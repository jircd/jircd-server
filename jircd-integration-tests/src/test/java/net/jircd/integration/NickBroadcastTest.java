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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 005-fix-batch-conformance Story 1: a nickname change broadcasts to the changer and channel
 * co-members.
 */
class NickBroadcastTest {

  @Test
  void clientWithNoChannelMembershipsReceivesItsOwnConfirmation() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");

      alice.send("NICK alicia");
      assertThat(alice.readUntil("NICK alicia", Duration.ofSeconds(5))).contains("alice!");
    }
  }

  @Test
  void channelCoMemberReceivesTheNotification() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");
      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #lobby");
      bob.readUntil("353", Duration.ofSeconds(5));
      alice.readUntil("JOIN #lobby", Duration.ofSeconds(5));

      alice.send("NICK alicia");
      assertThat(alice.readUntil("NICK alicia", Duration.ofSeconds(5))).contains("alice!");
      assertThat(bob.readUntil("NICK alicia", Duration.ofSeconds(5))).contains("alice!");
    }
  }

  @Test
  void reclaimingTheExactCurrentNicknameBroadcastsNothing() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");

      alice.send("NICK alice");
      alice.send("PING sentinel");
      assertThat(alice.readUntil("PONG", Duration.ofSeconds(5))).contains("sentinel");
    }
  }

  @Test
  void peersSharingSeveralChannelsReceiveExactlyOneNotificationEach() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");
      for (String channel : List.of("#one", "#two")) {
        alice.send("JOIN " + channel);
        alice.readUntil("353", Duration.ofSeconds(5));
        bob.send("JOIN " + channel);
        bob.readUntil("353", Duration.ofSeconds(5));
        alice.readUntil("JOIN " + channel, Duration.ofSeconds(5));
      }

      alice.send("NICK alicia");

      assertThat(nickNotifications(alice.readLinesFor(Duration.ofSeconds(1)))).isEqualTo(1);
      assertThat(nickNotifications(bob.readLinesFor(Duration.ofSeconds(1)))).isEqualTo(1);
    }
  }

  @Test
  void changerInSeveralChannelsWithNoPeersReceivesExactlyOneNotification() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      for (String channel : List.of("#one", "#two")) {
        alice.send("JOIN " + channel);
        alice.readUntil("353", Duration.ofSeconds(5));
      }

      alice.send("NICK alicia");

      assertThat(nickNotifications(alice.readLinesFor(Duration.ofSeconds(1)))).isEqualTo(1);
    }
  }

  private static long nickNotifications(List<String> lines) {
    return lines.stream().filter(line -> line.contains(" NICK alicia")).count();
  }
}
