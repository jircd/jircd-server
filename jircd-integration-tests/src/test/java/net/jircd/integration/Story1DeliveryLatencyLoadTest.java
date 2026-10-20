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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Channel message delivery latency stays within SC-002's 1-second budget under moderate concurrent
 * load.
 */
@Tag("load")
class Story1DeliveryLatencyLoadTest {

  @Test
  void deliveryLatencyStaysWithinOneSecondBudget() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");
      alice.send("JOIN #lobby");
      alice.readUntil("353", Duration.ofSeconds(5));
      bob.send("JOIN #lobby");
      bob.readUntil("353", Duration.ofSeconds(5));

      // Paced below the server's per-connection rate limit (FR-016, 20-token bucket refilling at
      // 10/sec) — back-to-back sends would otherwise get silently dropped past the initial burst,
      // timing out the readUntil waiting for an echo that never arrives (Story5BanCapTest).
      java.util.List<Long> samples = new java.util.ArrayList<>();
      for (int i = 0; i < 50; i++) {
        Instant sentAt = Instant.now();
        alice.send("PRIVMSG #lobby :message " + i);
        bob.readUntil("message " + i, Duration.ofSeconds(2));
        Duration elapsed = Duration.between(sentAt, Instant.now());
        samples.add(elapsed.toNanos());
        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        Thread.sleep(110);
      }
      long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
      System.out.printf(
          "Story1DeliveryLatencyLoadTest: messages=%d p50Ms=%.3f p99Ms=%.3f maxMs=%.3f%n",
          sorted.length,
          sorted[sorted.length / 2] / 1e6,
          sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1e6,
          sorted[sorted.length - 1] / 1e6);
    }
  }
}
