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
import java.util.ArrayList;
import java.util.List;
import net.jircd.core.session.ConnectionMonitorLog;
import org.junit.jupiter.api.Test;

/**
 * 009-connection-monitoring-log Story 3: connection tokens are opaque UUIDs, not sequential
 * counters — nothing about them reveals connection order or count.
 */
class ConnectionTokenOpacityTest {

    private static final java.util.regex.Pattern UUID_FORMAT =
            java.util.regex.Pattern.compile(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Test
    void tokensAreOpaqueUuidsNotSequentialCounters() throws Exception {
        try (LogCapture capture = new LogCapture(ConnectionMonitorLog.class);
                TestServer server = TestServer.start()) {

            List<String> tokens = new ArrayList<>();
            int previousConnectedCount = 0;
            for (int i = 0; i < 3; i++) {
                int expectedCount = previousConnectedCount + 1;
                try (RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                    client.registerAndAwaitWelcome("nick" + i, "user" + i);
                    capture.awaitMessage(
                            m ->
                                    capture.messages().stream()
                                                    .filter(
                                                            x ->
                                                                    x.startsWith(
                                                                            "connection-event=connected"))
                                                    .count()
                                            >= expectedCount,
                            Duration.ofSeconds(5));
                    List<String> connectedMessages =
                            capture.messages().stream()
                                    .filter(m -> m.startsWith("connection-event=connected"))
                                    .toList();
                    tokens.add(ConnectionMonitorLogTest.extractToken(connectedMessages.get(i)));
                    previousConnectedCount = connectedMessages.size();
                }
            }

            assertThat(tokens).hasSize(3);
            assertThat(tokens).doesNotHaveDuplicates();
            for (String token : tokens) {
                assertThat(token).matches(UUID_FORMAT.pattern());
                assertThat(token).doesNotMatch("^c\\d+$");
            }
        }
    }
}
