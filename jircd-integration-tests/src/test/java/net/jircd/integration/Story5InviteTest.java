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

class Story5InviteTest {

    @Test
    void inviteOnlyRequiresAnInvitationConsumedOnJoin() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            bob.registerAndAwaitWelcome("bob", "bob");
            carol.registerAndAwaitWelcome("carol", "carol");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            carol.send("JOIN #lobby");
            carol.readUntil("353", Duration.ofSeconds(5));
            alice.readUntil("JOIN #lobby", Duration.ofSeconds(5));

            alice.send("MODE #lobby +i");
            alice.readUntil("MODE #lobby +i", Duration.ofSeconds(5));

            // INVITE naming a nickname already a member is rejected
            alice.send("INVITE carol #lobby");
            assertThat(alice.readUntil("443", Duration.ofSeconds(5))).contains("443");

            // INVITE naming a nickname not currently connected is rejected
            alice.send("INVITE nobody #lobby");
            assertThat(alice.readUntil("401", Duration.ofSeconds(5))).contains("401");

            // a non-operator's INVITE while +i is active is rejected
            carol.send("INVITE bob #lobby");
            assertThat(carol.readUntil("482", Duration.ofSeconds(5))).contains("482");

            bob.send("JOIN #lobby");
            assertThat(bob.readUntil("473", Duration.ofSeconds(5))).contains("473");

            alice.send("INVITE bob #lobby");
            assertThat(alice.readUntil("341", Duration.ofSeconds(5))).contains("341");
            String inviteMsg = bob.readUntil("INVITE bob #lobby", Duration.ofSeconds(5));
            assertThat(inviteMsg).startsWith(":alice!alice@");

            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            // the invitation was consumed — a second join after parting is rejected again
            bob.send("PART #lobby");
            bob.readUntil("PART #lobby", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            assertThat(bob.readUntil("473", Duration.ofSeconds(5))).contains("473");

            // once +i is cleared, a non-operator member's INVITE succeeds
            alice.send("MODE #lobby -i");
            alice.readUntil("MODE #lobby -i", Duration.ofSeconds(5));
            carol.send("INVITE bob #lobby");
            assertThat(carol.readUntil("341", Duration.ofSeconds(5))).contains("341");

            // INVITE from a sender who isn't a member of an EXISTING target channel is rejected —
            // using an existing channel here specifically, since inviting to a not-yet-existing one
            // now succeeds instead (006-complete-core-protocol FR-013, covered by
            // InviteNotYetExistingChannelTest).
            alice.send("JOIN #other");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("INVITE carol #other");
            assertThat(bob.readUntil("442", Duration.ofSeconds(5))).contains("442");
        }
    }
}
