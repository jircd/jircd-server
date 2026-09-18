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

class Story1NicknameRaceTest {

  @Test
  void secondClaimOfSameNicknameIsRejected() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient first = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient second = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      first.send("NICK Alice");
      Thread.sleep(
          200); // give the server time to commit the first claim before the second races it

      second.send("NICK alice");
      String reply = second.readUntil("433", Duration.ofSeconds(5));
      assertThat(reply)
          .contains("433")
          .contains("*"); // addressed to * (FR-053), no nickname claimed yet
    }
  }

  @Test
  void uppercaseVariantIsAlsoRejectedAsInUse() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient first = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient second = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      first.send("NICK Alice");
      Thread.sleep(
          200); // give the server time to commit the first claim before the second races it
      second.send("NICK ALICE");
      String reply = second.readUntil("433", Duration.ofSeconds(5));
      assertThat(reply).contains("433");
    }
  }

  @Test
  void caseOnlyNicknameChangeKeepsTheOriginalClaim() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient first = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient second = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      first.registerAndAwaitWelcome("Alice", "first");

      first.send("NICK alice");
      assertThat(first.readUntil("NICK alice", Duration.ofSeconds(5))).contains("Alice!");

      second.send("NICK Alice");
      assertThat(second.readUntil("433", Duration.ofSeconds(5))).contains("433");
    }
  }
}
