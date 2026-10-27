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
 * 006-complete-core-protocol Story 1: the {@code +l} (user-limit) and {@code +k} (channel-key)
 * channel modes, including a pending invitation exempting a join from both at once.
 */
class ChannelCapacityModesTest {

    @Test
    void userLimitRejectsOnceFullAndRecoversOnceRaised() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient user2 = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient user3 = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));

            chanop.send("MODE #chan +l 2");
            assertThat(chanop.readUntil("MODE #chan +l", Duration.ofSeconds(5))).contains("2");

            user2.registerAndAwaitWelcome("user2", "user2");
            user2.send("JOIN #chan");
            assertThat(user2.readUntil("353", Duration.ofSeconds(5))).contains("#chan");

            user3.registerAndAwaitWelcome("user3", "user3");
            user3.send("JOIN #chan");
            assertThat(user3.readUntil("471", Duration.ofSeconds(5))).contains("471");

            chanop.send("MODE #chan -l");
            assertThat(chanop.readUntil("MODE #chan -l", Duration.ofSeconds(5)))
                    .endsWith("MODE #chan -l");

            user3.send("JOIN #chan");
            assertThat(user3.readUntil("353", Duration.ofSeconds(5))).contains("#chan");
        }
    }

    @Test
    void channelKeyRejectsMissingOrIncorrectKeyAndAcceptsTheCorrectOne() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient user2 = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));

            chanop.send("MODE #chan +k secret");
            chanop.readUntil("MODE #chan +k", Duration.ofSeconds(5));

            user2.registerAndAwaitWelcome("user2", "user2");
            user2.send("JOIN #chan");
            assertThat(user2.readUntil("475", Duration.ofSeconds(5))).contains("475");

            user2.send("JOIN #chan wrongkey");
            assertThat(user2.readUntil("475", Duration.ofSeconds(5))).contains("475");

            user2.send("JOIN #chan secret");
            assertThat(user2.readUntil("353", Duration.ofSeconds(5))).contains("#chan");
        }
    }

    @Test
    void anEmptyOrSpaceContainingKeyIsSilentlyIgnoredNotApplied() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient user2 = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));

            // An empty or space-containing key can never be supplied back via JOIN's own space-
            // delimited grammar (RFC 2812's key grammar excludes both), so it must be silently
            // ignored rather than applied — otherwise the channel becomes permanently unjoinable
            // with a key nobody can ever type back.
            chanop.send("MODE #chan +k :");
            chanop.send("PING sentinel1");
            assertThat(chanop.readUntil("PONG", Duration.ofSeconds(5))).contains("sentinel1");

            chanop.send("MODE #chan +k :has space");
            chanop.send("PING sentinel2");
            assertThat(chanop.readUntil("PONG", Duration.ofSeconds(5))).contains("sentinel2");

            // Neither MODE took effect — the channel remains keyless.
            user2.registerAndAwaitWelcome("user2", "user2");
            user2.send("JOIN #chan");
            assertThat(user2.readUntil("353", Duration.ofSeconds(5))).contains("#chan");
        }
    }

    @Test
    void aPendingInvitationExemptsAJoinFromBothTheLimitAndTheKeyAtOnce() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient invitee = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));
            chanop.send("MODE #chan +l 1");
            chanop.readUntil("MODE #chan +l", Duration.ofSeconds(5));
            chanop.send("MODE #chan +k secret");
            chanop.readUntil("MODE #chan +k", Duration.ofSeconds(5));

            invitee.registerAndAwaitWelcome("invitee", "invitee");
            chanop.send("INVITE invitee #chan");
            chanop.readUntil("341", Duration.ofSeconds(5));
            invitee.readUntil("INVITE", Duration.ofSeconds(5));

            // Wrong key AND channel already at its limit — the invitation must exempt both at once.
            invitee.send("JOIN #chan wrongkey");
            assertThat(invitee.readUntil("353", Duration.ofSeconds(5))).contains("#chan");
        }
    }

    @Test
    void loweringTheLimitBelowCurrentMembershipDoesNotRemoveExistingMembers() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient chanop = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient user2 = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            chanop.registerAndAwaitWelcome("chanop", "chanop");
            chanop.send("JOIN #chan");
            chanop.readUntil("353", Duration.ofSeconds(5));
            user2.registerAndAwaitWelcome("user2", "user2");
            user2.send("JOIN #chan");
            user2.readUntil("353", Duration.ofSeconds(5));
            chanop.readUntil("JOIN #chan", Duration.ofSeconds(5));

            // Two members present; lower the limit to 1 — below current membership. The limit only
            // gates future JOIN attempts, so neither existing member is removed (spec.md Edge
            // Cases).
            chanop.send("MODE #chan +l 1");
            chanop.readUntil("MODE #chan +l", Duration.ofSeconds(5));

            // Both members are still present: a message from user2 still reaches chanop, and a
            // fresh NAMES query still lists both.
            user2.send("PRIVMSG #chan :still here");
            assertThat(chanop.readUntil("PRIVMSG #chan", Duration.ofSeconds(5)))
                    .contains("still here");

            chanop.send("NAMES #chan");
            String namreply = chanop.readUntil("353", Duration.ofSeconds(5));
            assertThat(namreply).contains("chanop").contains("user2");
        }
    }
}
