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

class Story5PrivacyTest {

    @Test
    void secretChannelIsHiddenFromNonMembersButVisibleToMembersAndAdmins() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            bob.registerAndAwaitWelcome("bob", "bob"); // stays a non-member

            alice.send("JOIN #secret");
            alice.readUntil("353", Duration.ofSeconds(5));

            // +p then +s clears +p (mutual exclusion)
            alice.send("MODE #secret +p");
            alice.readUntil("MODE #secret +p", Duration.ofSeconds(5));
            alice.send("MODE #secret +s");
            String secretEcho = alice.readUntil("MODE #secret +s", Duration.ofSeconds(5));
            assertThat(secretEcho).contains("+s").doesNotContain("+p");

            bob.send("TOPIC #secret");
            assertThat(bob.readUntil("403", Duration.ofSeconds(5))).contains("403");

            // 006-complete-core-protocol Polish — NAMES has no error reply for a bad/hidden channel
            // name (RFC1459 §4.2.5/RFC2812 §3.2.5); a non-member sees only the closing
            // RPL_ENDOFNAMES, never 403 — indistinguishable from the channel simply not existing
            // (FR-047).
            bob.send("NAMES #secret");
            assertThat(bob.readUntil("366", Duration.ofSeconds(5))).contains("366");

            bob.send("LIST");
            String bobList = bob.readUntil("323", Duration.ofSeconds(5)); // RPL_LISTEND
            assertThat(bobList).doesNotContain("#secret");

            // a current member sees it normally in all three
            alice.send("TOPIC #secret");
            assertThat(alice.readUntil("331", Duration.ofSeconds(5)))
                    .contains("331"); // RPL_NOTOPIC

            alice.send("NAMES #secret");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains("#secret");
        }
    }

    @Test
    void secretChannelIsVisibleToAnAdministratorWhoIsNotAMember() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            root.registerAndAwaitWelcome(
                    "root", "root"); // stays a non-member, gains admin privilege
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            alice.send("JOIN #secret");
            alice.readUntil("353", Duration.ofSeconds(5));
            alice.send("MODE #secret +s");
            alice.readUntil("MODE #secret +s", Duration.ofSeconds(5));

            root.send("TOPIC #secret");
            assertThat(root.readUntil("331", Duration.ofSeconds(5))).contains("331"); // RPL_NOTOPIC

            root.send("NAMES #secret");
            assertThat(root.readUntil("353", Duration.ofSeconds(5)))
                    .contains("#secret")
                    .contains("alice");

            root.send("LIST");
            assertThat(root.readUntil("322", Duration.ofSeconds(5)))
                    .contains("#secret"); // RPL_LIST
        }
    }
}
