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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Repeatable NAMES response workload for the negotiated userhost-in-names capability. */
@Tag("load")
class UserhostInNamesLoadTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int WARMUP_REQUESTS = 1;
    private static final int MEASURED_REQUESTS = 5;
    private static final int MAX_WIRE_LINE_BYTES = 512;
    private static final String BASELINE_ENV = "JIRCD_NAMES_BASELINE";
    private static final String CAPABILITY_YAML =
            "capabilities:\n  multi-prefix: enabled\n  userhost-in-names: enabled\n";
    private static final String WORKLOAD_LIMITS_YAML =
            """
            maxConnections: 1100
            maxConnectionsPerIp: 1100
            sendqLimitBytes: 2097152
            rateLimit:
              bucketSize: 100000
              refillRatePerSecond: 100000
            """;

    // Sockets borrowed here are owned by the Clients resource and closed before the server.
    @SuppressWarnings("PMD.CloseResource")
    @ParameterizedTest
    @ValueSource(ints = {10, 100, 1000})
    void namesCompletesWithinBudgetWithoutLosingMembers(int visibleMembers) throws Exception {
        boolean baseline = Boolean.parseBoolean(System.getenv(BASELINE_ENV));
        String yaml = WORKLOAD_LIMITS_YAML + (baseline ? "" : CAPABILITY_YAML);
        try (TestServer server = TestServer.start(yaml);
                Clients clients = new Clients()) {
            for (int index = 0; index < visibleMembers; index++) {
                RawIrcClient member = clients.open(server.plaintextPort());
                String nickname = nickname(index);
                member.registerAndAwaitWelcome(nickname, nickname);
                if (index == 0 && !baseline) {
                    negotiate(member, nickname, "multi-prefix userhost-in-names");
                }
                member.send("JOIN #load");
                member.readUntil(" 366 ", WAIT);
            }

            RawIrcClient requester = clients.entries.getFirst();
            for (int index = 0; index < WARMUP_REQUESTS; index++) {
                assertNamesResponse(requester, visibleMembers, baseline);
            }

            List<Long> samples = new ArrayList<>(MEASURED_REQUESTS);
            long workloadStart = System.nanoTime();
            for (int index = 0; index < MEASURED_REQUESTS; index++) {
                long started = System.nanoTime();
                assertNamesResponse(requester, visibleMembers, baseline);
                long elapsed = System.nanoTime() - started;
                assertThat(elapsed).isLessThan(Duration.ofSeconds(1).toNanos());
                samples.add(elapsed);
            }
            long workloadElapsed = System.nanoTime() - workloadStart;
            printStats(visibleMembers, baseline, samples, workloadElapsed);
        }
    }

    /** Owns every opened socket; try-with-resources preserves a primary assertion failure. */
    private static final class Clients implements AutoCloseable {
        private final List<RawIrcClient> entries = new ArrayList<>();

        RawIrcClient open(int port) throws IOException {
            RawIrcClient client = RawIrcClient.connectPlaintext(port);
            entries.add(client);
            return client;
        }

        @Override
        public void close() throws IOException {
            closeClients(entries);
        }
    }

    private static void assertNamesResponse(
            RawIrcClient requester, int memberCount, boolean baseline) throws Exception {
        requester.send("NAMES #load");
        List<String> wireLines = requester.readThroughPong(WAIT);
        assertThat(wireLines).isNotEmpty();
        for (String line : wireLines) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length + 2)
                    .as("serialized IRC line including CR-LF")
                    .isLessThanOrEqualTo(MAX_WIRE_LINE_BYTES);
        }

        List<Message> replies = new ArrayList<>();
        for (String line : wireLines) replies.add(MessageParser.parse(line));
        List<Message> nameReplies =
                replies.stream().filter(message -> "353".equals(message.rawCommand())).toList();
        assertThat(replies.stream().filter(message -> "366".equals(message.rawCommand())))
                .singleElement()
                .satisfies(
                        message ->
                                assertThat(message.params().getLast())
                                        .isEqualTo("End of /NAMES list"));

        List<String> actualTokens =
                nameReplies.stream()
                        .flatMap(message -> Arrays.stream(message.params().getLast().split(" ")))
                        .toList();
        assertThat(actualTokens).hasSize(memberCount);
        assertThat(actualTokens).doesNotHaveDuplicates();
        List<String> expectedTokens =
                IntStream.range(0, memberCount)
                        .mapToObj(index -> expectedToken(index, baseline))
                        .toList();
        assertThat(actualTokens).containsExactlyInAnyOrderElementsOf(expectedTokens);
    }

    private static void negotiate(RawIrcClient client, String nickname, String capabilities)
            throws Exception {
        client.send("CAP REQ :" + capabilities);
        String acknowledgement = client.readUntil("CAP " + nickname + " ACK", WAIT);
        assertThat(MessageParser.parse(acknowledgement).params().getLast()).isEqualTo(capabilities);
    }

    private static String expectedToken(int index, boolean baseline) {
        String prefix = index == 0 ? "@" : "";
        String nickname = nickname(index);
        return prefix + (baseline ? nickname : nickname + "!" + nickname + "@127.0.0.1");
    }

    private static String nickname(int index) {
        return String.format(Locale.ROOT, "member%03d", index);
    }

    private static void printStats(
            int members, boolean baseline, List<Long> samples, long workloadNanos) {
        long[] sorted = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        double seconds = workloadNanos / 1_000_000_000.0;
        System.out.printf(
                Locale.ROOT,
                "UserhostInNamesLoadTest: mode=%s members=%d requests=%d p50Ms=%.3f p99Ms=%.3f maxMs=%.3f requestsPerSecond=%.2f%n",
                baseline ? "baseline" : "candidate",
                members,
                sorted.length,
                sorted[sorted.length / 2] / 1e6,
                sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1e6,
                sorted[sorted.length - 1] / 1e6,
                sorted.length / seconds);
    }

    // PMD cannot follow the list ownership; every element is closed, even after an IOException.
    @SuppressWarnings("PMD.CloseResource")
    private static void closeClients(List<RawIrcClient> clients) throws IOException {
        IOException failure = null;
        for (RawIrcClient client : clients) {
            try {
                client.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
