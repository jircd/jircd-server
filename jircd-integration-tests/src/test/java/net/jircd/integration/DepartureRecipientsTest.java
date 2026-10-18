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

/** Departure notices include the departing member and precede later numeric replies. */
class DepartureRecipientsTest {
  // Loop variables borrow clients owned and closed by the enclosing try-with-resources block.
  @SuppressWarnings("PMD.CloseResource")
  @Test
  void partAndKickReachEveryPreDepartureMemberBeforeLaterReplies() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      List<RawIrcClient> clients = List.of(alice, bob, carol);
      List<String> nicks = List.of("alice", "bob", "carol");
      for (int i = 0; i < clients.size(); i++) {
        RawIrcClient client = clients.get(i);
        client.registerAndAwaitWelcome(nicks.get(i), nicks.get(i));
        client.send("JOIN #c");
        client.readUntil("366", Duration.ofSeconds(5));
        for (int j = 0; j < i; j++) {
          clients.get(j).readUntil("JOIN #c", Duration.ofSeconds(5));
        }
      }
      bob.send("PART #c :leaving");
      bob.send("NAMES #c");
      List<String> bobLines = bob.readThroughPong(Duration.ofSeconds(5));
      assertThat(bobLines.getFirst()).contains(" PART #c");
      assertThat(bobLines.stream().filter(l -> l.contains(" PART #c"))).hasSize(1);
      assertThat(bobLines.stream().filter(l -> l.contains(" 353 "))).hasSize(1);
      assertThat(alice.readUntil(" PART #c", Duration.ofSeconds(5))).startsWith(":bob!");
      assertThat(carol.readUntil(" PART #c", Duration.ofSeconds(5))).startsWith(":bob!");

      alice.send("KICK #c carol :leaving");
      alice.send("NAMES #c");
      List<String> aliceLines = alice.readThroughPong(Duration.ofSeconds(5));
      assertThat(aliceLines.getFirst()).contains(" KICK #c carol");
      assertThat(aliceLines.stream().filter(l -> l.contains(" KICK #c carol"))).hasSize(1);
      assertThat(aliceLines.stream().filter(l -> l.contains(" 353 "))).hasSize(1);
      carol.send("NAMES #c");
      List<String> carolLines = carol.readThroughPong(Duration.ofSeconds(5));
      assertThat(carolLines.getFirst()).contains(" KICK #c carol");
      assertThat(carolLines.stream().filter(l -> l.contains(" KICK #c carol"))).hasSize(1);
      assertThat(carolLines.stream().filter(l -> l.contains(" 353 "))).hasSize(1);
    }
  }
}
