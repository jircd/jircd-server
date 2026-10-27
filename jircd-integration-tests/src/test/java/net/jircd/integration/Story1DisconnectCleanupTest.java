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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Story1DisconnectCleanupTest {

    @ParameterizedTest(name = "cloak={0}, voluntaryQuit={1}")
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void quitPrefixMatchesPresentedJoinIdentity(boolean cloak, boolean voluntaryQuit)
            throws Exception {
        try (TestServer server = TestServer.start(cloak ? TestServer.CLOAK_ENABLED_YAML : "");
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            bob.registerAndAwaitWelcome("bob", "bob");
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.send("JOIN #lobby");
            bob.readUntil("366", Duration.ofSeconds(5));
            alice.send("JOIN #lobby");
            alice.readUntil("366", Duration.ofSeconds(5));
            String joinPrefix = bob.readUntil("JOIN", Duration.ofSeconds(5)).split(" ")[0];

            if (voluntaryQuit) {
                alice.send("QUIT :goodbye");
            } else {
                alice.close(); // Close without QUIT to exercise externally triggered cleanup.
            }

            String quitLine = bob.readUntil("QUIT", Duration.ofSeconds(5));
            assertThat(quitLine.split(" ")[0]).isEqualTo(joinPrefix);
        }
    }

    @Test
    void quitWithReasonNotifiesRemainingMembers() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            alice.send("QUIT :goodbye");
            String quitLine = bob.readUntil("QUIT", Duration.ofSeconds(5));
            assertThat(quitLine).contains("goodbye");
        }
    }

    @Test
    void bareQuitGetsANonBlankDefaultReason() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            alice.send("QUIT");
            String quitLine = bob.readUntil("QUIT", Duration.ofSeconds(5));
            assertThat(quitLine).contains("QUIT :");
            assertThat(quitLine.endsWith("QUIT :")).isFalse(); // reason is present, not blank
        }
    }

    @Test
    void abruptSocketCloseStillNotifiesRemainingMembers() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            try (RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                alice.registerAndAwaitWelcome("alice", "alice");
                alice.send("JOIN #lobby");
                alice.readUntil("353", Duration.ofSeconds(5));
                alice.close(); // abrupt: no QUIT sent — closed early, deliberately, to simulate it
            }

            String quitLine = bob.readUntil("QUIT", Duration.ofSeconds(10));
            assertThat(quitLine).contains("alice");
        }
    }
}
