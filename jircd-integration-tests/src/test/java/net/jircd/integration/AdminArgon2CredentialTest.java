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

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 008-argon2-admin-verification: an administrator credential stored as an Argon2id hash
 * authenticates via {@code OPER} exactly like a bcrypt-hashed one does (Story6OperTest).
 */
class AdminArgon2CredentialTest {

    @Test
    void correctCredentialsGrantOperatorPrivilege() throws Exception {
        try (TestServer server = TestServer.start(TestServer.argon2AdminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            assertThat(alice.readUntil("381", Duration.ofSeconds(5))).contains("381");
        }
    }

    @Test
    void incorrectCredentialsAreRejectedWithoutGrantingPrivilege() throws Exception {
        try (TestServer server = TestServer.start(TestServer.argon2AdminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("OPER " + TestServer.ADMIN_USERNAME + " wrong-password");
            assertThat(alice.readUntil("464", Duration.ofSeconds(5))).contains("464");
        }
    }

    @Test
    void thirdConsecutiveFailedAttemptDisconnects() throws Exception {
        try (TestServer server =
                        TestServer.start(
                                TestServer.argon2AdminEnabledYaml() + "operFailureThreshold: 3\n");
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
    void corruptedArgon2HashFailsCleanlyRatherThanCrashing() throws Exception {
        // Passes ConfigurationLoader's "$argon2id$" prefix check but the hash segment is mangled —
        // exercises AdminCredentialVerifier's catch-and-fail-closed branch (FR-003).
        String corruptedHash = "$argon2id$v=19$m=65536,t=3,p=4$not-a-real-salt$not-a-real-hash!!!";
        String yaml =
                "server-extensions:\n"
                        + "  admin: enabled\n"
                        + "administratorCredentials:\n"
                        + "  - username: "
                        + TestServer.ADMIN_USERNAME
                        + "\n"
                        + "    hashedPassword: \""
                        + corruptedHash
                        + "\"\n";
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            assertThat(alice.readUntil("464", Duration.ofSeconds(5))).contains("464");
        }
    }
}
