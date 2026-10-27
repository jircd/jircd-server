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

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRuntime;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * #102 SC-005 lifecycle measurement: 1,000 live socket sessions, all holding the capabilities being
 * toggled, have their state changed through EXTENSION's path and through REHASH while channel
 * output and finite groups keep flowing. Each publication's stages are reported apart: the
 * administrative-mutex wait, the snapshot swap with the all-session prune (held), the {@code
 * DEL}/{@code NEW} enqueue, then what the whole operation adds around them (provider callbacks and
 * lifecycle bookkeeping; for REHASH also file I/O and parsing), and the socket drain seen by
 * clients. The budget is wait + swap/prune p99 at most 100 ms.
 *
 * <p>Nothing here requires captured output to stop at {@code DEL}. It does require that nothing
 * deadlocks, that every session's negotiated set and every new capture is coherent with the
 * snapshot after each change, and that every group a client sees is whole: framed groups open and
 * close around tagged members only, plain ones carry no framing.
 */
@Tag("load")
class CapabilityLifecycleLoadTest {

    private static final int SESSIONS = 1000;
    private static final int CHANNELS = 10;
    private static final int WARMUP = 5;
    private static final int CYCLES = 40;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final List<String> TOGGLED = List.of("multi-prefix", "message-tags");

    /** The trailing text of each group member, in order. */
    private static final List<String> GROUP_MEMBERS = List.of("one member", "two member");

    private static final String LIMITS =
            """
      maxConnections: 10000
      maxConnectionsPerIp: 10000
      sendqLimitBytes: 2097152
      rateLimit:
        bucketSize: 1000000
        refillRatePerSecond: 1000000
      capabilities:
        batch: enabled
      """;

    /** The configuration with every toggled capability enabled except {@code disabled}. */
    private static String yaml(String disabled) {
        StringBuilder yaml = new StringBuilder(LIMITS);
        for (String name : TOGGLED) {
            yaml.append("  ")
                    .append(name)
                    .append(name.equals(disabled) ? ": disabled\n" : ": enabled\n");
        }
        return yaml.toString();
    }

    /** Reads one session's socket; checks group framing and records ACKs and DEL receipt. */
    private static final class Reader {
        final String nick;
        final RawIrcClient client;
        final AtomicInteger acks = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger violations = new AtomicInteger();
        final AtomicInteger lines = new AtomicInteger();
        boolean inGroup;
        int groupMembers;
        volatile boolean drained;
        Thread thread;

        Reader(String nick, RawIrcClient client) {
            this.nick = nick;
            this.client = client;
        }
    }

    private final Queue<CapabilityRuntime.PublicationTiming> timings =
            new ConcurrentLinkedQueue<>();
    private final Queue<Long> delReceipts = new ConcurrentLinkedQueue<>();
    private final AtomicLong changeStarted = new AtomicLong();

    private static final String FINAL_BARRIER = "final-barrier";

    /** Reads until the final barrier's PONG, so everything queued before it is checked. */
    private void read(Reader reader) {
        while (true) {
            String line;
            try {
                line = reader.client.readLine();
            } catch (SocketTimeoutException _) {
                continue;
            } catch (java.io.IOException _) {
                return;
            }
            if (line == null) return;
            if (line.startsWith("PONG ") && line.endsWith(FINAL_BARRIER)) {
                reader.drained = true;
                return;
            }
            reader.lines.incrementAndGet();
            check(reader, line, System.nanoTime());
        }
    }

    private void check(Reader reader, String line, long now) {
        if (line.startsWith("BATCH +")) {
            if (reader.inGroup) reader.violations.incrementAndGet();
            reader.inGroup = true;
            reader.groupMembers = 0;
            reader.opens.incrementAndGet();
        } else if (line.startsWith("BATCH -")) {
            // An admitted group closes after all of its members, never empty or short.
            if (!reader.inGroup || reader.groupMembers != GROUP_MEMBERS.size()) {
                reader.violations.incrementAndGet();
            }
            reader.inGroup = false;
            reader.closes.incrementAndGet();
        } else if (reader.inGroup) {
            // Nothing else may land inside an admitted group; its members are all tagged, in order.
            int member = reader.groupMembers++;
            if (!line.startsWith("@batch=")
                    || member >= GROUP_MEMBERS.size()
                    || !line.endsWith(" PRIVMSG #grp :" + GROUP_MEMBERS.get(member))) {
                reader.violations.incrementAndGet();
            }
        } else if (line.contains(" CAP " + reader.nick + " ACK ")) {
            reader.acks.incrementAndGet();
            acksPending.get().countDown();
        } else if (line.contains(" CAP " + reader.nick + " DEL ")) {
            delReceipts.add(now - changeStarted.get());
        }
    }

