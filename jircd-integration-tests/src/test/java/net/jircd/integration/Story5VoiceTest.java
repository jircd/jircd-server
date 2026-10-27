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

class Story5VoiceTest {

    @Test
    void voiceGrantAllowsSendingInModeratedChannelButIsNotRetainedAcrossRejoin() throws Exception {
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

            // non-operator can't grant voice
            bob.send("MODE #lobby +v bob");
            assertThat(bob.readUntil("482", Duration.ofSeconds(5))).contains("482");

            // +v naming a nickname that isn't connected anywhere is rejected
            // (005-fix-batch-conformance
            // FR-017 — 401, distinct from 441's "connected but not a member of this channel")
            alice.send("MODE #lobby +v nobody");
            assertThat(alice.readUntil("401", Duration.ofSeconds(5))).contains("401");

            alice.send("MODE #lobby +m");
            alice.readUntil("MODE #lobby +m", Duration.ofSeconds(5));

            alice.send("MODE #lobby +v bob");
            String voiceEcho = bob.readUntil("MODE #lobby +v bob", Duration.ofSeconds(5));
            assertThat(voiceEcho).contains("+v bob");

            bob.send("PRIVMSG #lobby :now I can talk");
            String delivered = alice.readUntil("PRIVMSG #lobby", Duration.ofSeconds(5));
            assertThat(delivered).contains("now I can talk");

            bob.send("PART #lobby");
            bob.readUntil("PART #lobby", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));
            alice.readUntil("JOIN #lobby", Duration.ofSeconds(5));

            bob.send("PRIVMSG #lobby :still moderated, no voice now");
            assertThat(bob.readUntil("404", Duration.ofSeconds(5))).contains("404");
        }
    }
}
