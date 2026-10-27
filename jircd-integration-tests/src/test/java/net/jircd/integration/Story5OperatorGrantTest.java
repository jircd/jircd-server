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

class Story5OperatorGrantTest {

    @Test
    void operatorGrantWorksRejoinLosesItAndSelfRevocationCanReachZeroOperators() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator (first join)
            bob.registerAndAwaitWelcome("bob", "bob");
            carol.registerAndAwaitWelcome("carol", "carol");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));
            carol.send("JOIN #lobby");
            carol.readUntil("353", Duration.ofSeconds(5));

            // non-operator can't grant operator
            bob.send("MODE #lobby +o bob");
            assertThat(bob.readUntil("482", Duration.ofSeconds(5))).contains("482");

            // +o naming a nickname that isn't connected anywhere is rejected
            // (005-fix-batch-conformance
            // FR-017 — 401, distinct from 441's "connected but not a member of this channel")
            alice.send("MODE #lobby +o nobody");
            assertThat(alice.readUntil("401", Duration.ofSeconds(5))).contains("401");

            alice.send("MODE #lobby +o bob");
            String grantEcho = bob.readUntil("MODE #lobby +o bob", Duration.ofSeconds(5));
            assertThat(grantEcho).contains("+o bob");

            // the newly-granted operator can moderate, same as the original operator
            bob.send("KICK #lobby carol");
            carol.readUntil("KICK #lobby carol", Duration.ofSeconds(5));

            // operator status was not retained across a part/rejoin (alice is still in #lobby, so
            // it isn't destroyed and recreated, which would confound this with
            // first-join-gets-operator)
            bob.send("PART #lobby");
            bob.readUntil("PART #lobby", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));
            bob.send("KICK #lobby alice");
            assertThat(bob.readUntil("482", Duration.ofSeconds(5))).contains("482");

            // self-revocation succeeds even leaving the channel with zero operators
            alice.send("MODE #lobby -o alice");
            String revokeEcho = alice.readUntil("MODE #lobby -o alice", Duration.ofSeconds(5));
            assertThat(revokeEcho).contains("-o alice");
            alice.send("KICK #lobby bob");
            assertThat(alice.readUntil("482", Duration.ofSeconds(5))).contains("482");
        }
    }
}
