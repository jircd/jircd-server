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

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a bug fixed in Phase 9: {@code ConnectionHandler} previously ignored
 * {@code ServerConfiguration.rateLimit()} entirely, always applying {@code
 * RateLimitBucket.withDefaults()} (20 tokens, 10/sec) to every session regardless of what an
 * administrator configured. A configured {@code rateLimit} MUST be honored by new connections, both
 * at startup and after a live {@code REHASH} (FR-016).
 */
class RateLimitConfigTest {

    private static final String TINY_RATE_LIMIT_YAML =
            "rateLimit:\n  bucketSize: 3\n  refillRatePerSecond: 1\n";

    @Test
    void configuredRateLimitIsHonoredAtStartup() throws Exception {
        try (TestServer server = TestServer.start(TINY_RATE_LIMIT_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            // NICK + USER spend 2 of the configured 3-token bucket, leaving exactly 1.
            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("JOIN #a");
            alice.send("JOIN #b");
            alice.send("JOIN #c");
            alice.send("JOIN #d");

            // A short window, before any meaningful refill at 1/sec — the default 20-token bucket
            // would trivially absorb all four; only the configured 3-token bucket drops most of
            // them.
            List<String> lines = alice.readLinesFor(Duration.ofMillis(300));
            long joinConfirmations = lines.stream().filter(line -> line.contains("JOIN #")).count();
            assertThat(joinConfirmations).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void configuredRateLimitTakesLiveEffectAfterRehash() throws Exception {
        try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
                RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            root.registerAndAwaitWelcome("root", "root");
            root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
            root.readUntil("381", Duration.ofSeconds(5));

            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml() + TestServer.adminEnabledYaml() + TINY_RATE_LIMIT_YAML);
            root.send("REHASH");
            root.readUntil("382", Duration.ofSeconds(5));

            // Opened only after the rehash completes — the rate-limit bucket is built once, at TCP
            // accept time, so a connection opened earlier would still capture the old
            // configuration.
            try (RawIrcClient probe = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                probe.registerAndAwaitWelcome("probe", "probe");

                probe.send("JOIN #a");
                probe.send("JOIN #b");
                probe.send("JOIN #c");
                probe.send("JOIN #d");

                List<String> lines = probe.readLinesFor(Duration.ofMillis(300));
                long joinConfirmations =
                        lines.stream().filter(line -> line.contains("JOIN #")).count();
                assertThat(joinConfirmations).isLessThanOrEqualTo(1);
            }
        }
    }
}
