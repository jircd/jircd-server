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
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A single connected client's live state, from TCP accept to disconnect. {@code connectionId} is
 * its identity; everything else is mutable state guarded by this aggregate.
 *
 * <p>S-1: {@code channelMemberships} is written only by {@link ChannelRegistry} transitions while
 * the corresponding channel's monitor is held, so it agrees with each channel's member set whenever
 * no transition is in progress (a transition updates the channel, then the session, and a live view
 * can observe the step between). Once the {@code DisconnectCleanup.cleanup} invocation that claimed
 * the disconnect returns, it is empty; a duplicate invocation returns immediately. S-2: only the
 * administrator grant and revoke operations change {@code UserMode.OPERATOR}; after a grant returns
 * it is present, after a revoke returns it is absent. The flag and the mode are written one after
 * the other, not atomically.
 */
public final class ClientSession {

  private final String connectionId;
  private final ConnectionLifecycle lifecycle = new ConnectionLifecycle();
  private final Set<String> negotiatedCapabilities = ConcurrentHashMap.newKeySet();
  private final Set<Channel> channelMemberships = ConcurrentHashMap.newKeySet();
  private final Set<Channel> channelMembershipsView =
      Collections.unmodifiableSet(channelMemberships);
  private final Set<UserMode> userModes = ConcurrentHashMap.newKeySet();
  private final Set<UserMode> userModesView = Collections.unmodifiableSet(userModes);
  private final RateLimitBucket rateLimitBucket;
  private final String realHostname;
  private final Instant connectedAt = Instant.now();

  private volatile SessionWriter writer;
  private volatile LivenessMonitor livenessMonitor;
  private volatile boolean negotiatingCapabilities;
  private volatile String nickname;
  private volatile String ident;
  private volatile String realname;
  private volatile String awayReason;
  private final AtomicBoolean administratorPrivilege = new AtomicBoolean(false);
  private final AtomicInteger failedOperAttempts = new AtomicInteger(0);
  private final AtomicReference<Instant> lastLivenessAt = new AtomicReference<>(Instant.now());

  public ClientSession(String connectionId, String realHostname, RateLimitBucket rateLimitBucket) {
    this.connectionId = connectionId;
    this.realHostname = realHostname;
    this.rateLimitBucket = rateLimitBucket;
  }

  public String connectionId() {
    return connectionId;
  }

  public Instant connectedAt() {
    return connectedAt;
  }

  public ConnectionLifecycle lifecycle() {
    return lifecycle;
  }

  public SessionWriter writer() {
    return writer;
  }

  public void attachWriter(SessionWriter writer) {
    this.writer = writer;
  }

  public LivenessMonitor livenessMonitor() {
    return livenessMonitor;
  }

  public void attachLivenessMonitor(LivenessMonitor livenessMonitor) {
    this.livenessMonitor = livenessMonitor;
  }

  public String realHostname() {
    return realHostname;
  }

  public String nickname() {
    return nickname;
  }

  public void setNickname(String nickname) {
    this.nickname = nickname;
  }

  public String ident() {
    return ident;
  }

  /**
   * Also the source-of-truth signal for the one-shot USER restriction: absent means USER hasn't
   * been processed yet.
   */
  public boolean hasProcessedUser() {
    return ident != null;
  }

  public void setIdent(String ident) {
    this.ident = ident;
  }

  public String realname() {
    return realname;
  }

  public void setRealname(String realname) {
    this.realname = realname;
  }

  /**
   * Non-{@code null} while away, holding the reason; {@code null} otherwise. Presence, not a
   * separate boolean, is the away/not-away signal — the same pattern {@link #ident} already uses as
   * its own registration-state signal.
   */
  public String awayReason() {
    return awayReason;
  }

  public boolean isAway() {
    return awayReason != null;
  }

  /** {@code null} clears away status; any other value sets/replaces the reason. */
  public void setAwayReason(String awayReason) {
    this.awayReason = awayReason;
  }

  public Set<String> negotiatedCapabilities() {
    return negotiatedCapabilities;
  }

  public boolean isNegotiatingCapabilities() {
    return negotiatingCapabilities;
  }

  public void setNegotiating(boolean negotiating) {
    this.negotiatingCapabilities = negotiating;
  }

  /** Returns a live, unmodifiable view of memberships owned by registry transitions. */
  public Set<Channel> channelMemberships() {
    return channelMembershipsView;
  }

  // Called by registry transitions while holding the registry and corresponding channel monitors.
  void addMembership(Channel channel) {
    channelMemberships.add(channel);
  }

  void removeMembership(Channel channel) {
    channelMemberships.remove(channel);
  }

  /** Returns a live, unmodifiable view of user modes. */
  public Set<UserMode> userModes() {
    return userModesView;
  }

  /**
   * Sets a non-operator mode; caller permissions are checked by the command handler.
   *
   * @throws IllegalArgumentException for OPERATOR, which requires grantAdministratorPrivilege
   */
  public void setUserMode(UserMode mode) {
    if (UserMode.OPERATOR.equals(mode)) {
      throw new IllegalArgumentException("Operator mode requires grantAdministratorPrivilege");
    }
    userModes.add(mode);
  }

  /**
   * Clears a non-operator mode; clearing an inactive mode is a no-op.
   *
   * @throws IllegalArgumentException for OPERATOR, which requires revokeAdministratorPrivilege
   */
  public void clearUserMode(UserMode mode) {
    if (UserMode.OPERATOR.equals(mode)) {
      throw new IllegalArgumentException("Operator mode requires revokeAdministratorPrivilege");
    }
    userModes.remove(mode);
  }

  public RateLimitBucket rateLimitBucket() {
    return rateLimitBucket;
  }

  public boolean isAdministrator() {
    return administratorPrivilege.get();
  }

  /** Grants administrator privilege and adds the operator user mode in the same act. */
  public void grantAdministratorPrivilege() {
    administratorPrivilege.set(true);
    userModes.add(UserMode.OPERATOR);
    failedOperAttempts.set(0);
  }

  /** Revokes administrator privilege and removes the operator user mode in the same act. */
  public void revokeAdministratorPrivilege() {
    administratorPrivilege.set(false);
    userModes.remove(UserMode.OPERATOR);
  }

  public int incrementFailedOperAttempts() {
    return failedOperAttempts.incrementAndGet();
  }

  public Instant lastLivenessAt() {
    return lastLivenessAt.get();
  }

  public void markAlive() {
    lastLivenessAt.set(Instant.now());
  }

  /**
   * Same as {@link #markAlive()}, but against a caller-supplied instant (e.g. an injected clock in
   * tests).
   */
  public void markAliveAt(Instant instant) {
    lastLivenessAt.set(instant);
  }
}
