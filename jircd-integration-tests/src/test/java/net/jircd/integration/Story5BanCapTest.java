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

class Story5BanCapTest {

    @Test
    void banListIsCappedAt100Entries() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            // Paced below the server's per-connection rate limit (FR-016, 20-token bucket refilling
            // at 10/sec) — 100 back-to-back commands would otherwise get silently dropped past the
            // initial burst, timing out the readUntil waiting for an echo that never arrives.
            for (int i = 0; i < 100; i++) {
                alice.send("MODE #lobby +b mask" + i + "!*@*");
                alice.readUntil("MODE #lobby +b mask" + i, Duration.ofSeconds(5));
                Thread.sleep(110);
            }

            alice.send("MODE #lobby +b mask100!*@*");
            assertThat(alice.readUntil("478", Duration.ofSeconds(5))).contains("478");

            alice.send("MODE #lobby -b mask0!*@*");
            alice.readUntil("MODE #lobby -b mask0", Duration.ofSeconds(5));

            alice.send("MODE #lobby +b mask100!*@*");
            assertThat(alice.readUntil("MODE #lobby +b mask100", Duration.ofSeconds(5)))
                    .contains("+b mask100");
        }
    }
}
