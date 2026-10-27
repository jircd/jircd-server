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
 * FR-039: a client-initiated {@code PING} is answered with an immediate {@code PONG} on any
 * connection, at any time — including before registration completes — independently of the server's
 * own keep-alive probing of that connection. Since 005-fix-batch-conformance FR-005, {@code PONG}
 * carries the server's own name as well as the echoed token (two params, in that order); a bare
 * {@code PING} with no token at all is rejected (FR-006), never given a fabricated one.
 */
class PingPongTest {

    @Test
    void pingBeforeRegistrationReceivesImmediatePong() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("PING sometoken");
            String pong = alice.readUntil("PONG", Duration.ofSeconds(5));
            assertThat(pong).contains("PONG").contains("test.jircd.local").contains("sometoken");
        }
    }

    @Test
    void pingAfterRegistrationReceivesImmediatePong() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("PING anothertoken");
            String pong = alice.readUntil("PONG", Duration.ofSeconds(5));
            assertThat(pong).contains("PONG").contains("test.jircd.local").contains("anothertoken");
        }
    }

    @Test
    void pingWithNoTokenIsRejectedNotFabricated() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("PING");
            assertThat(alice.readUntil("409", Duration.ofSeconds(5))).contains("409");
        }
    }
}
