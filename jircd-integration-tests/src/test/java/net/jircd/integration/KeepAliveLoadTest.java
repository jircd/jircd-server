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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * FR-039's server-initiated keep-alive, exercised end to end over a real socket — {@code
 * LivenessMonitor}'s own probe/timeout logic is already unit-tested deterministically (a fake
 * clock, no real waiting); this instead proves the production wiring itself (constructing one per
 * connection, attaching it, and actually ticking it — {@code ConnectionHandler}) works, which
 * nothing exercised before it was wired in.
 */
@Tag("load")
class KeepAliveLoadTest {

    /**
     * 009-connection-monitoring-log made the idle interval administrator-configurable (default
     * 120s) instead of a hardcoded 30s constant — both tests below configure a short interval
     * explicitly so this load test doesn't have to wait on the new, much larger default.
     */
    private static final String SHORT_KEEP_ALIVE_YAML = "keepAliveFrequencySeconds: 2\n";

    @Test
    void idleConnectionIsProbedThenDisconnectedIfNeverAnswered() throws Exception {
        try (TestServer server = TestServer.start(SHORT_KEEP_ALIVE_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            // Past the configured 2s idle interval (plus tick granularity) with no traffic sent: a
            // server-initiated PING must arrive.
            assertThat(alice.readUntil("PING", Duration.ofSeconds(10))).contains("PING");

            // No PONG is sent in reply: past the 10s timeout (plus tick granularity and scheduling
            // slack under the full load suite's contention — e.g. running alongside
            // ConcurrentConnectionScaleLoadTest's 1,000 connections), the server closes the
            // connection.
            assertThatThrownBy(() -> alice.readUntil("_never_matches_", Duration.ofSeconds(20)))
                    .hasMessageContaining("Connection closed");
        }
    }

    @Test
    void respondingToTheServersPingKeepsTheConnectionAlive() throws Exception {
        try (TestServer server = TestServer.start(SHORT_KEEP_ALIVE_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            String ping = alice.readUntil("PING", Duration.ofSeconds(10));
            String token = ping.substring(ping.indexOf("PING") + 5).replace(":", "").trim();
            alice.send("PONG " + token);

            // Well past the original timeout window, the connection is still usable.
            alice.send("JOIN #lobby");
            assertThat(alice.readUntil("353", Duration.ofSeconds(15))).contains("353");
        }
    }
}
