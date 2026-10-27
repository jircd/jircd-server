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

/** 002-extended-irc-commands User Story 5: tag-only TAGMSG delivery. */
class TagmsgCommandTest {

    private static void negotiateMessageTags(RawIrcClient client) throws Exception {
        client.send("CAP REQ :message-tags");
        client.readUntil("CAP * ACK", Duration.ofSeconds(5));
        client.send("CAP END");
    }

    @Test
    void tagmsgIsDeliveredToNegotiatedChannelMembersAndDroppedForNonNegotiatedOnes()
            throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient dave = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            negotiateMessageTags(alice);
            alice.registerAndAwaitWelcome("alice", "alice");
            negotiateMessageTags(bob);
            bob.registerAndAwaitWelcome("bob", "bob");
            dave.registerAndAwaitWelcome("dave", "dave"); // no message-tags negotiated

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));
            dave.send("JOIN #lobby");
            dave.readUntil("353", Duration.ofSeconds(5));

            alice.send("@+example.com/typing=active TAGMSG #lobby");

            String bobReceived = bob.readUntil("TAGMSG", Duration.ofSeconds(5));
            assertThat(bobReceived).startsWith("@");
            assertThat(bobReceived).contains("+example.com/typing=active");
            assertThat(bobReceived).contains("TAGMSG #lobby");

            var daveLines = dave.readLinesFor(Duration.ofMillis(500));
            assertThat(daveLines).noneMatch(line -> line.contains("TAGMSG"));
        }
    }

    @Test
    void tagmsgIsDeliveredDirectlyToANegotiatedNickname() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            negotiateMessageTags(alice);
            alice.registerAndAwaitWelcome("alice", "alice");
            negotiateMessageTags(bob);
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("@+status=here TAGMSG bob");
            assertThat(bob.readUntil("TAGMSG", Duration.ofSeconds(5))).contains("+status=here");
        }
    }

    @Test
    void tagmsgWithNoTagsIsRejected() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            negotiateMessageTags(alice);
            alice.registerAndAwaitWelcome("alice", "alice");
            negotiateMessageTags(bob);
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("TAGMSG bob");
            var bobLines = bob.readLinesFor(Duration.ofMillis(500));
            assertThat(bobLines).noneMatch(line -> line.contains("TAGMSG"));
        }
    }

    @Test
    void senderWithEchoMessageReceivesTheirOwnTagmsgBack() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags echo-message");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            alice.send("@+example.com/typing=active TAGMSG #lobby");
            assertThat(alice.readUntil("TAGMSG", Duration.ofSeconds(5)))
                    .contains("+example.com/typing=active");
        }
    }
}
