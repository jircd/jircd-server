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
 * 006-complete-core-protocol Story 6: {@code WHOWAS}'s optional count parameter — a positive count
 * returns up to that many entries; omitting it, or giving zero/negative, both mean "do a full
 * search" and return every retained entry (RFC1459 §4.5.3/RFC2812 §3.6.3's own rule, confirmed
 * against irctest's own non-deprecated {@code testWhowasMultiple}, which sends no count at all and
 * still expects every retained entry back).
 */
class WhowasCountTest {

    private static void disconnectUnderNickname(TestServer server, String nickname)
            throws Exception {
        try (RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.send("NICK " + nickname);
            client.send("USER " + nickname + " 0 * :" + nickname);
            client.readUntil(" 001 ", Duration.ofSeconds(5));
            client.send("QUIT :bye");
        }
        Thread.sleep(200); // let server-side disconnect cleanup (and the WHOWAS recording) complete
    }

    @Test
    void countTwoReturnsTheTwoMostRecentEntries() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            disconnectUnderNickname(server, "erin");
            disconnectUnderNickname(server, "erin");
            disconnectUnderNickname(server, "erin");

            alice.send("WHOWAS erin 2");
            // readUntil first to skip past any still-unread registration-burst lines; readLine
            // thereafter for a precise count (no silent skipping) confirming exactly two 314 lines.
            assertThat(alice.readUntil("314", Duration.ofSeconds(5))).contains("314");
            assertThat(alice.readLine()).contains("314");
            assertThat(alice.readLine()).contains("369");
        }
    }

    @Test
    void countZeroOrNegativeReturnsEveryRetainedEntry() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            disconnectUnderNickname(server, "erin");
            disconnectUnderNickname(server, "erin");
            disconnectUnderNickname(server, "erin");

            alice.send("WHOWAS erin 0");
            alice.readUntil("314", Duration.ofSeconds(5));
            alice.readUntil("314", Duration.ofSeconds(5));
            alice.readUntil("314", Duration.ofSeconds(5));
            assertThat(alice.readUntil("369", Duration.ofSeconds(5))).contains("369");

            alice.send("WHOWAS erin -1");
            alice.readUntil("314", Duration.ofSeconds(5));
            alice.readUntil("314", Duration.ofSeconds(5));
            alice.readUntil("314", Duration.ofSeconds(5));
            assertThat(alice.readUntil("369", Duration.ofSeconds(5))).contains("369");
        }
    }

    @Test
    void noCountAlsoReturnsEveryRetainedEntryNotJustOne() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");

            disconnectUnderNickname(server, "erin");
            disconnectUnderNickname(server, "erin");

            alice.send("WHOWAS erin");
            // readUntil first to skip past any still-unread registration-burst lines; readLine
            // thereafter for a precise check (no silent skipping) confirming exactly two 314 lines
            // are sent, not just one — omitting the count is not the same as requesting a count of
            // one.
            assertThat(alice.readUntil("314", Duration.ofSeconds(5))).contains("314");
            assertThat(alice.readLine()).contains("314");
            assertThat(alice.readLine()).contains("369");
        }
    }
}
