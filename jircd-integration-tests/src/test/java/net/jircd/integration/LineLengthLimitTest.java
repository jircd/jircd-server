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
 * FR-049: a protocol line exceeding 512 bytes (command+params, CR-LF inclusive) plus the 4096-byte
 * message-tags allowance is rejected with {@code 417 ERR_INPUTTOOLONG} — a specific, actionable
 * error distinct from FR-015's other malformed-message cases, not silently truncated or partially
 * processed. Distinct from {@code Story4LengthLimitConfigTest}, which covers FR-056's
 * administrator-configured *topic* length (reusing the same numeric for an unrelated requirement).
 */
class LineLengthLimitTest {

    @Test
    void lineExceedingTheProtocolLengthBudgetIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            // 512 (command+params, CR-LF inclusive) + 4096 (message-tags allowance) = 4608 total
            // budget.
            String oversizedBody = "x".repeat(5000);
            alice.send("PRIVMSG #lobby :" + oversizedBody);

            assertThat(alice.readUntil("417", Duration.ofSeconds(5))).contains("417");
        }
    }

    @Test
    void lineWithinTheProtocolLengthBudgetIsUnaffected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("JOIN #lobby");
            assertThat(alice.readUntil("353", Duration.ofSeconds(5))).contains("353");
        }
    }
}
