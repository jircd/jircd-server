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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/** The {@code multi-prefix} capability over real sockets: 353 on JOIN and NAMES, and 352 on WHO. */
class MultiPrefixTest {

  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final String YAML = "capabilities:\n  multi-prefix: enabled\n";

  private static void register(RawIrcClient client, String nick, boolean multiPrefix)
      throws Exception {
    if (multiPrefix) {
      client.send("CAP REQ :multi-prefix");
      assertThat(client.readUntil("CAP * ACK", WAIT)).endsWith(" multi-prefix");
      client.send("CAP END");
    }
    client.registerAndAwaitWelcome(nick, nick);
  }

  /** The 353 member list as a set of tokens, since members are listed in no fixed order. */
  private static List<String> members(RawIrcClient client, String nick) throws Exception {
    List<String> params = read(client, "353").params();
    assertThat(params.subList(0, 3)).containsExactly(nick, "=", "#lobby");
    return Arrays.asList(params.get(3).split(" "));
  }

  private static Message read(RawIrcClient client, String code) throws Exception {
    return MessageParser.parse(client.readUntil(code, WAIT));
  }

  @Test
  void capLsOffersMultiPrefixAndReqIsAcked() throws Exception {
    try (TestServer server = TestServer.start(YAML);
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      client.send("CAP LS 302");
      assertThat(client.readUntil("CAP * LS", WAIT)).contains("multi-prefix");
      client.send("CAP REQ :multi-prefix");
      assertThat(client.readUntil("CAP * ACK", WAIT)).endsWith(" multi-prefix");
    }
  }

  @Test
  void opAndVoiceMemberIsListedWithBothPrefixesOnlyToAClientThatNegotiatedIt() throws Exception {
    try (TestServer server = TestServer.start(YAML);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      register(alice, "alice", false);
      alice.send("JOIN #lobby");
      read(alice, "366");
      // alice is the first joiner, hence operator; voicing herself makes her hold both.
      alice.send("MODE #lobby +v alice");
      alice.readUntil("MODE #lobby +v alice", WAIT);

      register(bob, "bob", true);
      bob.send("JOIN #lobby");
      assertThat(members(bob, "bob")).containsExactlyInAnyOrder("@+alice", "bob");

      register(carol, "carol", false);
      carol.send("JOIN #lobby");
      assertThat(members(carol, "carol")).containsExactlyInAnyOrder("@alice", "bob", "carol");

      bob.send("NAMES #lobby");
      assertThat(members(bob, "bob")).containsExactlyInAnyOrder("@+alice", "bob", "carol");
      carol.send("NAMES #lobby");
      assertThat(members(carol, "carol")).containsExactlyInAnyOrder("@alice", "bob", "carol");

      bob.send("WHO #lobby");
      assertThat(whoFlags(bob))
          .containsExactlyInAnyOrderEntriesOf(Map.of("alice", "H@+", "bob", "H", "carol", "H"));
      carol.send("WHO #lobby");
      assertThat(whoFlags(carol))
          .containsExactlyInAnyOrderEntriesOf(Map.of("alice", "H@", "bob", "H", "carol", "H"));
    }
  }

  /** The flags field of each 352 by nickname, up to the closing 315. */
  private static Map<String, String> whoFlags(RawIrcClient client) throws Exception {
    Map<String, String> flags = new HashMap<>();
    for (int i = 0; i < 3; i++) {
      List<String> params = read(client, "352").params();
      flags.put(params.get(5), params.get(6));
    }
    read(client, "315");
    return flags;
  }
}
