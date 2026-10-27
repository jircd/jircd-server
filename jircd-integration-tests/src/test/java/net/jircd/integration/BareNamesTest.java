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
 * 006-complete-core-protocol Story 3: a bare, argument-less {@code NAMES} lists every channel
 * visible to the requester, excluding a private/secret channel they aren't a member of, closed by
 * exactly one {@code RPL_ENDOFNAMES}.
 */
class BareNamesTest {

    @Test
    void bareNamesListsVisibleChannelsAndExcludesASecretOneTheRequesterIsntIn() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #pub");
            alice.readUntil("353", Duration.ofSeconds(5));

            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("JOIN #priv");
            bob.readUntil("353", Duration.ofSeconds(5));
            bob.send("MODE #priv +s");
            bob.readUntil("MODE #priv +s", Duration.ofSeconds(5));

            alice.send("NAMES");
            // Only #pub is visible to alice, so exactly one 353 line (for #pub, never #priv) is
            // expected before the single closing 366 targeted at "*".
            String namreply = alice.readUntil("353", Duration.ofSeconds(5));
            assertThat(namreply).contains("#pub").contains("alice").doesNotContain("#priv");

            String end = alice.readUntil("366", Duration.ofSeconds(5));
            assertThat(end).contains(" * ");
        }
    }

    @Test
    void bareNamesIncludesASecretChannelForOneOfItsOwnMembers() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("JOIN #priv");
            bob.readUntil("353", Duration.ofSeconds(5));
            bob.send("MODE #priv +s");
            bob.readUntil("MODE #priv +s", Duration.ofSeconds(5));

            // bob IS a member of #priv, so the same visibility rule that hides it from a non-member
            // (ChannelVisibility.isHiddenFrom) must still include it for bob himself.
            bob.send("NAMES");
            String namreply = bob.readUntil("353", Duration.ofSeconds(5));
            assertThat(namreply).contains("#priv").contains("bob");

            assertThat(bob.readUntil("366", Duration.ofSeconds(5))).contains(" * ");
        }
    }
}
