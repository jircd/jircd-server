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

import java.time.Duration;
import net.jircd.core.session.ConnectionMonitorLog;
import org.junit.jupiter.api.Test;

/**
 * 009-connection-monitoring-log: the server-sent {@code PING} carries the exact same token as the
 * connection's own monitoring-log entry (US2), and the idle interval that triggers it follows the
 * administrator-configured {@code keepAliveFrequencySeconds} value rather than a fixed constant.
 */
class PingTokenConsistencyTest {

    @Test
    void pingPayloadMatchesTheMonitoringLogToken() throws Exception {
        try (LogCapture capture = new LogCapture(ConnectionMonitorLog.class);
                TestServer server = TestServer.start("keepAliveFrequencySeconds: 2\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            String connectedMessage =
                    capture.awaitMessage(
                            message -> message.startsWith("connection-event=connected"),
                            Duration.ofSeconds(5));
            String loggedToken = ConnectionMonitorLogTest.extractToken(connectedMessage);

            String ping = alice.readUntil("PING", Duration.ofSeconds(10));
            String pingToken = ping.substring(ping.indexOf("PING") + 5).replace(":", "").trim();

            assertThat(pingToken).isEqualTo(loggedToken);
        }
    }

    @Test
    void configuredFrequencyChangesHowSoonThePingArrives() throws Exception {
        try (TestServer server = TestServer.start("keepAliveFrequencySeconds: 2\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            // Well under the 120s default and the old hardcoded 30s constant — only reachable if
            // the configured value is actually being honored, not silently ignored.
            assertThat(alice.readUntil("PING", Duration.ofSeconds(10))).contains("PING");
        }
    }
}
