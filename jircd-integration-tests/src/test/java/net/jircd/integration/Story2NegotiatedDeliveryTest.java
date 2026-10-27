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

class Story2NegotiatedDeliveryTest {

    @Test
    void negotiatingConnectionGetsTaggedEchoWhilePlainConnectionGetsUntagged() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :server-time echo-message");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");

            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            alice.send("PRIVMSG #lobby :hi there");

            String aliceEcho = alice.readUntil("PRIVMSG #lobby", Duration.ofSeconds(5));
            assertThat(aliceEcho).startsWith("@");
            assertThat(aliceEcho).contains("time=");
            assertThat(aliceEcho).contains(":alice!alice@");
            assertThat(aliceEcho).contains("PRIVMSG #lobby :hi there");

            String bobReceived = bob.readUntil("PRIVMSG #lobby", Duration.ofSeconds(5));
            assertThat(bobReceived).doesNotStartWith("@");
            assertThat(bobReceived).contains(":alice!alice@");
            assertThat(bobReceived).contains("PRIVMSG #lobby :hi there");
        }
    }
}
