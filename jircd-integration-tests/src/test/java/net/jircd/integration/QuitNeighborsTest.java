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

/** Shared neighbors receive one QUIT; a channel left without members disappears. */
class QuitNeighborsTest {
  // Loop variables borrow clients owned and closed by the enclosing try-with-resources block.
  @SuppressWarnings("PMD.CloseResource")
  @Test
  void quitDeduplicatesNeighborsAndDropsOnlyEmptyChannels() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient dave = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      List<RawIrcClient> clients = List.of(alice, bob, carol, dave);
      List<String> nicks = List.of("alice", "bob", "carol", "dave");
      for (int i = 0; i < clients.size(); i++) {
        clients.get(i).registerAndAwaitWelcome(nicks.get(i), nicks.get(i));
        clients.get(i).readThroughPong(Duration.ofSeconds(5));
      }
      for (String channel : List.of("#a", "#b", "#solo")) {
        alice.send("JOIN " + channel);
        alice.readUntil("366", Duration.ofSeconds(5));
      }
      for (String channel : List.of("#a", "#b")) {
        bob.send("JOIN " + channel);
        bob.readUntil("366", Duration.ofSeconds(5));
        alice.readUntil("JOIN " + channel, Duration.ofSeconds(5));
      }
      carol.send("JOIN #b");
      carol.readUntil("366", Duration.ofSeconds(5));
      alice.readUntil("JOIN #b", Duration.ofSeconds(5));
      bob.readUntil("JOIN #b", Duration.ofSeconds(5));
      alice.send("QUIT :finished");
      for (RawIrcClient neighbor : List.of(bob, carol)) {
        assertThat(neighbor.readUntil(" QUIT ", Duration.ofSeconds(5))).startsWith(":alice!");
        assertThat(
                neighbor.readThroughPong(Duration.ofSeconds(5)).stream()
                    .filter(l -> l.contains(" QUIT ")))
            .isEmpty();
      }
      assertThat(
              dave.readThroughPong(Duration.ofSeconds(5)).stream()
                  .filter(l -> l.contains(" QUIT ")))
          .isEmpty();
      dave.send("LIST");
      List<String> listing = dave.readThroughPong(Duration.ofSeconds(5));
      assertThat(listing.stream().filter(l -> l.contains(" 322 ")))
          .anyMatch(l -> l.contains(" #a "))
          .noneMatch(l -> l.contains(" #solo "));
      assertThat(listing.stream().filter(l -> l.contains(" 323 "))).hasSize(1);
    }
  }
}
