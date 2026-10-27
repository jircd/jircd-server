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

class Story7WhoInvisibleTest {

    @Test
    void invisibleExcludesMaskAndBareWhoButNeverExactNicknameOrAdministrator() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            root.registerAndAwaitWelcome("root", "root");

            bob.send("MODE bob +i");
            bob.readUntil("MODE bob +i", Duration.ofSeconds(5));

            // No shared channel yet: an exact-nickname WHO still matches bob — only the mask and
            // no-argument forms respect invisibility (005-fix-batch-conformance FR-021).
            alice.send("WHO bob");
            assertThat(alice.readUntil("352", Duration.ofSeconds(5))).contains("bob");
            alice.readUntil("315", Duration.ofSeconds(5));

            alice.send("WHO bo*");
            List<String> maskLines = alice.readLinesFor(Duration.ofSeconds(2));
            assertThat(maskLines).noneMatch(line -> line.contains("352"));
            assertThat(maskLines).anyMatch(line -> line.contains("315"));

            // Bare WHO similarly excludes bob for a non-sharing, non-privileged requester.
            alice.send("WHO");
            String bareEndOfWho = alice.readUntil("315", Duration.ofSeconds(5));
            assertThat(bareEndOfWho).contains("315");

            // An administrator gets a match without sharing any channel.
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));
            root.send("WHO bob");
            assertThat(root.readUntil("352", Duration.ofSeconds(5))).contains("bob");

            // After sharing a channel, alice's exact and mask queries now match.
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            alice.send("WHO bob");
            assertThat(alice.readUntil("352", Duration.ofSeconds(5))).contains("bob");
            alice.send("WHO bo*");
            assertThat(alice.readUntil("352", Duration.ofSeconds(5))).contains("bob");
        }
    }
}
