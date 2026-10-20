/*
 * Copyright 2026 Guillermo Castro
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.jircd.core.session;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owns channel existence and membership transitions, with case-folded channel-name uniqueness.
 *
 * <p>Lock order: registry → channel; never two channels; no delivery under either monitor. Every
 * channel lookup takes the registry monitor, so JOIN does no O(members) work and runs no extension
 * code under it: admission must not block or call extensions, and the JOIN snapshot is read after
 * both monitors are released.
 *
 * <p>R-1: at most one registered channel per folded name. R-2: after each transition no registered
 * channel is empty. R-3: channel membership and session membership agree whenever no transition
 * holds the registry monitor. R-4: joins that observe a CLOSING session are refused; a join that
 * passed that check may still finish its insertion, and cleanup, which claims CLOSING before taking
 * the registry monitor, then removes it.
 */
public final class ChannelRegistry {
  private final Map<String, Channel> byFoldedName = new LinkedHashMap<>();
  private final Consumer<Channel> joinSnapshotHook;

  public ChannelRegistry() {
    this(channel -> {});
  }

  /**
   * Test-only seam: runs after both monitors are released, at the start of a JOIN's NAMES and
   * recipient reads. Not a production extension point; a hook that throws leaves the membership in
   * place.
   */
  ChannelRegistry(Consumer<Channel> joinSnapshotHook) {
    this.joinSnapshotHook = joinSnapshotHook;
  }

  /**
   * Returns the existing channel or creates one; membership transitions populate it under this
   * lock.
   */
  synchronized Channel getOrCreate(String name) {
    return byFoldedName.computeIfAbsent(CaseMapping.fold(name), ignored -> new Channel(name));
  }

  /**
   * Admits and joins a non-closing session under the registry then channel monitor. An existing
   * member skips admission and returns an unchanged membership with alreadyMember set. The NAMES
   * listing and then the recipients are read after both monitors are released, from the weakly
   * consistent live views, so this O(members) work never holds the registry monitor. The joiner is
   * always a recipient of its own JOIN; the listing is never patched to include it.
   */
  public Optional<JoinResult> join(
      String name, ClientSession session, Predicate<Channel> admission) {
    Channel channel;
    boolean alreadyMember;
    boolean grantedOperator = false;
    synchronized (this) {
      boolean created = !byFoldedName.containsKey(CaseMapping.fold(name));
      channel = getOrCreate(name);
      synchronized (channel) {
        if (session.lifecycle().isClosing()) {
          if (created) {
            removeIfEmpty(channel);
          }
          return Optional.empty();
        }
        alreadyMember = channel.members().contains(session);
        if (!alreadyMember) {
          boolean admitted = false;
          try {
            admitted = admission.test(channel);
          } finally {
            if (!admitted && created) {
              removeIfEmpty(channel);
            }
          }
          if (!admitted) {
            return Optional.empty();
          }
          grantedOperator = channel.addMember(session);
          session.addMembership(channel);
        }
      }
    }
    joinSnapshotHook.accept(channel);
    List<String> listing = channel.memberListing();
    Set<ClientSession> recipients = new LinkedHashSet<>(channel.members());
    recipients.add(session);
    return Optional.of(
        new JoinResult(channel, recipients, grantedOperator, alreadyMember, listing));
  }

  /**
   * Removes both sides of a membership and captures recipients before removal; empty means absent.
   */
  public synchronized Optional<Departure> part(Channel channel, ClientSession session) {
    return channel.underMonitor(
        () -> {
          if (!channel.members().contains(session)) {
            return Optional.empty();
          }
          return Optional.of(depart(channel, session));
        });
  }

  /** Checks operator status and resolves the folded target nickname under the membership locks. */
  public synchronized KickOutcome kick(
      Channel channel, ClientSession kicker, String targetNickname) {
    return channel.underMonitor(
        () -> {
          if (!channel.operators().contains(kicker)) {
            return new KickOutcome.NotOperator();
          }
          var target = channel.findMember(targetNickname);
          if (target.isEmpty()) {
            return new KickOutcome.NoSuchMember();
          }
          return new KickOutcome.Kicked(depart(channel, target.get()));
        });
  }

  // Both monitors are already held. Keep delivery out of this state-only operation.
  private Departure depart(Channel channel, ClientSession session) {
    Departure departure = new Departure(channel, session, channel.members());
    channel.removeMember(session);
    session.removeMembership(channel);
    removeIfEmpty(channel);
    return departure;
  }

  /**
   * Removes every membership, capturing remaining members after each removal. Cleanup must first
   * claim CLOSING; the returned immutable neighbor snapshot excludes self and is deduplicated.
   */
  public synchronized Set<ClientSession> leaveAll(ClientSession session) {
    Set<ClientSession> neighbors = new LinkedHashSet<>();
    for (Channel channel : Set.copyOf(session.channelMemberships())) {
      channel.runUnderMonitor(
          () -> {
            channel.removeMember(session);
            neighbors.addAll(channel.members());
            session.removeMembership(channel);
            removeIfEmpty(channel);
          });
    }
    neighbors.remove(session);
    return Collections.unmodifiableSet(neighbors);
  }

  /** Looks up a registered channel using IRC case mapping. */
  public synchronized Optional<Channel> lookup(String name) {
    return Optional.ofNullable(byFoldedName.get(CaseMapping.fold(name)));
  }

  /**
   * Removes only this instance, if still registered and empty. Caller holds the registry monitor.
   */
  synchronized void removeIfEmpty(Channel channel) {
    channel.runUnderMonitor(
        () ->
            byFoldedName.computeIfPresent(
                CaseMapping.fold(channel.name()),
                (key, existing) -> existing == channel && existing.isEmpty() ? null : existing));
  }

  /** Returns registered channels in creation order as a snapshot. */
  public synchronized Collection<Channel> all() {
    return List.copyOf(byFoldedName.values());
  }
}
