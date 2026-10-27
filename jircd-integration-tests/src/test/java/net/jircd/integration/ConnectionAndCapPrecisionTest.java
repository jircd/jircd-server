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
 * 005-fix-batch-conformance Story 3: PING/PONG and CAP wire-format precision, client-tag
 * forwarding, and the independent tag/command length limits. FR-012 (invalid UTF-8 during
 * registration closes the connection) is covered by {@link Utf8ValidationTest} already.
 */
class ConnectionAndCapPrecisionTest {

    @Test
    void capListReportsOnlyCurrentlyNegotiatedCapabilities() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));

            alice.send("CAP LIST");
            String list = alice.readUntil("CAP * LIST", Duration.ofSeconds(5));
            assertThat(list).contains("message-tags").doesNotContain("echo-message");
        }
    }

    @Test
    void invalidCapSubcommandGetsTheSpecificError() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP BOGUS");
            // irctest's own testInvalidCapSubcommand expects the invalid subcommand token echoed
            // back as its own param (`410 * BOGUS :Invalid CAP subcommand`), not folded into the
            // reason text.
            String reply = alice.readUntil("410", Duration.ofSeconds(5));
            assertThat(reply).contains("410").contains("BOGUS");
        }
    }

    @Test
    void capNakEchoesBackDuplicatesInTheRequestedList() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :foo bar foo");
            String nak = alice.readUntil("CAP * NAK", Duration.ofSeconds(5));
            assertThat(nak).contains("foo bar foo");
        }
    }

    @Test
    void clientTagSurvivesRelayToANegotiatedRecipient() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");

            bob.send("CAP REQ :message-tags");
            bob.readUntil("CAP * ACK", Duration.ofSeconds(5));
            bob.send("CAP END");
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("@+example-tag=value PRIVMSG bob :hi");
            assertThat(bob.readUntil("PRIVMSG bob", Duration.ofSeconds(5)))
                    .contains("+example-tag=value");
        }
    }

    @Test
    void tagSectionAloneExceedingTheLimitIsRejectedEvenUnderTheOldCombinedThreshold()
            throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");

            // A tag section alone over 4096 bytes, with a short command+params section — total well
            // under the old combined 4608-byte threshold, but the tag section on its own is too
            // long.
            StringBuilder tags = new StringBuilder("@+huge=");
            tags.append("a".repeat(4090));
            alice.send(tags + " PING x");
            assertThat(alice.readUntil("417", Duration.ofSeconds(5))).contains("417");
        }
    }

    @Test
    void tagSectionRejectionCountsTheTrailingSpaceTowardTheLimit() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");

            // The message-tags spec counts the leading '@' AND the trailing space toward the
            // 4096-byte limit. "@foo=bar;+baz=" + 4081 'a's + " PING x" puts the tag section
            // (including the trailing space) at exactly 4097 bytes — one over the limit.
            String tagSection = "@foo=bar;+baz=" + "a".repeat(4082);
            alice.send(tagSection + " PING x");
            assertThat(alice.readUntil("417", Duration.ofSeconds(5))).contains("417");
        }
    }

    @Test
    void clientTagIsNotForwardedToARecipientWithoutMessageTagsNegotiated() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");

            // bob negotiates nothing — a `+`-prefixed client tag must never reach a recipient with
            // no way to parse a tag section at all (oragono/Ergo issue 754 regression).
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("@+example-tag=value PRIVMSG bob :hi");
            String delivered = bob.readUntil("PRIVMSG bob", Duration.ofSeconds(5));
            assertThat(delivered).doesNotStartWith("@").doesNotContain("+example-tag");
        }
    }

    @Test
    void nonClientOnlyTagIsNeverRelayedEvenOnTheSendersOwnEcho() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags echo-message");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");

            bob.send("CAP REQ :message-tags");
            bob.readUntil("CAP * ACK", Duration.ofSeconds(5));
            bob.send("CAP END");
            bob.registerAndAwaitWelcome("bob", "bob");

            // `vendor` has no `+` prefix — only the message-tags spec's own reserved namespace, not
            // a client-settable tag — so it must be dropped on relay, and even on the sender's own
            // echo.
            alice.send("@+baz=bat;vendor=oops PRIVMSG bob :hi");
            String echo = alice.readUntil("PRIVMSG bob", Duration.ofSeconds(5));
            assertThat(echo).contains("+baz=bat").doesNotContain("vendor=");
            String delivered = bob.readUntil("PRIVMSG bob", Duration.ofSeconds(5));
            assertThat(delivered).contains("+baz=bat").doesNotContain("vendor=");
        }
    }
}
