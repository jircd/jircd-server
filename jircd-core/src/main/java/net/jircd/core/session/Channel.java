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
  private final Set<ClientSession> operators = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> voiced = ConcurrentHashMap.newKeySet();
  private final Set<ChannelMode> activeModes = ConcurrentHashMap.newKeySet();
  private final List<BanEntry> bans = new CopyOnWriteArrayList<>();
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
    return members;
  }

  public Set<ClientSession> operators() {
    return operators;
  }

  public Set<ClientSession> voiced() {
    return voiced;
  }

  public Set<ChannelMode> activeModes() {
    return activeModes;
  }

  public List<BanEntry> bans() {
    return bans;
  }

  public Set<String> invited() {
    return invited;
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
  public synchronized boolean addMember(ClientSession session) {
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
  public void removeMember(ClientSession session) {
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
  public void removeBan(String mask) {
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
