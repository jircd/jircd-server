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
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopicMetadataTest {
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  @Test
  void topicMetadataIsSentOnQueryAndJoinAndDisappearsWhenCleared() throws Exception {
    try (TestServer server = TestServer.start(TestServer.CLOAK_ENABLED_YAML);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");
      bob.readThroughPong(READ_TIMEOUT);
      alice.send("JOIN #topic");
      assertThat(alice.readThroughPong(READ_TIMEOUT)).noneMatch(l -> l.contains(" 333 "));
      long before = Instant.now().getEpochSecond();
      alice.send("TOPIC #topic :socket topic");
      alice.readThroughPong(READ_TIMEOUT);
      long after = Instant.now().getEpochSecond();
      bob.send("TOPIC #topic");
      List<String> query = bob.readThroughPong(READ_TIMEOUT);
      assertThat(query.getFirst()).contains(" 332 bob #topic :socket topic");
      assertThat(query.get(1)).contains(" 333 bob #topic alice!alice@");
      String[] metadata = query.get(1).split(" ");
      assertThat(metadata[4]).doesNotContain(TestServer.LOOPBACK);
      assertThat(Long.parseLong(metadata[5])).isBetween(before, after);
      bob.send("JOIN #topic");
      List<String> joined = bob.readThroughPong(READ_TIMEOUT);
      int topicIndex =
          java.util.stream.IntStream.range(0, joined.size())
              .filter(i -> joined.get(i).contains(" 332 "))
              .findFirst()
              .orElseThrow();
      assertThat(joined.get(topicIndex + 1)).isEqualTo(query.get(1));
      alice.send("TOPIC #topic :");
      alice.readThroughPong(READ_TIMEOUT);
      bob.readUntil("TOPIC #topic", READ_TIMEOUT);
      bob.send("TOPIC #topic");
      List<String> cleared = bob.readThroughPong(READ_TIMEOUT);
      assertThat(cleared.getFirst()).contains(" 331 bob #topic :No topic is set");
      assertThat(cleared).noneMatch(l -> l.contains(" 332 ") || l.contains(" 333 "));
      bob.send("PART #topic");
      bob.readThroughPong(READ_TIMEOUT);
      bob.send("JOIN #topic");
      assertThat(bob.readThroughPong(READ_TIMEOUT))
          .noneMatch(l -> l.contains(" 332 ") || l.contains(" 333 "));
    }
  }
}
