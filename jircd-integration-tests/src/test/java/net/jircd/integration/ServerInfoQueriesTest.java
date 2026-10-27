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

/** 002-extended-irc-commands User Story 1: VERSION/TIME/LUSERS server information queries. */
class ServerInfoQueriesTest {

    @Test
    void versionRepliesWithVersionThenAnIsupportBurstMatchingRegistration() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("NICK alice");
            alice.send("USER alice 0 * :Alice");
            String registrationIsupport = alice.readUntil("005", Duration.ofSeconds(5));

            alice.send("VERSION");
            assertThat(alice.readUntil("351", Duration.ofSeconds(5))).contains("351");
            String versionIsupport = alice.readUntil("005", Duration.ofSeconds(5));
            assertThat(versionIsupport).isEqualTo(registrationIsupport);
        }
    }

    @Test
    void timeRepliesWith391() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("TIME");
            assertThat(alice.readUntil("391", Duration.ofSeconds(5))).contains("391");
        }
    }

    @Test
    void lusersReportsConnectedClientAndActiveChannelCounts() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            alice.send("LUSERS");
            String lusersClient = alice.readUntil("251", Duration.ofSeconds(5));
            assertThat(lusersClient).contains("2");
            String lusersChannels = alice.readUntil("254", Duration.ofSeconds(5));
            assertThat(lusersChannels).contains("1");
        }
    }
}