    /** Refuses an empty sample, so a measurement that never happened cannot pass a budget. */
    private static double p99Ms(Collection<Long> nanos) {
        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        assertThat(sorted).as("samples").isNotEmpty();
        return sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1e6;
    }

    private static double maxMs(Collection<Long> nanos) {
        return nanos.stream().mapToLong(Long::longValue).max().orElse(0) / 1e6;
    }

    private static void print(String stage, Collection<Long> nanos) {
        System.out.printf(
                "CapabilityLifecycleLoadTest: sessions=%d stage=%s samples=%d p99Ms=%.3f maxMs=%.3f%n",
                SESSIONS, stage, nanos.size(), p99Ms(nanos), maxMs(nanos));
    }

    /** Every live session and a fresh capture agree with the published offers. */
    private static void assertCoherent(ExtensionRegistry registry, Collection<ClientSession> live) {
        AtomicReference<String> incoherent = new AtomicReference<>();
        registry.capabilityControl()
                .runUnderControl(
                        () -> {
                            var offered = registry.capabilitySnapshot().offeredNames();
                            for (ClientSession session : live) {
                                for (String name : session.negotiatedCapabilities()) {
                                    if (!offered.contains(name))
                                        incoherent.set(session.nickname() + " holds " + name);
                                }
                            }
                        });
        assertThat(incoherent.get()).isNull();
        for (ClientSession session : live) {
            CapabilityRuntime.CapturedView view = registry.capabilityControl().capture(session);
            assertThat(view.snapshot().offeredNames()).containsAll(view.negotiated());
        }
    }

    /** Counted down once per session's ACK of the current cycle's request. */
    private final AtomicReference<CountDownLatch> acksPending =
            new AtomicReference<>(new CountDownLatch(0));

    /** Times one whole change; what it costs beyond the publication stages is reported apart. */
    private long timed(Runnable change) {
        changeStarted.set(System.nanoTime());
        long start = System.nanoTime();
        change.run();
        return System.nanoTime() - start;
    }

    // Every client is closed in the finally block, not in the scope that opened it.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void publicationAtOneThousandBusySessionsStaysWithinTheBudgetAndCoherent() throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        List<RawIrcClient> clients = new ArrayList<>();
        List<Reader> readers = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        try (TestServer server = TestServer.start(yaml(""))) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            for (int i = 0; i < SESSIONS; i++) {
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
                clients.add(client);
                // Half are CAP 302 clients, so every DEL/NEW goes to 500 eligible writers.
                if (i % 2 == 0) client.send("CAP LS 302");
                client.send("CAP REQ :multi-prefix message-tags batch");
                client.send("CAP END");
                client.registerAndAwaitWelcome("s" + i, "s" + i);
                client.send("JOIN #l" + (i % CHANNELS));
                client.readUntil("366", TIMEOUT);
                readers.add(new Reader("s" + i, client));
            }
            for (Reader reader : readers) {
                reader.thread = Thread.ofVirtual().start(() -> read(reader));
            }
            Collection<ClientSession> live = server.application.nicknameRegistry().all();

