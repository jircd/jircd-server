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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.time.Duration;
import net.jircd.core.session.SecurityEventLog;
import org.junit.jupiter.api.Test;

class Story6OperTest {

    @Test
    void correctCredentialsGrantOperatorPrivilege() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            assertThat(alice.readUntil("381", Duration.ofSeconds(5))).contains("381");
        }
    }

    @Test
    void incorrectCredentialsAreRejectedWithoutGrantingPrivilege() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("OPER " + TestServer.ADMIN_USERNAME + " wrong-password");
            assertThat(alice.readUntil("464", Duration.ofSeconds(5))).contains("464");

            alice.send("WHOHOST alice");
            assertThat(alice.readUntil("481", Duration.ofSeconds(5))).contains("481");
        }
    }

    @Test
    void thirdConsecutiveFailedAttemptDisconnects() throws Exception {
        try (TestServer server =
                        TestServer.start(
                                TestServer.adminEnabledYaml() + "operFailureThreshold: 3\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            for (int attempt = 1; attempt <= 2; attempt++) {
                alice.send("OPER " + TestServer.ADMIN_USERNAME + " wrong-password");
                assertThat(alice.readUntil("464", Duration.ofSeconds(5))).contains("464");
            }

            alice.send("OPER " + TestServer.ADMIN_USERNAME + " wrong-password");
            assertThatThrownBy(() -> alice.readUntil("_never_matches_", Duration.ofSeconds(5)))
                    .hasMessageContaining("Connection closed");
        }
    }

    @Test
    void failuresAndLockoutSurviveReconnectEvenWithCorrectCredentials() throws Exception {
        try (TestServer server =
                TestServer.start(TestServer.adminEnabledYaml() + "operFailureThreshold: 2\n")) {
            try (RawIrcClient first = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                first.registerAndAwaitWelcome("first", "first");
                first.send("OPER root wrong-password");
                assertThat(first.readUntil("464", Duration.ofSeconds(5))).contains("464");
            }
            try (RawIrcClient second = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                second.registerAndAwaitWelcome("second", "second");
                second.send("OPER another-user wrong-password");
                assertThatThrownBy(() -> second.readUntil("_never_matches_", Duration.ofSeconds(5)))
                        .hasMessageContaining("Connection closed");
            }
            try (RawIrcClient third = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                third.registerAndAwaitWelcome("third", "third");
                third.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
                assertThatThrownBy(() -> third.readUntil("381", Duration.ofSeconds(5)))
                        .hasMessageContaining("Connection closed");
            }
        }
    }

    @Test
    void tlsRequirementRejectsPlaintextAndAcceptsTls() throws Exception {
        try (TestServer server =
                        TestServer.start(TestServer.adminEnabledYaml() + "operRequireTls: true\n");
                RawIrcClient plain = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient tls = RawIrcClient.connectTls(server.tlsPort())) {
            plain.registerAndAwaitWelcome("plain", "plain");
            plain.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            assertThat(plain.readUntil("491", Duration.ofSeconds(5))).contains("OPER requires TLS");
            tls.registerAndAwaitWelcome("secure", "secure");
            tls.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            assertThat(tls.readUntil("381", Duration.ofSeconds(5))).contains("381");
        }
    }

    @Test
    void successfulOperClearsFailuresAcrossConnections() throws Exception {
        try (TestServer server =
                TestServer.start(TestServer.adminEnabledYaml() + "operFailureThreshold: 2\n")) {
            try (RawIrcClient first = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                first.registerAndAwaitWelcome("first", "first");
                first.send("OPER root wrong-password");
                first.readUntil("464", Duration.ofSeconds(5));
                first.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
                first.readUntil("381", Duration.ofSeconds(5));
            }
            try (RawIrcClient second = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                second.registerAndAwaitWelcome("second", "second");
                second.send("OPER root wrong-password");
                second.readUntil("464", Duration.ofSeconds(5));
                second.send("PING still-connected");
                assertThat(second.readUntil("PONG", Duration.ofSeconds(5)))
                        .contains("still-connected");
            }
        }
    }

    @Test
    void rehashAppliesTlsRequirementToExistingConnections() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient probe = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            root.registerAndAwaitWelcome("root", "root");
            probe.registerAndAwaitWelcome("probe", "probe");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));
            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + TestServer.adminEnabledYaml()
                            + "operRequireTls: true\noperLockoutSeconds: 60\noperFailureThreshold: 2\n");
            root.send("REHASH");
            root.readUntil("382", Duration.ofSeconds(5));
            probe.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            assertThat(probe.readUntil("491", Duration.ofSeconds(5))).contains("OPER requires TLS");
        }
    }

    @Test
    void skippedVerificationLogsLockoutRatherThanInvalidCredentials() throws Exception {
        try (TestServer server =
                        TestServer.start(
                                TestServer.adminEnabledYaml() + "operFailureThreshold: 1\n");
                LogCapture logs = new LogCapture(SecurityEventLog.class)) {
            try (RawIrcClient first = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                first.registerAndAwaitWelcome("first", "first");
                first.send("OPER root wrong");
                assertThatThrownBy(() -> first.readUntil("_never_matches_", Duration.ofSeconds(5)))
                        .hasMessageContaining("Connection closed");
            }
            try (RawIrcClient second = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                second.registerAndAwaitWelcome("second", "second");
                second.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
                assertThatThrownBy(() -> second.readUntil("381", Duration.ofSeconds(5)))
                        .hasMessageContaining("Connection closed");
            }
            assertThat(
                            logs.messages().stream()
                                    .filter(
                                            message ->
                                                    message.contains(
                                                            "security-event=failed-authentication"))
                                    .map(message -> message.substring(message.indexOf("reason="))))
                    .containsExactly(
                            "reason=invalid OPER credentials", "reason=OPER source locked out");
        }
    }
}
