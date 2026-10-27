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

class Story5BanMuteTest {

    @Test
    void banMasksMuteAMemberWithoutRemovingThem() throws Exception {
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

            // non-operator can't ban
            bob.send("MODE #lobby +b bob");
            assertThat(bob.readUntil("482", Duration.ofSeconds(5))).contains("482");

            // partial mask (bare nickname) is normalized and still mutes bob
            alice.send("MODE #lobby +b bob");
            alice.readUntil("MODE #lobby +b bob", Duration.ofSeconds(5));

            bob.send("PRIVMSG #lobby :can you hear me");
            assertThat(bob.readUntil("404", Duration.ofSeconds(5))).contains("404");

            alice.send("NAMES #lobby");
            String names = alice.readUntil("353", Duration.ofSeconds(5));
            assertThat(names).contains("bob");
        }
    }
}
