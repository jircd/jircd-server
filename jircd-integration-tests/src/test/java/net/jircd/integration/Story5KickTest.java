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

class Story5KickTest {

    @Test
    void operatorKicksMemberAndBothTargetAndRemainingAreNotified() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // first joiner -> operator
            bob.registerAndAwaitWelcome("bob", "bob");
            carol.registerAndAwaitWelcome("carol", "carol");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));
            carol.send("JOIN #lobby");
            carol.readUntil("353", Duration.ofSeconds(5));
            alice.readUntil("JOIN #lobby", Duration.ofSeconds(5)); // carol's join notice

            alice.send("KICK #lobby bob :be gone");

            String bobKicked = bob.readUntil("KICK #lobby bob", Duration.ofSeconds(5));
            assertThat(bobKicked).startsWith(":alice!alice@");
            assertThat(bobKicked).contains("be gone");

            String carolSawIt = carol.readUntil("KICK #lobby bob", Duration.ofSeconds(5));
            assertThat(carolSawIt).contains("be gone");

            carol.send("NAMES #lobby");
            String names = carol.readUntil("353", Duration.ofSeconds(5));
            assertThat(names).doesNotContain("bob");
            assertThat(names).contains("carol");
        }
    }
}
