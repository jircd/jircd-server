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

class Story7WhoMaskConfigTest {

    @Test
    void whoMaskEnabledFalseBlocksOnlyMaskAndNoArgumentFormsForNonAdministrators()
            throws Exception {
        try (TestServer server =
                        TestServer.start(
                                TestServer.adminEnabledYaml() + "whoMaskEnabled: false\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            root.registerAndAwaitWelcome("root", "root");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            // Mask and bare forms return zero matches for a non-administrator.
            alice.send("WHO bo*");
            List<String> maskLines = alice.readLinesFor(Duration.ofSeconds(2));
            assertThat(maskLines).noneMatch(line -> line.contains("352"));
            assertThat(maskLines).anyMatch(line -> line.contains("315"));

            alice.send("WHO");
            List<String> bareLines = alice.readLinesFor(Duration.ofSeconds(2));
            assertThat(bareLines).noneMatch(line -> line.contains("352"));
            assertThat(bareLines).anyMatch(line -> line.contains("315"));

            // Exact-nickname and channel forms are unaffected.
            alice.send("WHO bob");
            assertThat(alice.readUntil("352", Duration.ofSeconds(5))).contains("bob");

            alice.send("WHO #lobby");
            assertThat(alice.readUntil("352", Duration.ofSeconds(5))).contains("352");

            // An administrator's mask/bare WHO still returns real matches despite the setting.
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            root.send("WHO bo*");
            assertThat(root.readUntil("352", Duration.ofSeconds(5))).contains("bob");

            root.send("WHO");
            List<String> adminBareLines = root.readLinesFor(Duration.ofSeconds(2));
            assertThat(adminBareLines).anyMatch(l -> l.contains("352"));
            assertThat(adminBareLines).anyMatch(l -> l.contains("315"));
        }
    }
}
