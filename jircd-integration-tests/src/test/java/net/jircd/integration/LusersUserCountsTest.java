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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue #76: {@code LUSERS} also sends {@code 253}, {@code 265} and {@code 266}, in the order 251,
 * 252, 253, 254, 255, 265, 266 (irctest {@code lusers.py testLusersFull}).
 */
class LusersUserCountsTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void localAndGlobalUsersCarryTheCurrentCountAndMax() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      alice.registerAndAwaitWelcome("alice", "alice");

      List<String> replies = lusers(alice);

      assertThat(find(replies, " 265 ")).endsWith(" 265 alice 1 1 :Current local users 1, max 1");
      assertThat(find(replies, " 266 ")).endsWith(" 266 alice 1 1 :Current global users 1, max 1");
      assertThat(codes(replies)).doesNotContain("253");
    }
  }

  @Test
  void maxKeepsTheHighWaterMarkAfterAUserLeaves() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");
      bob.send("JOIN #room");
      bob.readUntil(" 366 ", TIMEOUT);
      alice.send("JOIN #room"); // its NAMES lists bob, so both share the channel before bob quits
      alice.readUntil(" 366 ", TIMEOUT);

      bob.send("QUIT :bye");
      alice.readUntil("QUIT", TIMEOUT);
      List<String> replies = lusers(alice);

      assertThat(find(replies, " 265 ")).endsWith(" 265 alice 1 2 :Current local users 1, max 2");
      assertThat(find(replies, " 266 ")).endsWith(" 266 alice 1 2 :Current global users 1, max 2");
    }
  }

  @Test
  void unregisteredConnectionsAreCountedAndStopCountingOnceClosed() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient pending = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      alice.registerAndAwaitWelcome("alice", "alice");
      pending.send("NICK pending"); // claims a nick but never sends USER
      pending.readThroughPong(TIMEOUT); // the server has the connection by now

      List<String> replies = lusers(alice);

      assertThat(codes(replies)).containsExactly("251", "252", "253", "254", "255", "265", "266");
      assertThat(find(replies, " 253 ")).endsWith(" 253 alice 1 :unknown connection(s)");
      assertThat(find(replies, " 251 "))
          .endsWith(":There are 1 users and 0 invisible on 1 servers");
      assertThat(find(replies, " 265 ")).endsWith(" 265 alice 1 1 :Current local users 1, max 1");

      pending.send("QUIT :gone");
      pending.readUntil("ERROR", TIMEOUT);
      assertThat(codes(lusers(alice))).doesNotContain("253");
    }
  }

  private static List<String> lusers(RawIrcClient client) throws Exception {
    client.send("LUSERS");
    List<String> lines = new ArrayList<>();
    String last;
    do {
      last = client.readLine();
      lines.add(last);
    } while (!last.contains(" 266 "));
    return lines;
  }

  private static String find(List<String> lines, String marker) {
    return lines.stream().filter(l -> l.contains(marker)).findFirst().orElseThrow();
  }

  private static List<String> codes(List<String> lines) {
    return lines.stream()
        .map(l -> l.split(" "))
        .filter(parts -> parts.length > 1 && parts[1].matches("25[1-5]|26[56]"))
        .map(parts -> parts[1])
        .toList();
  }
}
