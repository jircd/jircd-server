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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Full mask lists must preserve the existing SC-002 one-second operation budget. */
@Tag("load")
class ExceptionModesLoadTest {
  @Test
  void fullListsPreserveJoinAndMessageLatencyBudget() throws Exception {
    Duration timeout = Duration.ofSeconds(5);
    try (TestServer server =
            TestServer.start("rateLimit:\n  bucketSize: 512\n  refillRatePerSecond: 10\n");
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      alice.registerAndAwaitWelcome("alice", "alice");
      bob.registerAndAwaitWelcome("bob", "bob");
      alice.send("JOIN #c");
      alice.readUntil("366", timeout);
      // Put matches last to exercise all 100 masks in every list. The configured bucket covers
      // this bounded workload; each expected reply, rather than a delay, gates the next command.
      for (int i = 0; i < 99; i++) {
        alice.send("MODE #c +beI mask" + i + " mask" + i + " mask" + i);
        alice.readUntil("MODE #c +beI", timeout);
      }
      alice.send("MODE #c +ibeI * bob bob");
      alice.readUntil("MODE #c +ibeI", timeout);
      long joinStart = System.nanoTime();
      bob.send("JOIN #c");
      bob.readUntil("366", timeout);
      Duration joinLatency = Duration.ofNanos(System.nanoTime() - joinStart);
      assertThat(joinLatency).isLessThan(Duration.ofSeconds(1));
      List<Long> samples = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        long start = System.nanoTime();
        bob.send("PRIVMSG #c :message " + i);
        alice.readUntil("message " + i, timeout);
        Duration latency = Duration.ofNanos(System.nanoTime() - start);
        assertThat(latency).isLessThan(Duration.ofSeconds(1));
        samples.add(latency.toNanos());
      }
      long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
      System.out.printf(
          "ExceptionModesLoadTest: joinMs=%.3f messages=%d p50Ms=%.3f maxMs=%.3f%n",
          joinLatency.toNanos() / 1e6,
          sorted.length,
          sorted[sorted.length / 2] / 1e6,
          sorted[sorted.length - 1] / 1e6);
    }
  }
}
