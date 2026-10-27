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

import java.time.Clock;
import net.jircd.core.config.ServerConfiguration;

/**
 * Per-connection token bucket rate limiter (FR-016, research.md "Rate limiting"). Protects overall
 * service availability by capping the rate of messages/commands accepted from a single connection
 * (SC-006).
 */
public final class RateLimitBucket {

    private final int capacity;
    private final double refillPerSecond;
    private final Clock clock;
    private final Object lock = new Object();

    private double tokens;
    private long lastRefillNanos;

    public RateLimitBucket(int capacity, double refillPerSecond, Clock clock) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.clock = clock;
        this.tokens = capacity;
        this.lastRefillNanos = clock.instant().toEpochMilli() * 1_000_000L;
    }

    public static RateLimitBucket withDefaults() {
        return new RateLimitBucket(
                ServerConfiguration.DEFAULT_RATE_LIMIT_BUCKET_SIZE,
                ServerConfiguration.DEFAULT_RATE_LIMIT_REFILL_PER_SECOND,
                Clock.systemUTC());
    }

    /** Attempts to consume one token; returns {@code false} if the bucket is exhausted. */
    public boolean tryConsume() {
        synchronized (lock) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private void refill() {
        long nowNanos = clock.instant().toEpochMilli() * 1_000_000L;
        double elapsedSeconds = Math.max(0, nowNanos - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
        lastRefillNanos = nowNanos;
    }
}
