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
 * 006-complete-core-protocol Story 4: {@code LUSERS} reports connected-operator count ({@code 252
 * RPL_LUSEROP}) and always closes with {@code 255 RPL_LUSERME}.
 */
class LusersCompletenessTest {

    @Test
    void lusersReportsZeroOperatorsAndClosesWithLuserme() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("LUSERS");
            assertThat(alice.readUntil("252", Duration.ofSeconds(5))).contains("0");
            assertThat(alice.readUntil("255", Duration.ofSeconds(5))).contains("255");
        }
    }

    @Test
    void lusersReportsOneOperatorOnceOneHasAuthenticated() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            admin.registerAndAwaitWelcome("admin", "admin");
            admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            admin.readUntil("381", Duration.ofSeconds(5));
            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("LUSERS");
            assertThat(alice.readUntil("252", Duration.ofSeconds(5))).contains("1");
            assertThat(alice.readUntil("255", Duration.ofSeconds(5))).contains("255");
        }
    }
}
