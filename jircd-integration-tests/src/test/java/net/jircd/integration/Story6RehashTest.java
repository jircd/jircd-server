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

import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class Story6RehashTest {

    @Test
    void rehashReloadsConfigurationAndReportsThePath() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            root.registerAndAwaitWelcome("root", "root");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            root.send("REHASH");
            assertThat(root.readUntil("382", Duration.ofSeconds(5)))
                    .contains(server.configPath.toString());
        }
    }

    @Test
    void rehashWithInvalidConfigurationReportsFailureAndLeavesPreviousConfigurationActive()
            throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            root.registerAndAwaitWelcome("root", "root");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + TestServer.adminEnabledYaml()
                            + "nicknameMaxLength: 0\n");

            root.send("REHASH");
            assertThat(root.readUntil("NOTICE", Duration.ofSeconds(5))).contains("Rehash failed");

            root.send("WHOHOST root");
            assertThat(root.readUntil("NOTICE", Duration.ofSeconds(5)))
                    .contains("root is connecting from");
        }
    }

    /**
     * A rehashed length limit must take live effect on enforcement (NICK/JOIN/TOPIC/MODE), not just
     * on the {@code 005 RPL_ISUPPORT} advertisement — the two are backed by separate mechanisms
     * (data-model.md), and a prior implementation gap left enforcement silently reading the
     * configuration snapshot taken at startup forever, never the reloaded value.
     */
    @Test
    void rehashedNicknameMaxLengthTakesLiveEffectOnEnforcement() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient probe = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            root.registerAndAwaitWelcome("root", "root");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + TestServer.adminEnabledYaml()
                            + "nicknameMaxLength: 4\n");
            root.send("REHASH");
            root.readUntil("382", Duration.ofSeconds(5));

            probe.send("NICK abcde");
            assertThat(probe.readUntil("432", Duration.ofSeconds(5))).contains("432");
        }
    }
}
