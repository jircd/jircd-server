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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * FR-054: a message containing an invalid UTF-8 byte sequence in any of `PRIVMSG`/`NOTICE` bodies,
 * channel topics, realnames, or `QUIT`/`PART` reasons is rejected as malformed (FR-015). Since
 * 005-fix-batch-conformance FR-012, this now closes the connection with an `ERROR` line — the same
 * definitive, client-visible resolution every other fatal/malformed pre-registration or mid-session
 * condition in this codebase already gives, rather than a `421`-and-continue that leaves the client
 * to guess whether to retry.
 */
class Utf8ValidationTest {

    /** A single {@code 0xFF} byte is never valid standalone UTF-8, regardless of context. */
    private static byte[] rawLine(String asciiPrefix, String asciiSuffix) {
        byte[] prefixBytes = asciiPrefix.getBytes(StandardCharsets.UTF_8);
        byte[] suffixBytes = asciiSuffix.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[prefixBytes.length + 1 + suffixBytes.length];
        System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
        result[prefixBytes.length] = (byte) 0xFF;
        System.arraycopy(suffixBytes, 0, result, prefixBytes.length + 1, suffixBytes.length);
        return result;
    }

    private static void assertClosesWithError(RawIrcClient client, byte[] malformedLine)
            throws Exception {
        client.sendRawBytes(malformedLine);
        assertThat(client.readUntil("ERROR", Duration.ofSeconds(5))).contains("ERROR");
        assertThat(client.readLine()).isNull();
    }

    @Test
    void invalidUtf8InPrivmsgBodyIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            assertClosesWithError(alice, rawLine("PRIVMSG #lobby :bad", "text"));
        }
    }

    @Test
    void invalidUtf8InNoticeBodyIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            assertClosesWithError(alice, rawLine("NOTICE #lobby :bad", "text"));
        }
    }

    @Test
    void invalidUtf8InTopicIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            assertClosesWithError(alice, rawLine("TOPIC #lobby :bad", "topic"));
        }
    }

    @Test
    void invalidUtf8InUserRealnameIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("NICK alice");
            assertClosesWithError(alice, rawLine("USER alice 0 * :bad", "name"));
        }
    }

    @Test
    void invalidUtf8InQuitReasonIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            assertClosesWithError(alice, rawLine("QUIT :bad", "reason"));
        }
    }

    @Test
    void invalidUtf8InPartReasonIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            assertClosesWithError(alice, rawLine("PART #lobby :bad", "reason"));
        }
    }
}
