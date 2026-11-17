/*
 * Copyright 2026 JIRCD Maintainers
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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.Test;

/**
 * #151: the ping timeout claims CLOSING (which stops the connection counting) before it queues the
 * ERROR. A session another trigger already claimed gets no second ERROR.
 */
class LivenessTimeoutClaimOrderTest {

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.now();

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final Duration IDLE = Duration.ofSeconds(10);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static LivenessMonitor monitor(ClientSession session, MutableClock clock) {
        return new LivenessMonitor(
                session,
                new DisconnectCleanup(
                        new NicknameRegistry(),
                        new ChannelRegistry(),
                        new WhowasHistory(() -> 100),
                        new ExtensionRegistry()),
                IDLE,
                TIMEOUT,
                null,
                clock);
    }

    /** Sends the PING, then moves past the timeout so the next check times the session out. */
    private static void expirePing(LivenessMonitor monitor, MutableClock clock) {
        clock.advance(IDLE);
        monitor.checkNow();
        clock.advance(TIMEOUT);
    }

    @Test
    void timeoutAfterAnotherTriggerClaimedTheSessionQueuesNoError() throws Exception {
        MutableClock clock = new MutableClock();
        ClientSession session = new ClientSession("c1", "h", RateLimitBucket.withDefaults());
        session.markAliveAt(clock.instant());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
            session.attachWriter(writer);
            LivenessMonitor monitor = monitor(session, clock);
            expirePing(monitor, clock);
            session.lifecycle().claimClosing();

            monitor.checkNow();
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("ERROR");
    }

    @Test
    void timeoutClaimsTheSessionAndSendsErrorOnce() throws Exception {
        MutableClock clock = new MutableClock();
        ClientSession session = new ClientSession("c2", "h", RateLimitBucket.withDefaults());
        session.markAliveAt(clock.instant());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
            session.attachWriter(writer);
            LivenessMonitor monitor = monitor(session, clock);
            expirePing(monitor, clock);

            monitor.checkNow();

            assertThat(session.lifecycle().isClosing()).isTrue();
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("ERROR :Ping timeout\r\n");
        assertThat(out.toString(StandardCharsets.UTF_8).split("ERROR", -1)).hasSize(2);
    }
}
