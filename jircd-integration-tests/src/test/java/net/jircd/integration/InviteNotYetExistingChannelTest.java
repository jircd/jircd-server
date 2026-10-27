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
 * 006-complete-core-protocol Story 5: {@code INVITE} to a channel name that doesn't exist anywhere
 * on the server succeeds; inviting to an existing channel by a non-member is still rejected.
 */
class InviteNotYetExistingChannelTest {

    @Test
    void invitingToANotYetExistingChannelSucceeds() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("INVITE bob #brandnew");
            assertThat(alice.readUntil("341", Duration.ofSeconds(5))).contains("#brandnew");
            assertThat(bob.readUntil("INVITE", Duration.ofSeconds(5))).contains("#brandnew");
        }
    }

    @Test
    void invitingToAnExistingChannelByANonMemberIsStillRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #other");
            alice.readUntil("353", Duration.ofSeconds(5));

            bob.registerAndAwaitWelcome("bob", "bob"); // not a member of #other
            carol.registerAndAwaitWelcome("carol", "carol");

            bob.send("INVITE carol #other");
            assertThat(bob.readUntil("442", Duration.ofSeconds(5))).contains("442");
        }
    }
}
