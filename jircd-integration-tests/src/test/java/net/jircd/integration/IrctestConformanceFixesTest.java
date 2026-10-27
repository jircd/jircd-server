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
 * 003-irctest-conformance-fixes: one test per corrected finding (FR-001 through FR-007), plus two
 * regression tests confirming FR-008/FR-009's explicit no-change decisions.
 */
class IrctestConformanceFixesTest {

    @Test
    void quitSendsErrorBeforeClosing() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("QUIT :bye");
            assertThat(alice.readUntil("ERROR", Duration.ofSeconds(5))).contains("ERROR");
        }
    }

    @Test
    void emptyRealnameIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.send("NICK alice");
            alice.send("USER alice 0 * :");
            assertThat(alice.readUntil("461", Duration.ofSeconds(5))).contains("461");
        }

        try (TestServer server = TestServer.start();
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            bob.registerAndAwaitWelcome("bob", "bob"); // non-empty realname still registers
        }
    }

    @Test
    void namesVisibilitySymbolReflectsActualChannelMode() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome(
                    "alice", "alice"); // first joiner becomes channel operator

            alice.send("JOIN #lobby");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains(" = #lobby ");

            alice.send("MODE #lobby +s");
            alice.readUntil("MODE #lobby +s", Duration.ofSeconds(5));
            alice.send("NAMES #lobby");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains(" @ #lobby ");

            alice.send("MODE #lobby -s+p");
            alice.readUntil("MODE #lobby", Duration.ofSeconds(5));
            alice.send("NAMES #lobby");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains(" * #lobby ");

            alice.send("MODE #lobby -p");
            alice.readUntil("MODE #lobby -p", Duration.ofSeconds(5));
            alice.send("NAMES #lobby");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains(" = #lobby ");
        }
    }

    @Test
    void lusersTextMatchesTheConventionalShapeAndReportsARealInvisibleCount() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");

            bob.send("MODE bob +i");
            bob.readUntil("MODE bob +i", Duration.ofSeconds(5));

            alice.send("LUSERS");
            String reply = alice.readUntil("251", Duration.ofSeconds(5));
            assertThat(reply).contains("There are 2 users and 1 invisible on 1 servers");
        }
    }

    @Test
    void bareWhowasReturnsNoNicknameGivenNotGenericNeedMoreParams() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("WHOWAS");
            assertThat(alice.readUntil("431", Duration.ofSeconds(5))).contains("431");
        }
    }

    @Test
    void whoAndWhoisIncludeTheServerNameFieldAndWhoisAcceptsTheTwoParameterForm() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");

            bob.send("WHO alice");
            String whoReply = bob.readUntil("352", Duration.ofSeconds(5));
            // <me> 352 bob <chan/*> <user> <host> <server> <nick> <flags> :<hop> <real>
            assertThat(whoReply.split(" ")).contains("test.jircd.local");

            bob.send("WHOIS alice");
            String whoisUser = bob.readUntil("311", Duration.ofSeconds(5));
            String whoisServer = bob.readUntil("312", Duration.ofSeconds(5));
            assertThat(whoisServer).contains("alice").contains("test.jircd.local");
            bob.readUntil("318", Duration.ofSeconds(5));
            assertThat(whoisUser).isNotEmpty();

            // Two-parameter WHOIS <target-server> <nickname> form must still resolve alice, not
            // 401.
            bob.send("WHOIS test.jircd.local alice");
            String twoParamUser = bob.readUntil("311", Duration.ofSeconds(5));
            assertThat(twoParamUser).contains("alice");
            bob.readUntil("318", Duration.ofSeconds(5));
        }
    }

    @Test
    void namesOnANeverCreatedChannelIsIndistinguishableFromAHiddenChannel() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            // 006-complete-core-protocol Polish — NAMES has no error reply for a bad/nonexistent
            // channel name (RFC1459 §4.2.5/RFC2812 §3.2.5); only the closing RPL_ENDOFNAMES is
            // sent, never 403 — still indistinguishable from a hidden channel (FR-047), just via a
            // different numeric than before this feature.
            alice.send("NAMES #never-created");
            assertThat(alice.readUntil("366", Duration.ofSeconds(5))).contains("366");
        }
    }

    @Test
    void utf8NicknamesAreStillRejectedAsErroneous() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.send("NICK Işıl");
            assertThat(alice.readUntil("432", Duration.ofSeconds(5))).contains("432");
        }
    }
}
