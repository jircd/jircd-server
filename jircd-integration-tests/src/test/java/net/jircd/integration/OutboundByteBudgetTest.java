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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundByteBudgetTest {
    @ParameterizedTest
    @ValueSource(strings = {"PRIVMSG", "NOTICE"})
    void nearLimitUtf8TextFitsForBothTaggedEchoAndPlainRecipient(String command) throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.send("CAP REQ :message-tags server-time echo-message");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");

            String header = command + " bob :begin ";
            int remaining = 510 - header.getBytes(StandardCharsets.UTF_8).length;
            String incoming = header + "😀".repeat(remaining / 4) + "x".repeat(remaining % 4);
            assertThat(incoming.getBytes(StandardCharsets.UTF_8).length + 2).isEqualTo(512);
            alice.send(incoming);

            String delivered = bob.readUntil(command + " bob", Duration.ofSeconds(5));
            String echo = alice.readUntil(command + " bob", Duration.ofSeconds(5));
            assertThat(delivered).startsWith(":alice!alice@");
            assertThat(delivered.getBytes(StandardCharsets.UTF_8).length + 2)
                    .isLessThanOrEqualTo(512);
            assertThat(echo).startsWith("@").contains("time=");
            String echoCommand = echo.substring(echo.indexOf(' ') + 1);
            assertThat(echoCommand).isEqualTo(delivered);
            String body = delivered.substring(delivered.indexOf(" :begin ") + 2);
            assertThat(body).doesNotContain("\ufffd");
            assertThat(incoming.substring(header.indexOf(':') + 1)).startsWith(body);
            assertThat(body).endsWith("😀");
            assertThat(delivered).doesNotContain("417");

            alice.send(command + " bob :after-limit");
            assertThat(bob.readUntil("after-limit", Duration.ofSeconds(5)))
                    .contains(command + " bob");
            assertThat(alice.readUntil("after-limit", Duration.ofSeconds(5)))
                    .contains(command + " bob");
        }
    }
}
