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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 002-extended-irc-commands User Story 3: administrator-forced disconnect via KILL. */
class KillCommandTest {

    @Test
    void nonPrivilegedKillIsRejectedAndTargetStaysConnected() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            bob.registerAndAwaitWelcome("bob", "bob");
            carol.registerAndAwaitWelcome("carol", "carol");

            bob.send("KILL carol :test");
            assertThat(bob.readUntil("481", Duration.ofSeconds(5))).contains("481");

            carol.send("PING still-here");
            assertThat(carol.readUntil("PONG", Duration.ofSeconds(5))).contains("PONG");
        }
    }

    @Test
    void privilegedKillDisconnectsTargetWithAVisibleReason() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient dave = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));

            carol.registerAndAwaitWelcome("carol", "carol");
            dave.registerAndAwaitWelcome("dave", "dave");
            carol.send("JOIN #lobby");
            carol.readUntil("353", Duration.ofSeconds(5));
            dave.send("JOIN #lobby");
            dave.readUntil("353", Duration.ofSeconds(5));

            admin.send("KILL carol :abusive behavior");

            assertThat(carol.readUntil("ERROR", Duration.ofSeconds(5)))
                    .contains("abusive behavior");
            assertThatThrownBy(() -> carol.readUntil("_never_matches_", Duration.ofSeconds(5)))
                    .hasMessageContaining("Connection closed");

            String daveSees = dave.readUntil("carol", Duration.ofSeconds(5));
            assertThat(daveSees).contains("admin").contains("abusive behavior");
        }
    }

    @Test
    void killOfANonexistentNicknameReturnsNoSuchNick() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));

            admin.send("KILL doesnotexist");
            assertThat(admin.readUntil("401", Duration.ofSeconds(5))).contains("401");
        }
    }

    @Test
    void administratorMayKillTheirOwnNickname() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));

            admin.send("KILL admin :self-test");
            assertThat(admin.readUntil("ERROR", Duration.ofSeconds(5))).contains("self-test");
        }
    }
}
