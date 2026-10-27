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
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Repeatable #97 publication timing at 1,000 live socket sessions that all hold the toggled
 * capability. Each disable publishes the snapshot and prunes every session under the administrative
 * mutex. The first pass has no notification-eligible client, so it times the wait plus publication
 * and prune alone; the second makes every client eligible, so the difference is the {@code
 * DEL}/{@code NEW} enqueue. Both include the provider's own stop and command removal, so they bound
 * the publication step from above. #102 adds the dedicated final load classes.
 */
@Tag("load")
class CapabilityPublicationTimingLoadTest {

    private static final int SESSIONS = 1000;
    private static final int WARMUP = 5;
    private static final int CYCLES = 40;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** The per-connection rate limit is raised: over-rate commands are dropped, not delayed. */
    private static final String YAML =
            """
      maxConnections: 10000
      maxConnectionsPerIp: 10000
      rateLimit:
        bucketSize: 10000
        refillRatePerSecond: 10000
      capabilities:
        multi-prefix: enabled
      """;

    private record Sample(String label, long[] sortedNanos) {
        double p50Ms() {
            return sortedNanos[sortedNanos.length / 2] / 1e6;
        }

        double p99Ms() {
            return sortedNanos[(int) Math.ceil(sortedNanos.length * 0.99) - 1] / 1e6;
        }

        double maxMs() {
            return sortedNanos[sortedNanos.length - 1] / 1e6;
        }

        void print() {
            System.out.printf(
                    "CapabilityPublicationTimingLoadTest: %s sessions=%d cycles=%d p50Ms=%.3f p99Ms=%.3f"
                            + " maxMs=%.3f%n",
                    label, SESSIONS, sortedNanos.length, p50Ms(), p99Ms(), maxMs());
        }
    }

    /** Disables then re-enables, re-requesting on every client so each disable prunes them all. */
    // The clients belong to the caller, which closes them; this only writes to and reads from them.
    @SuppressWarnings("PMD.CloseResource")
    private static Sample[] measure(
            String label, ExtensionRegistry registry, List<RawIrcClient> clients) throws Exception {
        List<Long> disables = new ArrayList<>();
        List<Long> enables = new ArrayList<>();
        for (int cycle = 0; cycle < WARMUP + CYCLES; cycle++) {
            long start = System.nanoTime();
            registry.disable("multi-prefix");
            long disabled = System.nanoTime();
            registry.enable("multi-prefix");
            long enabled = System.nanoTime();
            if (cycle >= WARMUP) {
                disables.add(disabled - start);
                enables.add(enabled - disabled);
            }
            for (RawIrcClient client : clients) client.send("CAP REQ :multi-prefix");
            // Every request is answered before the next disable, so each one prunes every session.
            for (RawIrcClient client : clients) client.readUntil("ACK", TIMEOUT);
        }
        return new Sample[] {
            new Sample(
                    label + " disable",
                    disables.stream().mapToLong(Long::longValue).sorted().toArray()),
            new Sample(
                    label + " enable",
                    enables.stream().mapToLong(Long::longValue).sorted().toArray())
        };
    }

    // Every client is closed in the finally loop, not in the scope that opened it.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void publicationAndPruneAtOneThousandSessionsStayWithinTheBudget() throws Exception {
        try (TestServer server = TestServer.start(YAML)) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            List<RawIrcClient> clients = new ArrayList<>();
            try {
                for (int i = 0; i < SESSIONS; i++) {
                    RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
                    clients.add(client);
                    client.send("CAP REQ :multi-prefix");
                    client.send("CAP END");
                    client.registerAndAwaitWelcome("s" + i, "s" + i);
                }

                Sample[] quiet = measure("no-notify", registry, clients);
                for (RawIrcClient client : clients) {
                    client.send("CAP LS 302");
                    client.readUntil(" LS ", TIMEOUT);
                }
                Sample[] notified = measure("notify-302", registry, clients);

                for (Sample sample : quiet) sample.print();
                for (Sample sample : notified) sample.print();
                assertThat(quiet[0].p99Ms()).isLessThanOrEqualTo(100.0);
            } finally {
                for (RawIrcClient client : clients) client.close();
            }
        }
    }
}
