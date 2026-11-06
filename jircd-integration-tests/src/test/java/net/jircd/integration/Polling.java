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
package net.jircd.integration;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Deadline polling for tests, without {@code Thread.sleep} (Sonar S2925). */
public final class Polling {

    private static final long INTERVAL_MILLIS = 50;

    private Polling() {}

    /**
     * Checks {@code condition} until it holds or {@code timeout} passes; the pause between checks
     * is a timed park.
     *
     * @return whether the condition held at the last check
     */
    public static boolean until(Duration timeout, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            pause(Duration.ofMillis(INTERVAL_MILLIS));
        }
        return true;
    }

    /** Waits for {@code duration} to pass, for the rare test whose condition is elapsed time. */
    public static void pause(Duration duration) throws InterruptedException {
        // parkNanos may return early (spuriously, or on unpark), so park until the deadline passes.
        long deadline = System.nanoTime() + duration.toNanos();
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) {
            LockSupport.parkNanos(remaining);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }
}
