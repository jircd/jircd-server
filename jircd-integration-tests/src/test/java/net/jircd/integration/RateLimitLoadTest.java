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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * During a sustained flood of excessive traffic from a single connection, delivery latency for
 * other well-behaved clients does not increase beyond SC-002's 1-second target (SC-006) — the
 * per-connection rate limit (FR-016) drops the flooder's excess traffic silently rather than
 * degrading anyone else sharing the channel.
 */
@Tag("load")
class RateLimitLoadTest {

    @Test
    void floodFromOneConnectionDoesNotDegradeOtherWellBehavedClients() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient flooder = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");
            flooder.registerAndAwaitWelcome("flooder", "flooder");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));
            flooder.send("JOIN #lobby");
            flooder.readUntil("353", Duration.ofSeconds(5));

            Thread floodThread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            for (int i = 0; i < 500; i++) {
                                                flooder.send("PRIVMSG #lobby :flood " + i);
                                            }
                                        } catch (IOException ignored) {
                                            // Best-effort flood — this test asserts alice/bob's
                                            // latency, not the flooder's own delivery.
                                        }
                                    });

            // Paced below alice's own per-connection rate limit (FR-016, 20-token bucket refilling
            // at 10/sec — Story5BanCapTest) — alice is the well-behaved party here, not the one
            // under test for throttling; only the flooder's connection is meant to exceed its own
            // budget.
            for (int i = 0; i < 20; i++) {
                Instant sentAt = Instant.now();
                alice.send("PRIVMSG #lobby :well-behaved " + i);
                bob.readUntil("well-behaved " + i, Duration.ofSeconds(2));
                Duration elapsed = Duration.between(sentAt, Instant.now());
                assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
                Thread.sleep(110);
            }

            floodThread.join(Duration.ofSeconds(10));
        }
    }
}
