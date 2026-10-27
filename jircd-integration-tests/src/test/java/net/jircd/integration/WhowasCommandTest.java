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
 * 002-extended-irc-commands User Story 4: last-known identity lookup after a disconnect, via
 * WHOWAS. Also exercises Story 3's KILL as one of its disconnection-cause cases.
 */
class WhowasCommandTest {

    @Test
    void whowasAfterAVoluntaryQuitReturnsTheLastKnownIdentity() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            bob.registerAndAwaitWelcome("bob", "bob");

            try (RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                carol.registerAndAwaitWelcome("carol", "carol");
                carol.send("QUIT :bye");
            }
            Thread.sleep(200); // let server-side cleanup (and the WHOWAS recording) complete

            bob.send("WHOWAS carol");
            String whowasUser = bob.readUntil("314", Duration.ofSeconds(5));
            assertThat(whowasUser).contains("carol").contains("carol").contains("*");
            assertThat(bob.readUntil("369", Duration.ofSeconds(5))).contains("369");
        }
    }

    @Test
    void whowasAfterAKillReturnsTheLastKnownIdentity() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient dave = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));
            dave.registerAndAwaitWelcome("dave", "dave");

            admin.send("KILL dave :bye");
            admin.readUntil("NOTICE", Duration.ofSeconds(5));
            Thread.sleep(200);

            admin.send("WHOWAS dave");
            assertThat(admin.readUntil("314", Duration.ofSeconds(5))).contains("dave");
        }
    }

    @Test
    void whowasForANeverSeenNicknameReturns406() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("WHOWAS neverconnected");
            assertThat(alice.readUntil("406", Duration.ofSeconds(5))).contains("406");
            assertThat(alice.readUntil("369", Duration.ofSeconds(5))).contains("369");
        }
    }

    @Test
    void whowasReturnsTheCloakedHostnameToNonAdministratorsAndTheRealOneToAdministrators()
            throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminAndCloakEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));
            bob.registerAndAwaitWelcome("bob", "bob");

            String cloakedHostname;
            try (RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                carol.registerAndAwaitWelcome("carol", "carol");
                // bob (neither carol herself nor an administrator) looks her up while she's still
                // connected, to capture the cloaked value a non-privileged observer actually sees —
                // a self-lookup (carol WHOIS-ing herself) would bypass cloak entirely (FR-038) and
                // defeat the point of this test.
                bob.send("WHOIS carol");
                String whoisUser = bob.readUntil("311", Duration.ofSeconds(5));
                cloakedHostname =
                        whoisUser.split(" ")[5]; // :server 311 bob carol ident <host> * :real
                bob.readUntil("318", Duration.ofSeconds(5)); // RPL_ENDOFWHOIS
                carol.send("QUIT :bye");
            }
            Thread.sleep(200);

            bob.send("WHOWAS carol");
            String bobSees = bob.readUntil("314", Duration.ofSeconds(5));
            assertThat(bobSees).contains(cloakedHostname);
            assertThat(bobSees).doesNotContain("127.0.0.1");

            admin.send("WHOWAS carol");
            String adminSees = admin.readUntil("314", Duration.ofSeconds(5));
            assertThat(adminSees).contains("127.0.0.1");
        }
    }

    @Test
    void repeatedNicknameReturnsTheMostRecentDisconnection() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            try (RawIrcClient first = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                first.send("NICK erin");
                first.send("USER old-user 0 * :First Identity");
                first.readUntil(" 001 ", Duration.ofSeconds(5));
                first.send("QUIT :bye");
            }
            Thread.sleep(200);

            try (RawIrcClient second = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                second.send("NICK erin");
                second.send("USER new-user 0 * :Second Identity");
                second.readUntil(" 001 ", Duration.ofSeconds(5));
                second.send("QUIT :bye");
            }
            Thread.sleep(200);

            alice.send("WHOWAS erin");
            String whowasUser = alice.readUntil("314", Duration.ofSeconds(5));
            assertThat(whowasUser).contains("new-user");
        }
    }
}
