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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server sustains 1,000 simultaneous connections without channel message delivery delay
 * exceeding SC-002's 1-second budget (SC-003).
 */
@Tag("load")
class ConcurrentConnectionScaleLoadTest {

    private static final int CONNECTION_COUNT = 1000;

    // PMD's CloseResource check doesn't credit the finally-block loop below closing every entry in
    // idleConnections — each is genuinely closed, just not in the same scope it was opened in.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void deliveryLatencyStaysWithinBudgetAtOneThousandConnections() throws Exception {
        try (TestServer server =
                        TestServer.start("maxConnections: 10000\nmaxConnectionsPerIp: 10000\n");
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            List<RawIrcClient> idleConnections = new ArrayList<>();
            try {
                for (int i = 0; i < CONNECTION_COUNT - 2; i++) {
                    RawIrcClient idle = RawIrcClient.connectPlaintext(server.plaintextPort());
                    idle.registerAndAwaitWelcome("idle" + i, "idle" + i);
                    idleConnections.add(idle);
                }

                // Paced below the server's per-connection rate limit (FR-016, 20-token bucket
                // refilling at 10/sec — Story5BanCapTest) so this loop measures delivery latency,
                // not throttling.
                List<Long> samples = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
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
                        "ConcurrentConnectionScaleLoadTest: messages=%d p50Ms=%.3f p99Ms=%.3f maxMs=%.3f%n",
                        sorted.length,
                        sorted[sorted.length / 2] / 1e6,
                        sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1e6,
                        sorted[sorted.length - 1] / 1e6);
            } finally {
                for (RawIrcClient idle : idleConnections) {
                    idle.close();
                }
            }
        }
    }
}
