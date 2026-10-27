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

class Story6SajoinTest {

    @Test
    void sajoinBypassesTheJoinGateAndInvalidGrammarIsStillRejected() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            root.registerAndAwaitWelcome("root", "root");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            alice.send("MODE #lobby +b root!*@*");
            alice.readUntil("MODE #lobby +b root", Duration.ofSeconds(5));

            root.send("JOIN #lobby");
            assertThat(root.readUntil("474", Duration.ofSeconds(5))).contains("474");

            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            root.send("SAJOIN #lobby");
            assertThat(root.readUntil("353", Duration.ofSeconds(5))).contains("353");

            root.send("SAJOIN not-a-valid-channel");
            assertThat(root.readUntil("476", Duration.ofSeconds(5))).contains("476");
        }
    }
}
