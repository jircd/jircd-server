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
import java.lang.management.ManagementFactory;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.jircd.core.session.SessionWriter;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #102 SC-005 fan-out measurement over real sockets: 10, 100 and 1,000 recipients with mixed
 * negotiations (none; message-tags + server-time; every capability with CAP 302; multi-prefix +
 * away-notify) receive the same logical messages, AWAY changes, member listings and finite groups.
 * Each phase runs {@link #WARMUP_ROUNDS} discarded rounds and then {@link #MEASURED_ROUNDS}
 * measured ones, and reports the median over the measured rounds of p50/p99/max latency,
 * throughput, and the process's CPU time and allocated bytes, so a baseline and a candidate run of
 * this same class are comparable. A single pass over a 5–30 ms window was too noisy to resolve 10%
 * (see {@code specs/014-capability-plugin-spi/validation/pr-102/performance.md}, "A/A control").
 *
 * <p>Rate limits are raised for every client so no command is dropped; every item must arrive,
 * which rules out silent rate-limit or SendQ loss. Only APIs that exist on main are used, so the
 * identical class runs against the baseline revision.
 */
@Tag("load")
class CapabilityFanoutLoadTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * A phase's warm-up lasts at least this many rounds and this long, whichever is later. The
     * 10-recipient message phase, the first in the JVM, still gained about 50% throughput between
     * its fourth and twelfth round, so a round count alone does not reach steady state.
     */
    static final int WARMUP_ROUNDS = 3;

    static final long WARMUP_NANOS = Duration.ofSeconds(1).toNanos();

    /** Rounds per phase whose median is reported. */
    static final int MEASURED_ROUNDS = 9;

    private static final int GROUP_MEMBERS = 3;

    /**
     * The least time a measured round's timed delivery lasts. A round repeats its batch until the
     * summed delivery time (the time spent preparing and collecting is not counted) reaches this,
     * so one GC pause or timer tick cannot decide it however fast the machine is.
     */
    static final long MIN_WINDOW_NANOS = Duration.ofMillis(60).toNanos();

    /** Batches a round may need to reach {@link #MIN_WINDOW_NANOS}; more fails the test. */
    private static final int MAX_BATCHES = 16;

    /**
     * Items per batch, sized so that on the development machine one batch alone reaches the minimum
     * window; the counts shrink as the recipient count grows, within bounds that keep the sender's
     * own queue (echoes, replies) well under the SendQ limit. Per-item state is kept for {@link
     * #MAX_BATCHES} batches, indexed by sequence number modulo the slot count.
     */
    private record Load(int messages, int aways, int listings, int groups) {
        int messageSlots() {
            return messages * MAX_BATCHES;
        }

        int awaySlots() {
            return aways * MAX_BATCHES;
        }

        int groupSlots() {
            return groups * MAX_BATCHES;
        }

        static Load of(int recipients) {
            return new Load(
                    Math.clamp(80_000 / recipients, 200, 4000),
                    Math.clamp(120_000 / recipients, 100, 12_000),
                    Math.clamp(30_000 / recipients, 50, 600),
                    Math.clamp(16_000 / recipients, 20, 1600));
        }
    }

    private static final String YAML =
            """
      maxConnections: 10000
      maxConnectionsPerIp: 10000
      sendqLimitBytes: 2097152
      rateLimit:
        bucketSize: 1000000
        refillRatePerSecond: 1000000
      capabilities:
        message-tags: enabled
        server-time: enabled
        echo-message: enabled
        away-notify: enabled
        batch: enabled
        multi-prefix: enabled
      """;

    /** The four negotiation mixes, assigned round-robin. */
    private static final List<List<String>> MIXES =
            List.of(
                    List.of(),
                    List.of("CAP REQ :message-tags server-time"),
                    List.of(
                            "CAP LS 302",
                            "CAP REQ :message-tags server-time echo-message away-notify batch multi-prefix"),
                    List.of("CAP REQ :multi-prefix away-notify"));

    private static boolean awayNotify(int mix) {
        return mix == 2 || mix == 3;
    }

    private static boolean framed(int mix) {
        return mix == 2;
    }

    /**
     * Reads one recipient's socket on its own thread, records receipt latencies and checks the
     * exact sequence it receives. The verification state belongs to the reader thread; the test
     * reads it only after joining that thread.
     */
    private static final class Reader {
        final int index;
        final int mix;
        final RawIrcClient client;
        // Written by the reader thread at the item's slot (sequence number modulo the slot count);
        // the test thread reads a round's slots only after the latch of the round's last batch,
        // which orders them, and before a later round reuses them.
        final Load load;
        final long[] messageNanos;
        final long[] awayNanos;
        final long[] groupNanos;
        final AtomicLongArray groupSent;
        final BitSet messagesSeen;
        final BitSet awaysSeen;
        final List<String> violations = new ArrayList<>();

        /** The next message, AWAY and group member expected; anything else is out of order. */
        int nextMessage;

        int nextAway;

        /** As {@code seq * GROUP_MEMBERS + k}. */
        int nextGroupMember;

        boolean inGroup;
        int membersInGroup;
        int opens;
        int closes;
        boolean drained;
        Thread thread;

        Reader(int index, int mix, RawIrcClient client, Load load) {
            this.load = load;
            messageNanos = new long[load.messageSlots()];
            awayNanos = new long[load.awaySlots()];
            groupNanos = new long[load.groupSlots()];
            groupSent = new AtomicLongArray(load.groupSlots());
            messagesSeen = new BitSet();
            awaysSeen = new BitSet();
            this.index = index;
            this.mix = mix;
            this.client = client;
        }
    }

    /** A reader with the writer the server uses to reach it. */
    private record NicknameWriter(Reader reader, SessionWriter writer) {}

    /** Group {@code seq}'s members, one list per recipient. */
    private static List<List<Message>> groupMembers(int seq, int recipients) {
        List<List<Message>> perRecipient = new ArrayList<>();
        for (int w = 0; w < recipients; w++) {
            List<Message> members = new ArrayList<>();
            for (int k = 0; k < GROUP_MEMBERS; k++) {
                members.add(
                        new Message(
                                Map.of(),
                                "bot!bot@host",
                                Command.PRIVMSG,
                                "PRIVMSG",
                                List.of("#grp", "g" + seq + " " + k + " member")));
            }
            perRecipient.add(members);
        }
        return perRecipient;
    }

    /**
     * One round: its latencies in ascending order, the batches it took, and the delivery time and
     * what the process spent over exactly those timed deliveries.
     */
    private record Round(
            long[] sortedNanos, int batches, long wallNanos, long cpuNanos, long bytes) {
        double p50Ms() {
            return FanoutStats.quantileMs(sortedNanos, 0.5);
        }

        double p99Ms() {
            return FanoutStats.quantileMs(sortedNanos, 0.99);
        }

        double maxMs() {
            return sortedNanos[sortedNanos.length - 1] / 1e6;
        }

        double throughputPerSec() {
            return sortedNanos.length / (wallNanos / 1e9);
        }
    }

    /** The warm-up rounds, which are only checked, and the measured rounds, which are reported. */
    private record Phase(String name, List<Round> warmup, List<Round> measured) {
        /** Batches run in the whole phase, which is what the readers must have received. */
        int batches() {
            return Stream.concat(warmup.stream(), measured.stream()).mapToInt(Round::batches).sum();
        }

        double minWindowMs() {
            return measured.stream().mapToDouble(r -> r.wallNanos() / 1e6).min().orElseThrow();
        }

        private double median(ToDoubleFunction<Round> metric) {
            return FanoutStats.median(measured.stream().mapToDouble(metric).toArray());
        }

        private double cv(ToDoubleFunction<Round> metric) {
            return FanoutStats.cvPercent(measured.stream().mapToDouble(metric).toArray());
        }

        /** The slowest single delivery in any round, warm-up included (the delivery budget). */
        double worstMaxMs() {
            return Stream.concat(warmup.stream(), measured.stream())
                    .mapToDouble(Round::maxMs)
                    .max()
                    .orElseThrow();
        }

        void print(int recipients) {
            System.out.printf(
                    "CapabilityFanoutLoadTest: recipients=%d phase=%s items=%d p50Ms=%.3f p99Ms=%.3f"
                            + " maxMs=%.3f throughputPerSec=%.0f cpuMs=%.1f allocMB=%.1f rounds=%d"
                            + " windowMs=%.1f minWindowMs=%.1f worstMaxMs=%.3f roundCvThroughputPct=%.1f roundCvP99Pct=%.1f%n",
                    recipients,
                    name,
                    (int) median(r -> r.sortedNanos().length),
                    median(Round::p50Ms),
                    median(Round::p99Ms),
                    median(Round::maxMs),
                    median(Round::throughputPerSec),
                    median(r -> r.cpuNanos() / 1e6),
                    median(r -> r.bytes() / 1e6),
                    measured.size(),
                    median(r -> r.wallNanos() / 1e6),
                    minWindowMs(),
                    worstMaxMs(),
                    cv(Round::throughputPerSec),
                    cv(Round::p99Ms));
            for (int i = 0; i < warmup.size() + measured.size(); i++) {
                boolean warm = i < warmup.size();
                Round round = warm ? warmup.get(i) : measured.get(i - warmup.size());
                System.out.printf(
                        "CapabilityFanoutLoadTest.round: recipients=%d phase=%s round=%d kind=%s"
                                + " p99Ms=%.3f maxMs=%.3f throughputPerSec=%.0f cpuMs=%.1f"
                                + " allocMB=%.1f%n",
                        recipients,
                        name,
                        i,
                        warm ? "warmup" : "measured",
                        round.p99Ms(),
                        round.maxMs(),
                        round.throughputPerSec(),
                        round.cpuNanos() / 1e6,
                        round.bytes() / 1e6);
            }
        }
    }

    /**
     * Reads until the server's numeric reply {@code code}. {@code readUntil("315")} would stop at
     * the first WHO line that merely contains those digits (a nickname such as {@code r315}), leave
     * the rest of the listing queued, and push the sender past the SendQ limit.
     */
    private static void readThroughReply(RawIrcClient client, String code) throws IOException {
        // The idle timeout alone would never fire on a server that keeps sending other lines.
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            String line = client.readLine();
            if (line == null) throw new IOException("Connection closed while waiting for " + code);
            String[] fields = line.split(" ", 4);
            int numeric = fields[0].startsWith("@") ? 2 : 1;
            if (fields.length > numeric && fields[numeric].equals(code)) return;
        }
        throw new AssertionError("no " + code + " reply within " + TIMEOUT);
    }

    /** Sets one batch up outside the timed window. */
    @FunctionalInterface
    private interface Prepare {
        Timed prepare(int batch) throws Exception;
    }

    /** The timed part of a batch: send it and wait until every recipient has it. */
    @FunctionalInterface
    private interface Timed {
        void run() throws Exception;
    }

    /** Reads a round's per-item latencies, in nanoseconds, after its timed window has ended. */
    @FunctionalInterface
    private interface Collect {
        long[] collect(int firstBatch, int batches);
    }

    /**
     * Runs rounds of whole batches. A round repeats batches until their summed timed delivery
     * reaches {@link #MIN_WINDOW_NANOS}; the wall, CPU and allocation samples cover only those
     * deliveries, not the preparation or the collecting and sorting of latencies.
     */
    private static Phase measure(String name, Prepare prepare, Collect collect) throws Exception {
        List<Round> warmup = new ArrayList<>();
        List<Round> measured = new ArrayList<>();
        long warmupStart = System.nanoTime();
        int batch = 0;
        for (int round = 0; measured.size() < MEASURED_ROUNDS; round++) {
            boolean warm = round < WARMUP_ROUNDS || System.nanoTime() - warmupStart < WARMUP_NANOS;
            int first = batch;
            long wall = 0;
            long cpu = 0;
            long bytes = 0;
            while (wall < MIN_WINDOW_NANOS) {
                if (batch - first == MAX_BATCHES) {
                    throw new AssertionError(
                            name
                                    + ": "
                                    + MAX_BATCHES
                                    + " batches took under "
                                    + MIN_WINDOW_NANOS / 1_000_000
                                    + " ms; raise the item counts");
                }
                Timed timed = prepare.prepare(batch++);
                long cpu0 = cpuNanos();
                long bytes0 = allocatedBytes();
                long start = System.nanoTime();
                timed.run();
                wall += System.nanoTime() - start;
                cpu += cpuNanos() - cpu0;
                bytes += allocatedBytes() - bytes0;
            }
            long[] nanos = collect.collect(first, batch - first);
            Arrays.sort(nanos);
            (warm ? warmup : measured).add(new Round(nanos, batch - first, wall, cpu, bytes));
        }
        return new Phase(name, warmup, measured);
    }

    private static long cpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime();
    }

    private static long allocatedBytes() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
                .getTotalThreadAllocatedBytes();
    }

    // Shared send times, indexed by item sequence number.
    private volatile AtomicLongArray messageSent;
    private volatile AtomicLongArray awaySent;

    /**
     * The latencies of sequence numbers {@code [first, first + count)} across the readers that
     * {@code receive} them, read from their slots after the timed window has ended.
     */
    private static long[] latencies(
            List<Reader> readers,
            Predicate<Reader> receive,
            Function<Reader, long[]> of,
            int first,
            int count) {
        return readers.stream()
                .filter(receive)
                .flatMapToLong(
                        r -> {
                            long[] slots = of.apply(r);
                            return IntStream.range(first, first + count)
                                    .mapToLong(seq -> slots[seq % slots.length]);
                        })
                .toArray();
    }

    /** The sequence number right after {@code marker} in {@code line}, or -1 if it is absent. */
    private static int sequence(String line, String marker) {
        int at = line.indexOf(marker);
        if (at < 0) return -1;
        int from = at + marker.length();
        return Integer.parseInt(line.substring(from, line.indexOf(' ', from)));
    }

    private static final String BARRIER = "fanout-barrier";

    private volatile CountDownLatch messagesPending;
    private volatile CountDownLatch awaysPending;
    private volatile CountDownLatch groupsPending;

    /** Reads until the barrier's PONG, so every line queued before it has been checked. */
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
            if (line.startsWith("PONG ") && line.endsWith(BARRIER)) {
                reader.drained = true;
                return;
            }
            check(reader, line, System.nanoTime());
        }
    }

    private void check(Reader reader, String line, long now) {
        int message = sequence(line, " PRIVMSG #fan :m");
        int away = sequence(line, " AWAY :a");
        if (line.startsWith("BATCH ")) {
            checkFrame(reader, line);
        } else if (line.contains(" PRIVMSG #grp :g")) {
            checkMember(reader, line, now);
        } else if (reader.inGroup) {
            reader.violations.add("inside a group: " + line);
        } else if (message >= 0) {
            if (reader.messagesSeen.get(message))
                reader.violations.add("message twice: " + message);
            if (message != reader.nextMessage) {
                reader.violations.add(
                        "message " + message + " where " + reader.nextMessage + " was due");
            }
            reader.nextMessage = message + 1;
            reader.messagesSeen.set(message);
            int slot = message % reader.load.messageSlots();
            reader.messageNanos[slot] = now - messageSent.get(slot);
            messagesPending.countDown();
        } else if (away >= 0) {
            if (reader.awaysSeen.get(away)) reader.violations.add("AWAY twice: " + away);
            if (away != reader.nextAway) {
                reader.violations.add("AWAY " + away + " where " + reader.nextAway + " was due");
            }
            reader.nextAway = away + 1;
            reader.awaysSeen.set(away);
            int slot = away % reader.load.awaySlots();
            reader.awayNanos[slot] = now - awaySent.get(slot);
            awaysPending.countDown();
        }
    }

    private static void checkFrame(Reader reader, String line) {
        if (line.startsWith("BATCH +")) {
            if (!framed(reader.mix) || reader.inGroup) reader.violations.add("opener: " + line);
            reader.inGroup = true;
            reader.membersInGroup = 0;
            reader.opens++;
        } else {
            if (!reader.inGroup || reader.membersInGroup != GROUP_MEMBERS) {
                reader.violations.add("closer after " + reader.membersInGroup + " members");
            }
            reader.inGroup = false;
            reader.closes++;
        }
    }

    /** Members arrive in order, each exactly once, framed and tagged only for the batch mix. */
    private void checkMember(Reader reader, String line, long now) {
        String[] fields = line.substring(line.indexOf(" PRIVMSG #grp :g") + 16).split(" ");
        int seq = Integer.parseInt(fields[0]);
        int member = Integer.parseInt(fields[1]);
        int position = seq * GROUP_MEMBERS + member;
        if (position != reader.nextGroupMember) {
            reader.violations.add(
                    "member " + position + " where " + reader.nextGroupMember + " was due");
        }
        reader.nextGroupMember = position + 1;
        boolean tagged = line.startsWith("@batch=");
        if (framed(reader.mix) != reader.inGroup || framed(reader.mix) != tagged) {
            reader.violations.add("framing of member " + position + ": " + line);
        }
        reader.membersInGroup++;
        if (member == GROUP_MEMBERS - 1) {
            int slot = seq % reader.load.groupSlots();
            reader.groupNanos[slot] = now - reader.groupSent.get(slot);
            groupsPending.countDown();
        }
    }

    private static void await(CountDownLatch pending, String what) throws InterruptedException {
        assertThat(pending.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("every recipient received every %s within %s", what, TIMEOUT)
                .isTrue();
    }

    /** Never sent by the server, so waiting for it can only end at end of stream or a timeout. */
    private static final String NEVER_SENT = "\u0001fanout-end-of-stream";

    /**
     * Quits each client and requires every one to reach end of stream before one shared deadline.
     * The server releases a session's nickname before it closes the socket, so after this the
     * registry holds none of them, and a connection left open fails the test instead of passing.
     */
    // The clients belong to the caller, which closes them; this only writes to and reads from them.
    @SuppressWarnings("PMD.CloseResource")
    private static void quitAll(List<RawIrcClient> clients) throws java.io.IOException {
        for (RawIrcClient client : clients) client.send("QUIT");
        Instant deadline = Instant.now().plus(TIMEOUT);
        for (RawIrcClient client : clients) awaitEndOfStream(client, deadline);
    }

    private static void awaitEndOfStream(RawIrcClient client, Instant deadline) {
        Duration left = Duration.between(Instant.now(), deadline);
        assertThat(left).as("time left before the shared deadline").isPositive();
        try {
            client.readUntil(NEVER_SENT, left);
        } catch (java.io.IOException e) {
            // RawIrcClient reports end of stream as "Connection closed while waiting for: …".
            if (e.getMessage() != null && e.getMessage().startsWith("Connection closed")) return;
            throw new AssertionError("no end of stream before the deadline: " + e, e);
        }
        throw new AssertionError("the server sent a line it never sends");
    }

    // Every client is closed in the finally block, not in the scope that opened it.
    @SuppressWarnings("PMD.CloseResource")
    @ParameterizedTest(name = "{0} recipients")
    @ValueSource(ints = {10, 100, 1000})
    void mixedFanoutStaysWithinTheDeliveryBudget(int recipients) throws Exception {
        Load load = Load.of(recipients);
        messageSent = new AtomicLongArray(load.messageSlots());
        awaySent = new AtomicLongArray(load.awaySlots());
        List<Reader> readers = new ArrayList<>();
        List<RawIrcClient> clients = new ArrayList<>();
        try (TestServer server = TestServer.start(YAML)) {
            RawIrcClient sender = RawIrcClient.connectPlaintext(server.plaintextPort());
            clients.add(sender);
            sender.send("CAP LS 302");
            sender.send("CAP REQ :message-tags server-time echo-message batch multi-prefix");
            sender.send("CAP END");
            sender.registerAndAwaitWelcome("sender", "sender");
            sender.send("JOIN #fan");
            sender.readUntil("366", TIMEOUT);
            for (int i = 0; i < recipients; i++) {
                int mix = i % MIXES.size();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
                clients.add(client);
                for (String line : MIXES.get(mix)) client.send(line);
                client.send("CAP END");
                client.registerAndAwaitWelcome("r" + i, "r" + i);
                client.send("JOIN #fan");
                client.readUntil("366", TIMEOUT);
                readers.add(new Reader(i, mix, client, load));
            }
            sender.readThroughPong(TIMEOUT);
            List<NicknameWriter> writers = new ArrayList<>();
            for (Reader reader : readers) {
                writers.add(
                        new NicknameWriter(
                                reader,
                                server.application
                                        .nicknameRegistry()
                                        .lookup("r" + reader.index)
                                        .orElseThrow()
                                        .writer()));
            }
            for (Reader reader : readers) {
                reader.thread = Thread.ofVirtual().start(() -> read(reader));
            }

            List<Phase> phases = new ArrayList<>();
            String payload = "x".repeat(100);
            Predicate<Reader> everyone = _ -> true;
            Predicate<Reader> awayNotified = r -> awayNotify(r.mix);
            int awayRecipients = (int) readers.stream().filter(awayNotified).count();

            phases.add(
                    measure(
                            "message",
                            batch -> {
                                // The sender also gets its echoes; empty them before the window.
                                sender.readThroughPong(TIMEOUT);
                                int first = batch * load.messages();
                                messagesPending = new CountDownLatch(recipients * load.messages());
                                return () -> {
                                    for (int seq = first; seq < first + load.messages(); seq++) {
                                        messageSent.set(
                                                seq % load.messageSlots(), System.nanoTime());
                                        sender.send(
                                                "@+fan="
                                                        + seq
                                                        + " PRIVMSG #fan :m"
                                                        + seq
                                                        + " "
                                                        + payload);
                                    }
                                    await(messagesPending, "message");
                                };
                            },
                            (first, batches) ->
                                    latencies(
                                            readers,
                                            everyone,
                                            r -> r.messageNanos,
                                            first * load.messages(),
                                            batches * load.messages())));

            phases.add(
                    measure(
                            "away",
                            batch -> {
                                sender.readThroughPong(TIMEOUT);
                                int first = batch * load.aways();
                                awaysPending = new CountDownLatch(awayRecipients * load.aways());
                                return () -> {
                                    for (int seq = first; seq < first + load.aways(); seq++) {
                                        awaySent.set(seq % load.awaySlots(), System.nanoTime());
                                        sender.send("AWAY :a" + seq + " busy");
                                    }
                                    await(awaysPending, "AWAY");
                                };
                            },
                            (first, batches) ->
                                    latencies(
                                            readers,
                                            awayNotified,
                                            r -> r.awayNanos,
                                            first * load.aways(),
                                            batches * load.aways())));

            // The sender reads its own listings; the readers ignore them.
            Map<Integer, long[]> listingsByBatch = new HashMap<>();
            phases.add(
                    measure(
                            "member",
                            batch -> {
                                sender.readThroughPong(TIMEOUT);
                                return () -> {
                                    long[] listings = new long[2 * load.listings()];
                                    for (int i = 0; i < load.listings(); i++) {
                                        long sent = System.nanoTime();
                                        sender.send("NAMES #fan");
                                        readThroughReply(sender, "366");
                                        listings[2 * i] = System.nanoTime() - sent;
                                        sent = System.nanoTime();
                                        sender.send("WHO #fan");
                                        readThroughReply(sender, "315");
                                        listings[2 * i + 1] = System.nanoTime() - sent;
                                    }
                                    listingsByBatch.put(batch, listings);
                                };
                            },
                            (first, batches) ->
                                    IntStream.range(first, first + batches)
                                            .mapToObj(listingsByBatch::remove)
                                            .flatMapToLong(Arrays::stream)
                                            .toArray()));

            phases.add(
                    measure(
                            "group",
                            batch -> {
                                int first = batch * load.groups();
                                // Build every group before the window so it times delivery only.
                                List<List<List<Message>>> groups = new ArrayList<>();
                                for (int i = 0; i < load.groups(); i++) {
                                    groups.add(groupMembers(first + i, writers.size()));
                                }
                                groupsPending = new CountDownLatch(recipients * load.groups());
                                return () -> {
                                    for (int i = 0; i < load.groups(); i++) {
                                        int slot = (first + i) % load.groupSlots();
                                        for (int w = 0; w < writers.size(); w++) {
                                            NicknameWriter target = writers.get(w);
                                            target.reader().groupSent.set(slot, System.nanoTime());
                                            target.writer()
                                                    .enqueueGroup(
                                                            "fan", List.of(), groups.get(i).get(w));
                                        }
                                    }
                                    await(groupsPending, "group");
                                };
                            },
                            (first, batches) ->
                                    latencies(
                                            readers,
                                            everyone,
                                            r -> r.groupNanos,
                                            first * load.groups(),
                                            batches * load.groups())));

            for (Phase phase : phases) phase.print(recipients);
            for (Phase phase : phases) {
                assertThat(phase.worstMaxMs()).as(phase.name()).isLessThan(1000.0);
            }

            int messages = load.messages() * phases.get(0).batches();
            int aways = load.aways() * phases.get(1).batches();
            int groupItems = load.groups() * phases.get(3).batches();
            // Nothing more is sent; each reader checks everything queued before its barrier.
            for (Reader reader : readers) reader.client.send("PING " + BARRIER);
            for (Reader reader : readers) reader.thread.join(TimeUnit.SECONDS.toMillis(30));
            assertThat(readers)
                    .hasSize(recipients)
                    .allSatisfy(
                            reader -> {
                                int groups = framed(reader.mix) ? groupItems : 0;
                                assertThat(reader.drained).as("r%d drained", reader.index).isTrue();
                                assertThat(reader.violations).as("r%d", reader.index).isEmpty();
                                assertThat(reader.messagesSeen.cardinality()).isEqualTo(messages);
                                assertThat(reader.nextMessage).isEqualTo(messages);
                                assertThat(reader.awaysSeen.cardinality())
                                        .isEqualTo(awayNotify(reader.mix) ? aways : 0);
                                assertThat(reader.nextGroupMember)
                                        .isEqualTo(groupItems * GROUP_MEMBERS);
                                assertThat(reader.opens)
                                        .as("r%d opens", reader.index)
                                        .isEqualTo(groups);
                                assertThat(reader.closes)
                                        .as("r%d closes", reader.index)
                                        .isEqualTo(groups);
                                assertThat(reader.inGroup).isFalse();
                            });

            quitAll(clients);
            assertThat(server.application.nicknameRegistry().all()).isEmpty();
        } finally {
            for (RawIrcClient client : clients) client.close();
            System.out.println(
                    "CapabilityFanoutLoadTest: recipients="
                            + recipients
                            + " mixes="
                            + Arrays.toString(
                                    MIXES.stream().map(m -> String.join("|", m)).toArray()));
        }
    }
}