            // Output work: one member of each channel keeps talking.
            for (int c = 0; c < CHANNELS; c++) {
                RawIrcClient talker = clients.get(c);
                int channel = c;
                workers.add(
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            try {
                                                for (long n = 0; !done.get(); n++) {
                                                    talker.send(
                                                            "@+n="
                                                                    + n
                                                                    + " PRIVMSG #l"
                                                                    + channel
                                                                    + " :output "
                                                                    + n);
                                                    LockSupport.parkNanos(
                                                            TimeUnit.MILLISECONDS.toNanos(
                                                                    2)); // pacing
                                                }
                                            } catch (Exception e) {
                                                if (!done.get()) workerFailure.set(e);
                                            }
                                        }));
            }
            // Group work: finite groups to random sessions, framed or plain at admission.
            workers.add(
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        List<ClientSession> sessions = List.copyOf(live);
                                        List<Message> members =
                                                GROUP_MEMBERS.stream()
                                                        .map(
                                                                text ->
                                                                        new Message(
                                                                                Map.of(),
                                                                                "bot!bot@host",
                                                                                Command.PRIVMSG,
                                                                                "PRIVMSG",
                                                                                List.of(
                                                                                        "#grp",
                                                                                        text)))
                                                        .toList();
                                        try {
                                            while (!done.get()) {
                                                ClientSession target =
                                                        sessions.get(
                                                                ThreadLocalRandom.current()
                                                                        .nextInt(sessions.size()));
                                                target.writer()
                                                        .enqueueGroup("load", List.of(), members);
                                                LockSupport.parkNanos(200_000); // pacing
                                            }
                                        } catch (Exception e) {
                                            if (!done.get()) workerFailure.set(e);
                                        }
                                    }));

            registry.observeCapabilityPublications(timings::add);
            List<Long> extensionChanges = new ArrayList<>();
            List<Long> rehashChanges = new ArrayList<>();
            for (int cycle = 0; cycle < WARMUP + CYCLES; cycle++) {
                if (cycle == WARMUP) {
                    timings.clear();
                    delReceipts.clear();
                }
                String name = TOGGLED.get(cycle % TOGGLED.size());
                boolean rehash = isRehashCycle(cycle);
                List<Long> changes = rehash ? rehashChanges : extensionChanges;
                long disable =
                        timed(
                                () -> {
                                    if (rehash) reload(server, yaml(name));
                                    else registry.disable(name);
                                });
                assertCoherent(registry, live);
                long enable =
                        timed(
                                () -> {
                                    if (rehash) reload(server, yaml(""));
                                    else registry.enable(name);
                                });
                assertCoherent(registry, live);
                if (cycle >= WARMUP) {
                    changes.add(disable);
                    changes.add(enable);
                }
                // Every session asks again, so each disable prunes all 1,000.
                CountDownLatch acks = new CountDownLatch(SESSIONS);
                acksPending.set(acks);
                for (RawIrcClient client : clients) client.send("CAP REQ :" + name);
                assertThat(acks.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                        .as("every session answered")
                        .isTrue();
                assertThat(workerFailure.get()).isNull();
            }

            List<Long> wait = timings.stream().map(t -> t.waitNanos()).toList();
            List<Long> held = timings.stream().map(t -> t.publishNanos()).toList();
            List<Long> budget =
                    timings.stream().map(t -> t.waitNanos() + t.publishNanos()).toList();
            List<Long> notify = timings.stream().map(t -> t.notifyNanos()).toList();
            print("mutex-wait", wait);
            print("swap+prune (held)", held);
            print("wait+swap+prune (budget)", budget);
            print("del/new-enqueue", notify);
            print("extension-change-total", extensionChanges);
            print("rehash-change-total (file I/O, parse, callbacks)", rehashChanges);
            print("del-receipt-after-change-start (includes enqueue and drain)", delReceipts);
            // Every measured cycle publishes twice (disable, enable), whichever path it takes;
            // every disable notifies the 500 CAP 302 sessions.
            int rehashCycles =
                    (int)
                            java.util.stream.IntStream.range(WARMUP, WARMUP + CYCLES)
                                    .filter(CapabilityLifecycleLoadTest::isRehashCycle)
                                    .count();
            assertThat(timings).hasSize(2 * CYCLES).allMatch(t -> t.sessions() == SESSIONS);
            assertThat(rehashChanges).hasSize(2 * rehashCycles);
            assertThat(extensionChanges).hasSize(2 * (CYCLES - rehashCycles));
            assertThat(delReceipts).hasSize(CYCLES * SESSIONS / 2);
            assertThat(p99Ms(budget)).isLessThanOrEqualTo(100.0);

            done.set(true);
            for (Thread worker : workers) worker.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(workers).noneMatch(Thread::isAlive);
            assertThat(workerFailure.get()).isNull();
            // Output has stopped; each reader checks everything queued before its barrier.
            for (RawIrcClient client : clients) client.send("PING " + FINAL_BARRIER);
            for (Reader reader : readers) reader.thread.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(readers).allMatch(r -> r.drained);
        } finally {
            done.set(true);
            for (RawIrcClient client : clients) client.close();
            for (Reader reader : readers) {
                if (reader.thread != null) reader.thread.join(TimeUnit.SECONDS.toMillis(10));
            }
        }
        long opens = readers.stream().mapToLong(r -> r.opens.get()).sum();
        System.out.printf(
                "CapabilityLifecycleLoadTest: framedGroupsSeen=%d linesSeen=%d%n",
                opens, readers.stream().mapToLong(r -> r.lines.get()).sum());
        assertThat(readers)
                .hasSize(SESSIONS)
                .allSatisfy(
                        r -> {
                            assertThat(r.violations).hasValue(0);
                            assertThat(r.opens.get()).isEqualTo(r.closes.get());
                        });
        assertThat(opens).isPositive();
    }

    private static boolean isRehashCycle(int cycle) {
        return cycle % 4 == 3;
    }

    private static void reload(TestServer server, String yaml) {
        try {
            Files.writeString(server.configPath, TestServer.baseYaml() + yaml);
            server.application.reloader().reload();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
