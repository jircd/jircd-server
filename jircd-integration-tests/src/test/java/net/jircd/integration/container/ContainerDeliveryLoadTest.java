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
package net.jircd.integration.container;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.integration.Polling;
import net.jircd.integration.RawIrcClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spec 016 SC-004 to SC-006 against the built image: 1,000 clients register and join one channel,
 * and every message one of them sends reaches the other 999 within a second. Prints (and writes to
 * the file named by {@code -Djircd.load.report}) p50/p99/max delivery latency, throughput, the time
 * from container start to the server accepting connections (SC-005, reported, never asserted), and
 * the container's memory with 100 idle clients. Every client answers the server's {@code PING}, so
 * keep-alive never decides the run (lesson of #128).
 */
@Tag("container-load")
class ContainerDeliveryLoadTest {

    private static final int CLIENTS = 1_000;
    private static final int IDLE_CLIENTS = 100;
    private static final int MESSAGES = 30;
    private static final Duration BUDGET = Duration.ofSeconds(1);
    private static final Duration SETUP_TIMEOUT = Duration.ofSeconds(120);
    private static final String CHANNEL = "#load";

    @TempDir Path dir;

    private final AtomicLongArray sentAt = new AtomicLongArray(MESSAGES);
    private final long[][] latencyNanos = new long[MESSAGES][CLIENTS];
    private final AtomicInteger registered = new AtomicInteger();
    private final AtomicInteger delivered = new AtomicInteger();
    private final AtomicLong lastDelivery = new AtomicLong();
    private final CountDownLatch allDelivered = new CountDownLatch(MESSAGES * (CLIENTS - 1));
    private volatile boolean running = true;

    // Every client is closed in the finally block below, on every path, not where it is opened.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void thousandClientsReceiveEveryMessageWithinOneSecond() throws Exception {
        // The default of 20 connections per source address would refuse the 21st test client.
        ContainerFixture.write(
                dir.resolve("etc"), "maxConnections: 5000\nmaxConnectionsPerIp: 5000\n");
        List<RawIrcClient> clients = new ArrayList<>();
        try (JircdContainer jircd = JircdContainer.start(dir.resolve("etc"));
                ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                // SC-005 is informational: native is experimental (FR-016), so never asserted.
                long startupNanos = jircd.startupDuration().toNanos();
                connect(jircd, readers, clients, 0, 1);
                connect(jircd, readers, clients, 1, IDLE_CLIENTS);
                // Idle: let allocation and GC settle before sampling memory.
                Polling.pause(Duration.ofSeconds(3));
                long idleMemoryBytes = jircd.memoryBytes();
                connect(jircd, readers, clients, IDLE_CLIENTS, CLIENTS);
                assertThat(registered).as("registered clients").hasValue(CLIENTS);

                long firstSend = System.nanoTime();
                sendPaced(clients.getFirst());
                boolean complete = allDelivered.await(30, TimeUnit.SECONDS);
                long lastNanos = lastDelivery.get();
                assertThat(complete).as("deliveries received: " + delivered.get()).isTrue();

                report(startupNanos, idleMemoryBytes, Duration.ofNanos(lastNanos - firstSend));
            } finally {
                // Stop the reader loops first, or closing the executor would wait for them forever.
                running = false;
                for (RawIrcClient client : clients) client.close();
            }
        }
    }

    /**
     * Connects clients {@code from} (inclusive) to {@code to} (exclusive); returns the last ready.
     */
    // Each client lives until the test's finally block closes the whole list.
    @SuppressWarnings("PMD.CloseResource")
    private long connect(
            JircdContainer jircd,
            ExecutorService readers,
            List<RawIrcClient> clients,
            int from,
            int to)
            throws Exception {
        int count = to - from;
        List<RawIrcClient> batch = new ArrayList<>(java.util.Collections.nCopies(count, null));
        CountDownLatch joined = new CountDownLatch(count);
        AtomicLong lastReady = new AtomicLong();
        Semaphore inFlight = new Semaphore(50); // do not overrun the server's accept backlog
        for (int index = from; index < to; index++) {
            int member = index;
            inFlight.acquire();
            readers.execute(
                    () -> {
                        try {
                            RawIrcClient client =
                                    RawIrcClient.connectPlaintext(jircd.plaintextPort());
                            client.registerAndAwaitWelcome("c" + member, "c" + member);
                            registered.incrementAndGet();
                            client.send("JOIN " + CHANNEL);
                            client.readUntil(" 366 ", SETUP_TIMEOUT);
                            synchronized (batch) {
                                batch.set(member - from, client);
                            }
                            lastReady.accumulateAndGet(System.nanoTime(), Math::max);
                            inFlight.release();
                            joined.countDown();
                            read(client, member);
                        } catch (IOException e) {
                            inFlight.release();
                            joined.countDown();
                            if (running) throw new java.io.UncheckedIOException(e);
                        }
                    });
        }
        if (!joined.await(SETUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("Only " + registered.get() + " clients registered");
        }
        synchronized (batch) {
            clients.addAll(batch);
        }
        return lastReady.get();
    }

    /**
     * Sends {@link #MESSAGES} messages, one every 110 ms (under the per-connection rate limit of 20
     * tokens refilling at 10 per second), and returns once the last one is sent.
     */
    private void sendPaced(RawIrcClient sender) throws InterruptedException, IOException {
        AtomicInteger next = new AtomicInteger();
        AtomicReference<IOException> failure = new AtomicReference<>();
        CountDownLatch sentAll = new CountDownLatch(1);
        try (ScheduledExecutorService pacer = Executors.newSingleThreadScheduledExecutor()) {
            pacer.scheduleAtFixedRate(
                    () -> {
                        int message = next.getAndIncrement();
                        if (message >= MESSAGES) return;
                        try {
                            sentAt.set(message, System.nanoTime());
                            sender.send("PRIVMSG " + CHANNEL + " :msg-" + message);
                        } catch (IOException e) {
                            failure.set(e);
                            sentAll.countDown();
                            return;
                        }
                        if (message == MESSAGES - 1) sentAll.countDown();
                    },
                    0,
                    110,
                    TimeUnit.MILLISECONDS);
            assertThat(sentAll.await(30, TimeUnit.SECONDS)).as("all messages sent").isTrue();
            pacer.shutdownNow();
        }
        if (failure.get() != null) throw failure.get();
    }

    /** Answers PING and records the arrival of each {@code msg-N} until the test ends. */
    private void read(RawIrcClient client, int member) throws IOException {
        while (running) {
            String line;
            try {
                line = client.readLine();
            } catch (SocketTimeoutException _) {
                continue;
            } catch (SocketException _) {
                return; // closed by the test
            }
            if (line == null) return;
            if (line.startsWith("PING")) {
                client.send("PONG" + line.substring(4));
            } else if (line.contains(" PRIVMSG " + CHANNEL + " ") && line.contains("msg-")) {
                int message = Integer.parseInt(line.substring(line.lastIndexOf("msg-") + 4));
                long now = System.nanoTime();
                latencyNanos[message][member] = now - sentAt.get(message);
                lastDelivery.accumulateAndGet(now, Math::max);
                delivered.incrementAndGet();
                allDelivered.countDown();
            }
        }
    }

    private void report(long startupNanos, long idleMemoryBytes, Duration deliveryWindow)
            throws IOException {
        long[] sorted =
                Arrays.stream(latencyNanos)
                        .flatMapToLong(Arrays::stream)
                        .filter(nanos -> nanos > 0) // the sender (member 0) never receives its own
                        .sorted()
                        .toArray();
        assertThat(sorted).hasSize(MESSAGES * (CLIENTS - 1));
        double p50 = sorted[sorted.length / 2] / 1e6;
        double p99 = sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1e6;
        double max = sorted[sorted.length - 1] / 1e6;
        double throughput = sorted.length / (deliveryWindow.toNanos() / 1e9);

        Properties results = new Properties();
        results.setProperty("image", JircdContainer.image());
        results.setProperty("clients", Integer.toString(CLIENTS));
        results.setProperty("deliveries", Integer.toString(sorted.length));
        results.setProperty("p50Ms", String.format("%.3f", p50));
        results.setProperty("p99Ms", String.format("%.3f", p99));
        results.setProperty("maxMs", String.format("%.3f", max));
        results.setProperty("throughputPerSec", String.format("%.0f", throughput));
        results.setProperty("startupMs", String.format("%.0f", startupNanos / 1e6));
        results.setProperty(
                "idleMemoryMiB", String.format("%.1f", idleMemoryBytes / (1024.0 * 1024.0)));
        System.out.println("ContainerDeliveryLoadTest: " + results);
        System.out.println(
                "SC-005 (accepting within 1000 ms of container start): "
                        + (startupNanos <= 1_000_000_000L ? "met" : "NOT met")
                        + " at "
                        + results.getProperty("startupMs")
                        + " ms");
        String reportPath = System.getProperty("jircd.load.report");
        if (reportPath != null && !reportPath.isBlank()) {
            Path file = Path.of(reportPath);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (var out = Files.newOutputStream(file)) {
                results.store(out, "ContainerDeliveryLoadTest");
            }
        }
        assertThat(max).as("slowest delivery (ms)").isLessThan((double) BUDGET.toMillis());
    }
}
