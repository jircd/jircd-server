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

/** Channel mode and topic changes echo once per member, preserving private/secret exclusion. */
class ModeFanoutTest {
  // Loop variables borrow clients owned and closed by the enclosing try-with-resources block.
  @SuppressWarnings("PMD.CloseResource")
  @Test
  void modeAndTopicChangesReachEachMemberExactlyOnce() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient carol = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      List<RawIrcClient> clients = List.of(alice, bob, carol);
      List<String> nicks = List.of("alice", "bob", "carol");
      for (int i = 0; i < clients.size(); i++) {
        clients.get(i).registerAndAwaitWelcome(nicks.get(i), nicks.get(i));
        clients.get(i).send("JOIN #c");
        clients.get(i).readUntil("366", Duration.ofSeconds(5));
        for (int j = 0; j < i; j++) {
          clients.get(j).readUntil("JOIN #c", Duration.ofSeconds(5));
        }
      }
      for (String command :
          List.of(
              "MODE #c +o bob",
              "MODE #c +v carol",
              "MODE #c +s",
              "MODE #c +p",
              "TOPIC #c :new topic")) {
        alice.send(command);
        // Source barrier ensures fan-out is enqueued before each peer's barrier.
        List<String> sourceLines = alice.readThroughPong(Duration.ofSeconds(5));
        String verb = command.startsWith("MODE") ? " MODE #c " : " TOPIC #c ";
        assertThat(sourceLines.stream().filter(l -> l.contains(verb))).hasSize(1);
        for (RawIrcClient peer : List.of(bob, carol)) {
          assertThat(
                  peer.readThroughPong(Duration.ofSeconds(5)).stream()
                      .filter(l -> l.contains(verb)))
              .hasSize(1);
        }
      }
      alice.send("MODE #c");
      String modes = alice.readUntil("324", Duration.ofSeconds(5)).split(" ")[4];
      assertThat(modes).contains("p").doesNotContain("s");
    }
  }
}
