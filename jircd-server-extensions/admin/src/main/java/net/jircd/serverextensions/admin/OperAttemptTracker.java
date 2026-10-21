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
package net.jircd.serverextensions.admin;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Bounded source-address OPER failure windows, shared across sessions. Expired entries are
 * reclaimed lazily on the next attempt. A full tracker reports {@code CAPACITY_FULL} so the handler
 * can verify credentials with per-session failure counting, preserving access and live lockouts.
 * Authentication and accounting are serialized per source so concurrent connections cannot bypass
 * the threshold or block unrelated sources during credential verification.
 */
final class OperAttemptTracker {
  enum Result {
    SUCCESS,
    FAILURE,
    FAILURE_LIMIT_REACHED,
    CAPACITY_FULL,
    TLS_REQUIRED,
    LOCKED
  }

  /** Users includes threads waiting to verify; referenced entries must never be evicted. */
  private static final class Failures {
    private int count;
    private Instant expiresAt = Instant.MAX;
    private int users;
  }

  private final Clock clock;
  private final int capacity;
  private final Map<String, Failures> failures = new HashMap<>();
  private Instant nextExpiry = Instant.MAX;

  OperAttemptTracker(Clock clock, int capacity) {
    this.clock = clock;
    this.capacity = capacity;
  }

  /**
   * Serializes authentication only within a source bucket. Existing windows retain their original
   * deadline on reload; the current threshold applies immediately. Success clears the source's
   * failures, while locked or insecure attempts skip verification and never extend the deadline.
   */
  Result authenticate(
      String source,
      int threshold,
      int windowSeconds,
      boolean transportAllowed,
      BooleanSupplier verify) {
    Failures entry = acquire(source);
    if (entry == null) {
      return Result.CAPACITY_FULL;
    }
    try {
      synchronized (entry) {
        Instant now = clock.instant();
        if (!now.isBefore(entry.expiresAt)) {
          entry.count = 0;
        }
        if (entry.count >= threshold) {
          return Result.LOCKED;
        }
        if (!transportAllowed) {
          return Result.TLS_REQUIRED;
        }
        if (verify.getAsBoolean()) {
          entry.count = 0;
          return Result.SUCCESS;
        }
        if (entry.count == 0) {
          entry.expiresAt = now.plusSeconds(windowSeconds);
        }
        entry.count++;
        return entry.count >= threshold ? Result.FAILURE_LIMIT_REACHED : Result.FAILURE;
      }
    } finally {
      release(source, entry);
    }
  }

  // The map monitor guards membership/users. Entry state is read here only when users == 0,
  // after the last user published its writes through release(). Hashing never holds this monitor.
  private synchronized Failures acquire(String source) {
    Instant now = clock.instant();
    if (!now.isBefore(nextExpiry)) {
      failures.values().removeIf(value -> value.users == 0 && !now.isBefore(value.expiresAt));
      nextExpiry =
          failures.values().stream()
              .filter(value -> value.users == 0)
              .map(value -> value.expiresAt)
              .min(Instant::compareTo)
              .orElse(Instant.MAX);
    }
    Failures entry = failures.get(source);
    if (entry == null) {
      if (failures.size() >= capacity) {
        return null;
      }
      entry = new Failures();
      failures.put(source, entry);
    }
    entry.users++;
    return entry;
  }

  private synchronized void release(String source, Failures entry) {
    entry.users--;
    if (entry.users == 0) {
      if (entry.count == 0 || !clock.instant().isBefore(entry.expiresAt)) {
        failures.remove(source);
      } else if (entry.expiresAt.isBefore(nextExpiry)) {
        nextExpiry = entry.expiresAt;
      }
    }
  }
}
