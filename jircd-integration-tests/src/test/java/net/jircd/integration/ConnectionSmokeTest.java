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
 * Two raw-socket clients connect to both the plaintext and TLS listeners as a connectivity smoke
 * test.
 */
class ConnectionSmokeTest {

    @Test
    void connectsOverPlaintextAndTls() throws Exception {
        try (TestServer server = TestServer.start()) {
            try (RawIrcClient plaintext = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                plaintext.send("NICK plainuser");
                plaintext.send("USER plainuser 0 * :Plain User");
                String welcome = plaintext.readUntil(" 001 ", Duration.ofSeconds(5));
                assertThat(welcome).contains("001");
            }

            try (RawIrcClient tls = RawIrcClient.connectTls(server.tlsPort())) {
                tls.send("NICK tlsuser");
                tls.send("USER tlsuser 0 * :TLS User");
                String welcome = tls.readUntil(" 001 ", Duration.ofSeconds(5));
                assertThat(welcome).contains("001");
            }
        }
    }
}
