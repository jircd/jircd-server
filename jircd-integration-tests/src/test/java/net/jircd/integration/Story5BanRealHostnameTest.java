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
 * T107: with {@code cloak} enabled (presenting a member's channel-visible hostmask as something
 * other than their real value), a ban mask matching that member's *real*, uncloaked hostname/IP
 * still mutes them — a ban is not evadable simply because a cloaking extension changes what other
 * clients see (FR-062's dual real-vs-presented matching, US5 Acceptance Scenario 8).
 */
class Story5BanRealHostnameTest {

    @Test
    void banMaskMatchingRealHostnameMutesDespiteCloakedPresentedHostname() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminAndCloakEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator, creates the channel
            bob.registerAndAwaitWelcome("bob", "bob");
            root.registerAndAwaitWelcome("root", "root");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            String bobJoinEcho = bob.readUntil("353", Duration.ofSeconds(5));

            root.send("WHOHOST bob");
            String whohostNotice = root.readUntil("NOTICE", Duration.ofSeconds(5));
            String realHost =
                    whohostNotice.substring(whohostNotice.indexOf("connecting from ") + 16).trim();

            // bob's own presented hostmask (what alice actually sees in his JOIN) is cloaked —
            // never the literal real value — confirming this test's ban mask cannot be matching by
            // accident.
            assertThat(bobJoinEcho).doesNotContain(realHost);

            alice.send("MODE #lobby +b *!*@" + realHost);
            alice.readUntil("MODE #lobby +b", Duration.ofSeconds(5));

            bob.send("PRIVMSG #lobby :can you hear me");
            assertThat(bob.readUntil("404", Duration.ofSeconds(5))).contains("404");
        }
    }
}
