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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Server-wide channel-name uniqueness with create-on-first-join (FR-003), compared using the same
 * rfc1459 casemapping as {@link NicknameRegistry} (FR-052) — {@code #Foo} and {@code #foo} resolve
 * to one channel, storing whichever casing created it. Its narrow public surface leaves room to
 * later span servers without callers changing (FR-022).
 *
 * <p>Backed by a synchronized, insertion-ordered map rather than a lock-free {@code
 * ConcurrentHashMap} — {@link #all()}'s iteration order needs to be deterministic (creation order)
 * for a bare {@code NAMES} (006-complete-core-protocol FR-010) to have a stable, repeatable
 * response shape; channel creation/removal is low-frequency compared to per-message traffic, so a
 * single lock here has no measurable contention risk.
 */
public final class ChannelRegistry {

  private final Map<String, Channel> byFoldedName = new LinkedHashMap<>();

  /** Returns the existing channel of this name, or atomically creates and returns a new one. */
  public synchronized Channel getOrCreate(String name) {
    String folded = CaseMapping.fold(name);
    return byFoldedName.computeIfAbsent(folded, ignored -> new Channel(name));
  }

  /**
   * Checks admission and inserts membership while holding the registry lock, so empty-channel
   * removal cannot detach the channel between lookup and insertion. Admission must not block.
   */
  public synchronized Optional<Channel> join(
      String name, ClientSession session, Predicate<Channel> admission) {
    Channel channel = getOrCreate(name);
    if (!admission.test(channel)) {
      return Optional.empty();
    }
    channel.addMember(session);
    session.channelMemberships().add(channel);
    return Optional.of(channel);
  }

  public synchronized Optional<Channel> lookup(String name) {
    return Optional.ofNullable(byFoldedName.get(CaseMapping.fold(name)));
  }

  /**
   * Removes a channel once it has no remaining members (FR-003 — a zero-member channel is not
   * durable).
   */
  public synchronized void removeIfEmpty(Channel channel) {
    byFoldedName.computeIfPresent(
        CaseMapping.fold(channel.name()),
        (key, existing) -> {
          if (existing == channel && existing.isEmpty()) {
            return null;
          }
          return existing;
        });
  }

  public synchronized Collection<Channel> all() {
    return List.copyOf(byFoldedName.values());
  }
}
