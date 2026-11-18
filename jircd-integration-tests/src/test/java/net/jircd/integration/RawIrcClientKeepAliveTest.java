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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RawIrcClientKeepAliveTest {

    @Test
    void answeringServerPingsKeepsConnectionAlivePastTimeout() throws Exception {
        try (TestServer server = TestServer.start("keepAliveFrequencySeconds: 1\n");
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.answerServerPings();
            client.registerAndAwaitWelcome("alice", "alice");

            assertThatThrownBy(
                            () -> client.readUntil("_not_a_server_line_", Duration.ofSeconds(12)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Timed out waiting for: _not_a_server_line_");
            assertThat(client.answeredServerPingCount()).isPositive();

            client.send("JOIN #liveness");
            assertThat(client.readUntil(" 353 ", Duration.ofSeconds(5)))
                    .contains(" 353 alice = #liveness ", "alice");
        }
    }
}
