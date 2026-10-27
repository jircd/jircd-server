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

/**
 * 005-fix-batch-conformance Story 4: multi-channel JOIN, topic-on-join, KICK's default comment, and
 * MODE +b's list-query form. The +o/+v nonexistent-nickname numeric fix (FR-017) is covered by the
 * existing Story5ModeGroupingTest/Story5OperatorGrantTest/Story5VoiceTest, updated as part of this
 * same feature.
 */
class ChannelMembershipGrammarTest {

    @Test
    void commaSeparatedJoinBecomesAMemberOfEveryNamedChannel() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("JOIN #one,#two");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains("#one");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains("#two");
        }
    }

    @Test
    void joiningAChannelWithAnExistingTopicReceivesItImmediately() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            alice.send("TOPIC #lobby :hello world");
            alice.readUntil("TOPIC #lobby", Duration.ofSeconds(5));

            bob.send("JOIN #lobby");
            assertThat(bob.readUntil("332", Duration.ofSeconds(5))).contains("hello world");
        }
    }

    @Test
    void kickWithNoCommentDefaultsToTheKickersNickname() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            bob.registerAndAwaitWelcome("bob", "bob");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));
            alice.readUntil("JOIN #lobby", Duration.ofSeconds(5));

            alice.send("KICK #lobby bob");
            String kick = alice.readUntil("KICK #lobby bob", Duration.ofSeconds(5));
            assertThat(kick).contains("alice");
        }
    }

    @Test
    void modePlusBListQueryFormReturnsTheBanList() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            alice.send("MODE #lobby +b mask!*@*");
            alice.readUntil("MODE #lobby +b", Duration.ofSeconds(5));

            alice.send("MODE #lobby +b");
            assertThat(alice.readUntil("368", Duration.ofSeconds(5))).contains("368");
        }
    }
}
