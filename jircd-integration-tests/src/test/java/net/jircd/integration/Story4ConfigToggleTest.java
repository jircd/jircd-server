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

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import net.jircd.core.extension.Extension;
import net.jircd.server.SighupReloadHandler;
import org.junit.jupiter.api.Test;
import sun.misc.Signal;

class Story4ConfigToggleTest {

    @Test
    void disablingViaConfigAndSighupStopsTaggingWithoutRestart() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags echo-message");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            alice.send("PRIVMSG #lobby :before disable");
            String beforeDisable = alice.readUntil("PRIVMSG #lobby", Duration.ofSeconds(5));
            assertThat(beforeDisable).startsWith("@");

            SighupReloadHandler.install(server.application);
            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + """
              capabilities:
                message-tags: disabled
                server-time: enabled
                echo-message: enabled
              """);
            Signal.raise(new Signal("HUP"));
            awaitState(server, "message-tags", Extension.State.DISABLED);

            try (RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                bob.send("CAP LS 302");
                String capList = bob.readUntil("CAP * LS", Duration.ofSeconds(5));
                assertThat(capList).doesNotContain("message-tags");
            }

            alice.send("PRIVMSG #lobby :after disable");
            String afterDisable =
                    alice.readUntil("PRIVMSG #lobby :after disable", Duration.ofSeconds(5));
            assertThat(afterDisable).doesNotStartWith("@");
        }
    }

    /**
     * Polls {@code extensionRegistry().stateOf(id)} until it equals {@code expected} or times out.
     */
    private static void awaitState(TestServer server, String id, Extension.State expected)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (server.application.extensionRegistry().stateOf(id) == expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "Timed out waiting for extension '"
                        + id
                        + "' to reach state "
                        + expected
                        + "; was "
                        + server.application.extensionRegistry().stateOf(id));
    }
}
