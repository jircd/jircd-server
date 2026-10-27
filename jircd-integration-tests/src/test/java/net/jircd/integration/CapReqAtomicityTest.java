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
 * Issue #71: {@code CAP REQ} is ACKed or NAKed as a unit, supports {@code -cap} removal, and
 * replies target the client's nickname once it has one.
 */
class CapReqAtomicityTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void mixedRequestIsNakedWholeAndLeavesCapabilitiesUnchanged() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :echo-message batch labeled-response");
            var lines = alice.readThroughPong(TIMEOUT);
            assertThat(lines).filteredOn(l -> l.contains(" ACK")).isEmpty();
            assertThat(lines)
                    .filteredOn(l -> l.contains("CAP * NAK"))
                    .singleElement()
                    .asString()
                    .contains("CAP * NAK :echo-message batch labeled-response");

            alice.send("CAP LIST");
            String list = alice.readUntil("CAP * LIST", TIMEOUT);
            assertThat(list).doesNotContain("echo-message");
        }
    }

    @Test
    void validRequestIsAckedOnceWithTheFullRequestEchoed() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags echo-message");
            var lines = alice.readThroughPong(TIMEOUT);
            assertThat(lines).filteredOn(l -> l.contains(" NAK")).isEmpty();
            assertThat(lines)
                    .filteredOn(l -> l.contains("CAP * ACK"))
                    .singleElement()
                    .asString()
                    .contains("CAP * ACK :message-tags echo-message");
        }
    }

    @Test
    void removalIsAppliedTogetherWithAdditionsAndAckedOnce() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags");
            alice.readUntil("CAP * ACK", TIMEOUT);

            alice.send("CAP REQ :echo-message -message-tags");
            var lines = alice.readThroughPong(TIMEOUT);
            assertThat(lines).filteredOn(l -> l.contains(" NAK")).isEmpty();
            assertThat(lines)
                    .filteredOn(l -> l.contains("CAP * ACK"))
                    .singleElement()
                    .asString()
                    .contains("CAP * ACK :echo-message -message-tags");

            alice.send("CAP LIST");
            String list = alice.readUntil("CAP * LIST", TIMEOUT);
            assertThat(list).contains("echo-message").doesNotContain("message-tags");
        }
    }

    @Test
    void removingAnUnknownCapabilityNaksTheWholeRequest() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :echo-message -nope");
            var lines = alice.readThroughPong(TIMEOUT);
            assertThat(lines).filteredOn(l -> l.contains(" ACK")).isEmpty();
            assertThat(lines)
                    .filteredOn(l -> l.contains("CAP * NAK"))
                    .singleElement()
                    .asString()
                    .contains(":echo-message -nope");

            alice.send("CAP LIST");
            assertThat(alice.readUntil("CAP * LIST", TIMEOUT)).doesNotContain("echo-message");
        }
    }

    @Test
    void capRepliesTargetTheNicknameOnceRegistered() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("CAP REQ :echo-message");
            assertThat(alice.readThroughPong(TIMEOUT))
                    .filteredOn(l -> l.contains("CAP "))
                    .singleElement()
                    .asString()
                    .contains("CAP alice ACK");

            alice.send("CAP REQ :bogus");
            assertThat(alice.readThroughPong(TIMEOUT))
                    .filteredOn(l -> l.contains("CAP "))
                    .singleElement()
                    .asString()
                    .contains("CAP alice NAK");

            alice.send("CAP LIST");
            assertThat(alice.readUntil("CAP alice LIST", TIMEOUT)).contains("echo-message");

            alice.send("CAP LS");
            alice.readUntil("CAP alice LS", TIMEOUT);
        }
    }

    @Test
    void emptyRequestIsNakedWithAnEmptyListAndChangesNothing() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.send("CAP REQ :message-tags");
            alice.readUntil("CAP * ACK", TIMEOUT);

            alice.send("CAP REQ :");
            alice.send("CAP REQ");
            var lines = alice.readThroughPong(TIMEOUT);
            assertThat(lines).filteredOn(l -> l.contains(" ACK")).isEmpty();
            assertThat(lines).filteredOn(l -> l.contains("CAP * NAK")).hasSize(2);

            alice.send("CAP LIST");
            assertThat(alice.readUntil("CAP * LIST", TIMEOUT)).contains("message-tags");
        }
    }

    @Test
    void replyThatCannotFitIn512BytesGets417AndChangesNothing() throws Exception {
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            // 38 x "echo-message" fits inbound (504 bytes) but the echoed ACK, with its prefix,
            // does not.
            alice.send(
                    "CAP REQ :"
                            + String.join(" ", java.util.Collections.nCopies(38, "echo-message")));
            var lines = alice.readThroughPong(TIMEOUT);
            assertThat(lines).filteredOn(l -> l.contains(" CAP ")).isEmpty();
            assertThat(lines).filteredOn(l -> l.contains(" 417 ")).hasSize(1);

            alice.send("CAP LIST");
            assertThat(alice.readUntil("CAP * LIST", TIMEOUT)).doesNotContain("echo-message");
        }
    }
}
