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

class Story6SamodeTest {

    @Test
    void samodeGrantsAndRevokesSelfOperatorStatusBypassingTheOperatorPrecondition()
            throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            root.registerAndAwaitWelcome("root", "root");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            root.send("JOIN #lobby");
            root.readUntil("353", Duration.ofSeconds(5));

            // root isn't a channel operator yet — an ordinary MODE +o attempt is rejected.
            root.send("MODE #lobby +o root");
            assertThat(root.readUntil("482", Duration.ofSeconds(5))).contains("482");

            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            root.send("SAMODE #lobby +o");
            assertThat(root.readUntil("MODE #lobby +o root", Duration.ofSeconds(5)))
                    .contains("MODE #lobby +o root");

            root.send("SAMODE #lobby -o");
            assertThat(root.readUntil("MODE #lobby -o root", Duration.ofSeconds(5)))
                    .contains("MODE #lobby -o root");
        }
    }

    @Test
    void samodeRequiresChannelMembership() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            root.registerAndAwaitWelcome("root", "root");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            root.send("SAMODE #never-joined +o");
            assertThat(root.readUntil("442", Duration.ofSeconds(5))).contains("442");
        }
    }
}
