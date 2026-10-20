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
      String nak = alice.readUntil("CAP * NAK", TIMEOUT);
      assertThat(nak).contains("CAP * NAK :echo-message batch labeled-response");

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
      String ack = alice.readUntil("CAP * ACK", TIMEOUT);
      assertThat(ack).contains("CAP * ACK :message-tags echo-message");
    }
  }

  @Test
  void removalIsAppliedTogetherWithAdditionsAndAckedOnce() throws Exception {
    try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.send("CAP REQ :message-tags");
      alice.readUntil("CAP * ACK", TIMEOUT);

      alice.send("CAP REQ :echo-message -message-tags");
      String ack = alice.readUntil("CAP * ACK", TIMEOUT);
      assertThat(ack).contains("CAP * ACK :echo-message -message-tags");

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
      assertThat(alice.readUntil("CAP * NAK", TIMEOUT)).contains(":echo-message -nope");

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
      alice.readUntil("CAP alice ACK", TIMEOUT);

      alice.send("CAP REQ :bogus");
      alice.readUntil("CAP alice NAK", TIMEOUT);

      alice.send("CAP LIST");
      assertThat(alice.readUntil("CAP alice LIST", TIMEOUT)).contains("echo-message");

      alice.send("CAP LS");
      alice.readUntil("CAP alice LS", TIMEOUT);
    }
  }
}
