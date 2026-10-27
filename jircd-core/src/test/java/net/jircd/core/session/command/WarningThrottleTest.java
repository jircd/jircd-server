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
package net.jircd.core.session.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import net.jircd.core.session.WarningThrottle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WarningThrottleTest {
    private final AtomicLong clock = new AtomicLong();
    private WarningThrottle throttle;

    @BeforeEach
    void createThrottle() {
        throttle = new WarningThrottle(100, 2, clock::get);
    }

    @Test
    void admitsTheFirstOccurrenceThenSuppressesWithinTheInterval() {
        assertThat(throttle.admit("a")).isZero();
        assertThat(throttle.admit("a")).isEqualTo(-1);
        clock.set(99);
        assertThat(throttle.admit("a")).isEqualTo(-1);
    }

    @Test
    void reportsSuppressedCountWhenTheIntervalElapses() {
        throttle.admit("a");
        throttle.admit("a");
        throttle.admit("a");
        clock.set(100);
        assertThat(throttle.admit("a")).isEqualTo(2);
        assertThat(throttle.admit("a")).isEqualTo(-1);
    }

    @Test
    void keysAreIndependent() {
        throttle.admit("a");
        assertThat(throttle.admit("b")).isZero();
    }

    @Test
    void remembersAtMostMaxKeys() {
        throttle.admit("a");
        throttle.admit("b");
        throttle.admit("c");
        assertThat(throttle.admit("a")).as("evicted, so logged as a first occurrence").isZero();
    }

    @Test
    void resetForgetsEveryKey() {
        throttle.admit("a");
        throttle.reset();
        assertThat(throttle.admit("a")).isZero();
    }
}
