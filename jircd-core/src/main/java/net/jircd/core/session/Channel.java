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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A named, joinable group through which members exchange messages (data-model.md "Channel",
 * Aggregate Root). {@code name} is its identity; membership and moderation state are guarded by
 * this aggregate.
 */
public final class Channel {

  public static final int MAX_BANS = 100;

  private final String name;
  private final String foldedName;
  private final Set<ClientSession> members = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> membersView = Collections.unmodifiableSet(members);
  private final Set<ClientSession> operators = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> operatorsView = Collections.unmodifiableSet(operators);
  private final Set<ClientSession> voiced = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> voicedView = Collections.unmodifiableSet(voiced);
  private final Set<ChannelMode> activeModes = ConcurrentHashMap.newKeySet();
  private final Set<ChannelMode> activeModesView = Collections.unmodifiableSet(activeModes);
  private final List<BanEntry> bans = new CopyOnWriteArrayList<>();
  private final List<BanEntry> bansView = Collections.unmodifiableList(bans);
  private final Set<String> invited = ConcurrentHashMap.newKeySet();
  private volatile String topic;
  private volatile int memberLimit;
  private volatile String key;
  private final Instant createdAt = Instant.now();

  public Channel(String name) {
    this.name = name;
    this.foldedName = CaseMapping.fold(name);
  }

  public String name() {
    return name;
  }

  /**
   * When this channel instance was created (007-bare-mode-query FR-007) — a field initializer, not
   * a constructor parameter, so it resets naturally whenever a zero-member channel is recreated as
   * a fresh {@code Channel} object (FR-008), the same way {@code topic}/{@code memberLimit}/{@code
   * key} already reset.
   */
  public Instant createdAt() {
    return createdAt;
  }

  public Set<ClientSession> members() {
    return membersView;
  }

  /** Returns one status-prefixed nickname per member, in member iteration order. */
  public synchronized List<String> memberListing() {
    List<String> listing = new ArrayList<>();
    for (ClientSession member : members) {
      String status = "";
      if (operators.contains(member)) {
        status = "@";
      } else if (voiced.contains(member)) {
        status = "+";
      }
      listing.add(status + member.nickname());
    }
    return listing;
  }

  public Set<ClientSession> operators() {
    return operatorsView;
  }

  public Set<ClientSession> voiced() {
    return voicedView;
  }

  public Set<ChannelMode> activeModes() {
    return activeModesView;
  }

  public List<BanEntry> bans() {
    return bansView;
  }

  /** Adds an invitation using IRC case folding; repeated invitations remain a single entry. */
  public synchronized void invite(String nickname) {
    invited.add(CaseMapping.fold(nickname));
  }

  /** Reports whether this nickname has a pending invitation, using IRC case folding. */
  public synchronized boolean isInvited(String nickname) {
    return invited.contains(CaseMapping.fold(nickname));
  }

  /** Consumes a pending invitation once, returning whether it existed. */
  public synchronized boolean consumeInvitation(String nickname) {
    return invited.remove(CaseMapping.fold(nickname));
  }

  /** Grants operator status to a current member; returns false only for a non-member. */
  public synchronized boolean grantOperator(ClientSession member) {
    if (!members.contains(member)) {
      return false;
    }
    operators.add(member);
    return true;
  }

  /** Removes operator status, returning whether it was present. */
  public synchronized boolean revokeOperator(ClientSession member) {
    return operators.remove(member);
  }

  /** Grants voice to a current member; returns false only for a non-member. */
  public synchronized boolean grantVoice(ClientSession member) {
    if (!members.contains(member)) {
      return false;
    }
    voiced.add(member);
    return true;
  }

  /** Removes voice, returning whether it was present. */
  public synchronized boolean revokeVoice(ClientSession member) {
    return voiced.remove(member);
  }

  /** Activates a mode, clearing PRIVATE for SECRET and SECRET for PRIVATE in the same step. */
  public synchronized void setMode(ChannelMode mode) {
    if (CoreChannelModes.SECRET.equals(mode)) {
      activeModes.remove(CoreChannelModes.PRIVATE);
    } else if (CoreChannelModes.PRIVATE.equals(mode)) {
      activeModes.remove(CoreChannelModes.SECRET);
    }
    activeModes.add(mode);
  }

  /** Clears a mode; clearing an inactive mode is a no-op. */
  public synchronized void clearMode(ChannelMode mode) {
    activeModes.remove(mode);
  }

  public String topic() {
    return topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  /**
   * The {@code user-limit} channel mode's current value (006-complete-core-protocol FR-001) —
   * {@code 0} means unset (no limit), mirroring {@code topic}'s {@code null}-means-unset shape but
   * using {@code 0} since a real limit is always positive.
   */
  public int memberLimit() {
    return memberLimit;
  }

  public void setMemberLimit(int memberLimit) {
    this.memberLimit = memberLimit;
  }

  /**
   * The {@code channel-key} channel mode's current value (006-complete-core-protocol FR-004) —
   * {@code null} means unset.
   */
  public String key() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public boolean isEmpty() {
    return members.isEmpty();
  }

  /**
   * Adds a member, granting operator status if this is the channel's very first member (classic
   * first-join-gets-operator default, FR-013). Returns {@code true} if operator status was granted
   * by this call.
   */
  synchronized boolean addMember(ClientSession session) {
    boolean wasEmpty = members.isEmpty();
    members.add(session);
    if (wasEmpty) {
      operators.add(session);
    }
    return wasEmpty;
  }

  /**
   * Removes a departing session from membership, operator, and voice state together
   * (data-model.md).
   */
  synchronized void removeMember(ClientSession session) {
    members.remove(session);
    operators.remove(session);
    voiced.remove(session);
  }

  /**
   * Lookup of a current member by nickname (FR-064's `MEMBER`-kind targets), folded with the same
   * rfc1459 casemapping as {@link NicknameRegistry} (FR-052).
   */
  public Optional<ClientSession> findMember(String nickname) {
    String folded = CaseMapping.fold(nickname);
    return members.stream().filter(m -> CaseMapping.fold(m.nickname()).equals(folded)).findFirst();
  }

  /**
   * Adds a ban mask, enforcing the 100-entry cap atomically (FR-062) — {@code true} if the mask is
   * now present (including already-present, a no-op success), {@code false} if adding it would
   * exceed the cap.
   */
  public synchronized boolean addBan(BanEntry entry) {
    if (bans.stream().anyMatch(b -> b.mask().equals(entry.mask()))) {
      return true;
    }
    if (bans.size() >= MAX_BANS) {
      return false;
    }
    bans.add(entry);
    return true;
  }

  /** Removes a ban mask — a silent no-op if not present (FR-062). */
  public synchronized void removeBan(String mask) {
    bans.removeIf(b -> b.mask().equals(mask));
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof Channel other && foldedName.equals(other.foldedName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(foldedName);
  }
}
