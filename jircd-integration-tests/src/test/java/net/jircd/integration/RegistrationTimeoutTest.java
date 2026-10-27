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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A connection that does not finish registering within registrationTimeoutSeconds is dropped. */
@Timeout(30)
class RegistrationTimeoutTest {
    private static final Duration STEP = Duration.ofSeconds(10);
    private static final String CONFIG = "registrationTimeoutSeconds: 1\n";

    @ParameterizedTest
    @ValueSource(strings = {"", "CAP LS 302", "NICK lonely", "CAP LS 302\r\nNICK lonely"})
    void incompleteRegistrationGetsErrorThenClose(String lines) throws Exception {
        try (TestServer server = TestServer.start(CONFIG);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            for (String line : lines.split("\r\n")) {
                if (!line.isEmpty()) client.send(line);
            }

            // Reads block until the server speaks or closes; a hang is bounded by @Timeout.
            List<String> received = new java.util.ArrayList<>();
            String line;
            while ((line = client.readLine()) != null) received.add(line);

            assertThat(received).contains("ERROR :Registration timeout");
            assertThat(received.getLast()).isEqualTo("ERROR :Registration timeout");
        }
    }

    @Test
    void answeringPingsDoesNotKeepAnUnregisteredClientAlive() throws Exception {
        try (TestServer server = TestServer.start(CONFIG);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.send("CAP LS 302");
            // Each PONG we read is traffic that would reset an idle timer; keep it flowing until
            // the server gives up on us.
            String line;
            int token = 0;
            do {
                client.send("PING keepalive" + token++);
                line = client.readLine();
            } while (line != null && !line.equals("ERROR :Registration timeout"));

            assertThat(line).isEqualTo("ERROR :Registration timeout");
            assertThat(client.readLine()).isNull();
        }
    }

    @Test
    void answeringTheServersOwnPingDoesNotKeepAnUnregisteredClientAlive() throws Exception {
        try (TestServer server =
                        TestServer.start(
                                "registrationTimeoutSeconds: 4\nkeepAliveFrequencySeconds: 1\n");
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.send("CAP LS 302");
            int pingsAnswered = 0;
            String line;
            while ((line = client.readLine()) != null && !line.startsWith("ERROR ")) {
                if (line.startsWith("PING ")) {
                    client.send("PONG " + line.substring("PING ".length()));
                    pingsAnswered++;
                }
            }

            // A second server PING is only sent once the first PONG cleared the outstanding ping.
            assertThat(pingsAnswered).isGreaterThanOrEqualTo(2);
            assertThat(line).isEqualTo("ERROR :Registration timeout");
            assertThat(client.readLine()).isNull();
        }
    }

    @Test
    void clientThatRegistersInTimeIsUnaffected() throws Exception {
        try (TestServer server = TestServer.start(CONFIG);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("prompt", "prompt");
            client.readThroughPong(STEP); // drain the rest of the welcome burst

            // Nothing, in particular no ERROR, arrives well past the 1s registration deadline.
            assertThat(client.staysSilentFor(Duration.ofMillis(2500))).isTrue();
            client.send("PING after-timeout");

            assertThat(client.readUntil("PONG", STEP)).endsWith("after-timeout");
        }
    }
}
