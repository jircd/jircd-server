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

class Story5ModeCapConfigTest {

    @Test
    void configuredMaxModesPerCommandLimitsParameterConsumingChanges() throws Exception {
        try (TestServer server = TestServer.start("maxModesPerCommand: 2\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            alice.send("MODE #lobby +bbb mask1!*@* mask2!*@* mask3!*@*");
            String echo = alice.readUntil("MODE #lobby +bb", Duration.ofSeconds(5));
            assertThat(echo).contains("mask1").contains("mask2").doesNotContain("mask3");

            alice.send("MODE #lobby b");
            List<String> lines = alice.readLinesFor(Duration.ofSeconds(2));
            assertThat(lines.stream().filter(l -> l.contains("367")).count()).isEqualTo(2);

            alice.send("MODE #lobby +bb mask4!*@* mask5!*@*");
            String withinLimit = alice.readUntil("MODE #lobby +bb", Duration.ofSeconds(5));
            assertThat(withinLimit).contains("mask4").contains("mask5");
        }
    }
}
