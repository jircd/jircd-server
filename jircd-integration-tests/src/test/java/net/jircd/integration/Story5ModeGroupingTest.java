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

class Story5ModeGroupingTest {

    @Test
    void oneCommandCanCombineMultipleModeChangesNotAtomically() throws Exception {
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

            alice.send("MODE #lobby +b mask1!*@*");
            alice.readUntil("MODE #lobby +b mask1", Duration.ofSeconds(5));

            // one command: bob gets operator, bob gets voice (redundant with op, still applied),
            // mask1 removed
            alice.send("MODE #lobby +ov-b bob bob mask1");
            String echo = alice.readUntil("MODE #lobby", Duration.ofSeconds(5));
            assertThat(echo).contains("+ov-b").contains("bob bob mask1");

            alice.send("NAMES #lobby");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains("@bob");

            alice.send("MODE #lobby b");
            assertThat(alice.readUntil("368", Duration.ofSeconds(5))).contains("368");

            // pure BOOLEAN flags combine in one invocation
            alice.send("MODE #lobby +mn");
            String boolEcho = alice.readUntil("MODE #lobby +mn", Duration.ofSeconds(5));
            assertThat(boolEcho).contains("+mn");

            // a later flag failing still leaves the earlier flag applied, not rolled back
            alice.send("JOIN #other");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #other");
            bob.readUntil("353", Duration.ofSeconds(5));

            // not-a-member isn't connected anywhere, so this is 401, not 441
            // (005-fix-batch-conformance FR-017)
            alice.send("MODE #other +ov bob not-a-member");
            assertThat(alice.readUntil("401", Duration.ofSeconds(5))).contains("401");
            String partial = alice.readUntil("MODE #other", Duration.ofSeconds(5));
            assertThat(partial).contains("+o bob");
        }
    }
}
