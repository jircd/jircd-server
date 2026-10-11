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

/** 002-extended-irc-commands User Story 2: AWAY status set/clear, and its visibility elsewhere. */
class AwayStatusTest {

  @Test
  void settingAndClearingAwayConfirmsWithTheStandardNumerics() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");

      alice.send("AWAY :gone for lunch");
      assertThat(alice.readUntil("306", Duration.ofSeconds(5))).contains("306");

      alice.send("AWAY");
      assertThat(alice.readUntil("305", Duration.ofSeconds(5))).contains("305");
    }
  }

  @Test
  void privmsgToAnAwayTargetCarriesTheAwayReplyAlongsideNormalDelivery() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");

      alice.send("AWAY :gone for lunch");
      alice.readUntil("306", Duration.ofSeconds(5));

      bob.send("PRIVMSG alice :hi");
      String awayReply = bob.readUntil("301", Duration.ofSeconds(5));
      assertThat(awayReply).contains("alice").contains("gone for lunch");
    }
  }

  @Test
  void whoisIncludesTheAwayReasonOnlyWhileAway() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");

      alice.send("AWAY :gone for lunch");
      alice.readUntil("306", Duration.ofSeconds(5));

      bob.send("WHOIS alice");
      assertThat(bob.readUntil("301", Duration.ofSeconds(5))).contains("gone for lunch");
      bob.readUntil("318", Duration.ofSeconds(5)); // RPL_ENDOFWHOIS

      alice.send("AWAY");
      alice.readUntil("305", Duration.ofSeconds(5));

      bob.send("WHOIS alice");
      String whoisUser = bob.readUntil("311", Duration.ofSeconds(5));
      assertThat(whoisUser).contains("alice");
      String next = bob.readUntil("318", Duration.ofSeconds(5)); // no 301 in between
      assertThat(next).contains("318");
    }
  }

  @Test
  void whoShowsGForAnAwayMemberAndHOnceCleared() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");
      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #lobby");
      bob.readUntil("353", Duration.ofSeconds(5));

      alice.send("AWAY :gone for lunch");
      alice.readUntil("306", Duration.ofSeconds(5));

      bob.send("WHO #lobby");
      String whoWhileAway = bob.readUntil("alice", Duration.ofSeconds(5));
      assertThat(whoWhileAway).contains(" G");
      bob.readUntil("315", Duration.ofSeconds(5)); // RPL_ENDOFWHO

      alice.send("AWAY");
      alice.readUntil("305", Duration.ofSeconds(5));

      bob.send("WHO #lobby");
      String whoAfterClear = bob.readUntil("alice", Duration.ofSeconds(5));
      assertThat(whoAfterClear).contains(" H");
    }
  }

  @Test
  void awayStatusPersistsAcrossANicknameChange() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");

      alice.send("AWAY :gone for lunch");
      alice.readUntil("306", Duration.ofSeconds(5));

      alice.send("NICK alicia");
      // Wait for the nickname change before WHOIS runs on Bob's separate connection.
      assertThat(alice.readUntil("NICK", Duration.ofSeconds(5))).contains("NICK alicia");
      bob.send("WHOIS alicia");
      assertThat(bob.readUntil("301", Duration.ofSeconds(5))).contains("gone for lunch");
    }
  }
}
