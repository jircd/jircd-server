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
 * 005-fix-batch-conformance Story 5: USERHOST, INFO, WHOIS's missing-nickname 318, and AWAY's
 * empty-argument clear. The exact-nickname WHO invisibility bypass (FR-021) is covered by the
 * existing Story7WhoInvisibleTest, updated as part of this same feature.
 */
class InformationQueryCompletenessTest {

    @Test
    void userhostReturnsHostInformation() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("USERHOST bob");
            String reply = alice.readUntil("302", Duration.ofSeconds(5));
            assertThat(reply).contains("bob=");
        }
    }

    @Test
    void infoRepliesInsteadOfUnknownCommand() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("INFO");
            assertThat(alice.readUntil("371", Duration.ofSeconds(5))).contains("371");
            assertThat(alice.readUntil("374", Duration.ofSeconds(5))).contains("374");
        }
    }

    @Test
    void whoisOnAMissingNicknameStillSendsEndOfWhois() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("WHOIS nonexistentnick");
            assertThat(alice.readUntil("401", Duration.ofSeconds(5))).contains("401");
            assertThat(alice.readUntil("318", Duration.ofSeconds(5))).contains("318");
        }
    }

    @Test
    void awayWithEmptyArgumentClearsAwayStatus() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("AWAY :gone for lunch");
            alice.readUntil("306", Duration.ofSeconds(5));

            alice.send("AWAY :");
            assertThat(alice.readUntil("305", Duration.ofSeconds(5))).contains("305");
        }
    }
}
