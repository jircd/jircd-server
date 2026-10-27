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

class Story2MessageIdTest {

    @Test
    void msgidIsSharedAcrossRecipientsAndUniquePerMessage() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            // alice: message-tags only, no server-time — msgid isn't gated behind server-time.
            alice.send("CAP REQ :message-tags echo-message");
            alice.readUntil("CAP * ACK", Duration.ofSeconds(5));
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "alice");

            bob.send("CAP REQ :message-tags");
            bob.readUntil("CAP * ACK", Duration.ofSeconds(5));
            bob.send("CAP END");
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            alice.send("PRIVMSG #lobby :message one");
            String aliceFirst =
                    alice.readUntil("PRIVMSG #lobby :message one", Duration.ofSeconds(5));
            String bobFirst = bob.readUntil("PRIVMSG #lobby :message one", Duration.ofSeconds(5));

            String aliceFirstMsgid = extractTag(aliceFirst, "msgid");
            String bobFirstMsgid = extractTag(bobFirst, "msgid");
            assertThat(aliceFirstMsgid).isNotBlank();
            assertThat(aliceFirstMsgid).isEqualTo(bobFirstMsgid);

            alice.send("PRIVMSG #lobby :message two");
            String aliceSecond =
                    alice.readUntil("PRIVMSG #lobby :message two", Duration.ofSeconds(5));
            String aliceSecondMsgid = extractTag(aliceSecond, "msgid");

            assertThat(aliceSecondMsgid).isNotBlank();
            assertThat(aliceSecondMsgid).isNotEqualTo(aliceFirstMsgid);
        }
    }

    private static String extractTag(String line, String key) {
        assertThat(line).startsWith("@");
        String tagSection = line.substring(1, line.indexOf(' '));
        for (String tag : tagSection.split(";")) {
            int eq = tag.indexOf('=');
            if (eq > 0 && tag.substring(0, eq).equals(key)) {
                return tag.substring(eq + 1);
            }
        }
        throw new AssertionError("Tag '" + key + "' not found in: " + line);
    }
}
