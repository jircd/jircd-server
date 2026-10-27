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
 * T125's {@code WHOIS}-based assertion (confirming {@code RPL_WHOISOPERATOR} appears/disappears
 * across grant and self-revocation) is deferred to Story 7 (Phase 8), which is what actually
 * implements {@code WHOIS} — this covers everything else: {@code OPER} granting the {@code +o}
 * usermode, and {@code MODE <nick> -o} revoking administrator privilege in-band.
 */
class Story6OperUserModeTest {

    @Test
    void operSetsOperatorUsermodeAndSelfRevocationClearsIt() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            alice.readUntil("381", Duration.ofSeconds(5));

            alice.send("WHOHOST alice");
            assertThat(alice.readUntil("NOTICE", Duration.ofSeconds(5))).contains("alice");

            alice.send("MODE alice -o");
            alice.readUntil("MODE alice -o", Duration.ofSeconds(5));

            alice.send("WHOHOST alice");
            assertThat(alice.readUntil("481", Duration.ofSeconds(5))).contains("481");
        }
    }
}
