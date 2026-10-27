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

class Story7WhoChannelTest {

    @Test
    void whoChannelMatchesNamesIncludingInvisibleMembersAndNeverGatesOnInvisibility()
            throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator, creates the channel
            bob.registerAndAwaitWelcome("bob", "bob");
            carol.registerAndAwaitWelcome("carol", "carol"); // stays a non-member

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));
            bob.send("MODE bob +i");
            bob.readUntil("MODE bob +i", Duration.ofSeconds(5));

            carol.send("WHO #lobby");
            String first = carol.readUntil("352", Duration.ofSeconds(5));
            String second = carol.readUntil("352", Duration.ofSeconds(5));
            String endOfWho = carol.readUntil("315", Duration.ofSeconds(5));
            assertThat(first + second).contains("alice").contains("bob");
            assertThat(endOfWho).contains("315");
        }
    }

    @Test
    void whoOnAPrivateChannelFromANonMemberReturnsNoSuchChannel() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("JOIN #secret");
            alice.readUntil("353", Duration.ofSeconds(5));
            alice.send("MODE #secret +s");
            alice.readUntil("MODE #secret +s", Duration.ofSeconds(5));

            bob.send("WHO #secret");
            assertThat(bob.readUntil("403", Duration.ofSeconds(5))).contains("403");
        }
    }
}
