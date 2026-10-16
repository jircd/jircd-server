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
import java.util.function.Predicate;

/**
 * Owns channel existence and membership transitions, with case-folded channel-name uniqueness.
 *
 * <p>Lock order: registry → channel; never two channels; no delivery under either monitor.
 * Admission runs under both monitors and must not block. All other delivery and extension work runs
 * after the transition returns.
 *
 * <p>R-1: at most one registered channel per folded name. R-2: after each transition no registered
 * channel is empty. R-3: channel membership and session membership agree whenever no transition
 * holds the registry monitor. R-4: joins that observe a CLOSING session are refused; cleanup claims
 * CLOSING before taking the registry monitor, so it also removes any join already in progress.
 */
public final class ChannelRegistry {
  private final Map<String, Channel> byFoldedName = new LinkedHashMap<>();

  /**
   * Returns the existing channel or creates one; membership transitions populate it under this
   * lock.
   */
  synchronized Channel getOrCreate(String name) {
    return byFoldedName.computeIfAbsent(CaseMapping.fold(name), ignored -> new Channel(name));
  }

  /**
   * Admits and joins a non-closing session under the registry then channel monitor. An existing
   * member skips admission and returns an unchanged membership with alreadyMember set.
   */
  public synchronized Optional<JoinResult> join(
      String name, ClientSession session, Predicate<Channel> admission) {
    boolean created = !byFoldedName.containsKey(CaseMapping.fold(name));
    Channel channel = getOrCreate(name);
    synchronized (channel) {
      if (session.lifecycle().isClosing()) {
        if (created) {
          removeIfEmpty(channel);
        }
        return Optional.empty();
      }
      if (channel.members().contains(session)) {
        return Optional.of(
            new JoinResult(channel, channel.members(), false, true, channel.memberListing()));
      }
      if (!admission.test(channel)) {
        if (created) {
          removeIfEmpty(channel);
        }
        return Optional.empty();
      }
      boolean grantedOperator = channel.addMember(session);
      session.addMembership(channel);
      return Optional.of(
          new JoinResult(
              channel, channel.members(), grantedOperator, false, channel.memberListing()));
    }
  }

  /**
   * Removes both sides of a membership and captures recipients before removal; empty means absent.
   */
  public synchronized Optional<Departure> part(Channel channel, ClientSession session) {
    synchronized (channel) {
      if (!channel.members().contains(session)) {
        return Optional.empty();
      }
      return Optional.of(depart(channel, session));
    }
  }

  /** Checks operator status and resolves the folded target nickname under the membership locks. */
  public synchronized KickOutcome kick(
      Channel channel, ClientSession kicker, String targetNickname) {
    synchronized (channel) {
      if (!channel.operators().contains(kicker)) {
        return new KickOutcome.NotOperator();
      }
      var target = channel.findMember(targetNickname);
      if (target.isEmpty()) {
        return new KickOutcome.NoSuchMember();
      }
      return new KickOutcome.Kicked(depart(channel, target.get()));
    }
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
      synchronized (channel) {
        channel.removeMember(session);
        neighbors.addAll(channel.members());
        session.removeMembership(channel);
        removeIfEmpty(channel);
      }
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
    synchronized (channel) {
      byFoldedName.computeIfPresent(
          CaseMapping.fold(channel.name()),
          (key, existing) -> existing == channel && existing.isEmpty() ? null : existing);
    }
  }

  /** Returns registered channels in creation order as a snapshot. */
  public synchronized Collection<Channel> all() {
    return List.copyOf(byFoldedName.values());
  }
}
