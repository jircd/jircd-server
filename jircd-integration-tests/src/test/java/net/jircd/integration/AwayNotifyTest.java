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

/** 011-away-notify-batch User Story 1: `away-notify` capability. */
class AwayNotifyTest {

  private static final String AWAY_NOTIFY_ENABLED_YAML =
      """
      capabilities:
        away-notify: enabled
      """;

  @Test
  void optedInChannelMateSeesAwayThenBackNotices() throws Exception {
    try (TestServer server = TestServer.start(AWAY_NOTIFY_ENABLED_YAML);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.send("CAP REQ :away-notify");
      bob.readUntil("CAP * ACK", Duration.ofSeconds(5));
      bob.send("CAP END");
      bob.registerAndAwaitWelcome("bob", "bob");

      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #lobby");
      bob.readUntil("353", Duration.ofSeconds(5));

      alice.send("AWAY :gone for lunch");
      alice.readUntil("306", Duration.ofSeconds(5));
      String notice = bob.readUntil("AWAY", Duration.ofSeconds(5));
      assertThat(notice).contains("alice").contains("AWAY").contains("gone for lunch");

      alice.send("AWAY");
      alice.readUntil("305", Duration.ofSeconds(5));
      String backNotice = bob.readUntil("AWAY", Duration.ofSeconds(5));
      assertThat(backNotice).contains("alice").contains("AWAY");
      assertThat(backNotice.substring(backNotice.indexOf("AWAY"))).doesNotContain(":");
    }
  }

  @Test
  void nonOptedInClientReceivesNoNotice() throws Exception {
    try (TestServer server = TestServer.start(AWAY_NOTIFY_ENABLED_YAML);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");

      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #lobby");
      bob.readUntil("353", Duration.ofSeconds(5));

      alice.send("AWAY :should not be seen");
      alice.readUntil("306", Duration.ofSeconds(5));

      // Something bob CAN observe (a normal channel message) proves the connection is alive and
      // any AWAY notice, had one been sent, would already have arrived ahead of this.
      alice.send("PRIVMSG #lobby :ping");
      String pingEcho = bob.readUntil("ping", Duration.ofSeconds(5));
      assertThat(pingEcho).doesNotContain("AWAY");
    }
  }

  @Test
  void oneNoticePerRecipientRegardlessOfSharedChannelCount() throws Exception {
    try (TestServer server = TestServer.start(AWAY_NOTIFY_ENABLED_YAML);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.send("CAP REQ :away-notify");
      bob.readUntil("CAP * ACK", Duration.ofSeconds(5));
      bob.send("CAP END");
      bob.registerAndAwaitWelcome("bob", "bob");

      alice.send("JOIN #one");
      alice.readUntil("353", Duration.ofSeconds(5));
      alice.send("JOIN #two");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #one");
      bob.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #two");
      bob.readUntil("353", Duration.ofSeconds(5));

      alice.send("AWAY :testing dedup");
      alice.readUntil("306", Duration.ofSeconds(5));
      String firstNotice = bob.readUntil("AWAY", Duration.ofSeconds(5));
      assertThat(firstNotice).contains("testing dedup");

      // If a second, unwanted notice were also delivered, it would already be sitting in bob's
      // read buffer ahead of this next, unrelated line — prove it isn't there.
      alice.send("PRIVMSG #one :after");
      String next = bob.readUntil("after", Duration.ofSeconds(5));
      assertThat(next).doesNotContain("AWAY");
    }
  }

  @Test
  void noSharedChannelMeansNoNotice() throws Exception {
    try (TestServer server = TestServer.start(AWAY_NOTIFY_ENABLED_YAML);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.send("CAP REQ :away-notify");
      bob.readUntil("CAP * ACK", Duration.ofSeconds(5));
      bob.send("CAP END");
      bob.registerAndAwaitWelcome("bob", "bob");

      alice.send("JOIN #alpha");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #beta");
      bob.readUntil("353", Duration.ofSeconds(5));

      alice.send("AWAY :testing scope");
      alice.readUntil("306", Duration.ofSeconds(5));

      // Prove bob's connection is alive and nothing AWAY-related arrived, via a round-trip that
      // doesn't depend on shared channel membership (unlike a channel PRIVMSG, which never
      // echoes back to a lone member without echo-message negotiated).
      bob.send("WHOIS bob");
      String endOfWhois = bob.readUntil("318", Duration.ofSeconds(5));
      assertThat(endOfWhois).doesNotContain("AWAY");
    }
  }

  @Test
  void reasonOnlyChangeWhileAlreadyAwayStillNotifies() throws Exception {
    try (TestServer server = TestServer.start(AWAY_NOTIFY_ENABLED_YAML);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.send("CAP REQ :away-notify");
      bob.readUntil("CAP * ACK", Duration.ofSeconds(5));
      bob.send("CAP END");
      bob.registerAndAwaitWelcome("bob", "bob");

      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #lobby");
      bob.readUntil("353", Duration.ofSeconds(5));

      alice.send("AWAY :first reason");
      alice.readUntil("306", Duration.ofSeconds(5));
      String first = bob.readUntil("AWAY", Duration.ofSeconds(5));
      assertThat(first).contains("first reason");

      alice.send("AWAY :second reason");
      alice.readUntil("306", Duration.ofSeconds(5));
      String second = bob.readUntil("AWAY", Duration.ofSeconds(5));
      assertThat(second).contains("second reason");
    }
  }

  @Test
  void noticePresentsTheCloakedHostnameNotTheRealOne() throws Exception {
    String yaml = "capabilities:\n  away-notify: enabled\n" + TestServer.adminAndCloakEnabledYaml();
    try (TestServer server = TestServer.start(yaml);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.send("CAP REQ :away-notify");
      bob.readUntil("CAP * ACK", Duration.ofSeconds(5));
      bob.send("CAP END");
      bob.registerAndAwaitWelcome("bob", "bob");
      root.registerAndAwaitWelcome("root", "root");
      root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
      root.readUntil("381", Duration.ofSeconds(5));

      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #lobby");
      bob.readUntil("353", Duration.ofSeconds(5));

      root.send("WHOHOST alice");
      String whohostNotice = root.readUntil("NOTICE", Duration.ofSeconds(5));
      String realHost =
          whohostNotice.substring(whohostNotice.indexOf("connecting from ") + 16).trim();

      alice.send("AWAY :gone for lunch");
      alice.readUntil("306", Duration.ofSeconds(5));
      String notice = bob.readUntil("AWAY", Duration.ofSeconds(5));
      assertThat(notice).doesNotContain(realHost);
    }
  }
}
