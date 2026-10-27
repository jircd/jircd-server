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

import java.lang.management.ManagementFactory;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #102 SC-005 fan-out measurement over real sockets: 10, 100 and 1,000 recipients with mixed
 * negotiations (none; message-tags + server-time; every capability with CAP 302; multi-prefix +
 * away-notify) receive the same logical messages, AWAY changes, member listings and finite groups.
 * Each phase reports p50/p99/max latency, throughput, and the process's CPU time and allocated
 * bytes, so a baseline and a candidate run of this same class are comparable.
 *
 * <p>Rate limits are raised for every client so no command is dropped; every item must arrive,
 * which rules out silent rate-limit or SendQ loss. Only APIs that exist on main are used, so the
 * identical class runs against the baseline revision.
 */
@Tag("load")
class CapabilityFanoutLoadTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);
  private static final int MESSAGES = 200;
  private static final int AWAYS = 50;
  private static final int LISTINGS = 10;
  private static final int GROUPS = 20;
  private static final int GROUP_MEMBERS = 3;

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
   * Reads one recipient's socket on its own thread, records receipt latencies and checks the exact
   * sequence it receives. The verification state belongs to the reader thread; the test reads it
   * only after joining that thread.
   */
  private static final class Reader {
    final int index;
    final int mix;
    final RawIrcClient client;
    final Queue<Long> messageNanos = new ConcurrentLinkedQueue<>();
    final Queue<Long> awayNanos = new ConcurrentLinkedQueue<>();
    final Queue<Long> groupNanos = new ConcurrentLinkedQueue<>();
    final AtomicLongArray groupSent = new AtomicLongArray(GROUPS);
    final BitSet messagesSeen = new BitSet(MESSAGES);
    final BitSet awaysSeen = new BitSet(AWAYS);
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

    Reader(int index, int mix, RawIrcClient client) {
      this.index = index;
      this.mix = mix;
      this.client = client;
    }
  }

  private record Phase(String name, long[] sortedNanos, long wallNanos, long cpuNanos, long bytes) {
    double ms(int index) {
      return sortedNanos[index] / 1e6;
    }

    double p99Ms() {
      return ms((int) Math.ceil(sortedNanos.length * 0.99) - 1);
    }

    double maxMs() {
      return ms(sortedNanos.length - 1);
    }

    void print(int recipients) {
      System.out.printf(
          "CapabilityFanoutLoadTest: recipients=%d phase=%s items=%d p50Ms=%.3f p99Ms=%.3f"
              + " maxMs=%.3f throughputPerSec=%.0f cpuMs=%.1f allocMB=%.1f%n",
          recipients,
          name,
          sortedNanos.length,
          ms(sortedNanos.length / 2),
          p99Ms(),
          maxMs(),
          sortedNanos.length / (wallNanos / 1e9),
          cpuNanos / 1e6,
          bytes / 1e6);
    }
  }

  private static long cpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }

  private static long allocatedBytes() {
    return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean())
        .getTotalThreadAllocatedBytes();
  }

  // Shared send times, indexed by item sequence number.
  private final AtomicLongArray messageSent = new AtomicLongArray(MESSAGES);
  private final AtomicLongArray awaySent = new AtomicLongArray(AWAYS);

  private static long[] sorted(
      List<Reader> readers, java.util.function.Function<Reader, Queue<Long>> of) {
    return readers.stream()
        .flatMap(r -> of.apply(r).stream())
        .mapToLong(Long::longValue)
        .sorted()
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
      if (reader.messagesSeen.get(message)) reader.violations.add("message twice: " + message);
      if (message != reader.nextMessage) {
        reader.violations.add("message " + message + " where " + reader.nextMessage + " was due");
      }
      reader.nextMessage = message + 1;
      reader.messagesSeen.set(message);
      reader.messageNanos.add(now - messageSent.get(message));
      messagesPending.countDown();
    } else if (away >= 0) {
      if (reader.awaysSeen.get(away)) reader.violations.add("AWAY twice: " + away);
      if (away != reader.nextAway) {
        reader.violations.add("AWAY " + away + " where " + reader.nextAway + " was due");
      }
      reader.nextAway = away + 1;
      reader.awaysSeen.set(away);
      reader.awayNanos.add(now - awaySent.get(away));
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
      reader.violations.add("member " + position + " where " + reader.nextGroupMember + " was due");
    }
    reader.nextGroupMember = position + 1;
    boolean tagged = line.startsWith("@batch=");
    if (framed(reader.mix) != reader.inGroup || framed(reader.mix) != tagged) {
      reader.violations.add("framing of member " + position + ": " + line);
    }
    reader.membersInGroup++;
    if (member == GROUP_MEMBERS - 1) {
      reader.groupNanos.add(now - reader.groupSent.get(seq));
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
   * Quits each client and requires every one to reach end of stream before one shared deadline. The
   * server releases a session's nickname before it closes the socket, so after this the registry
   * holds none of them, and a connection left open fails the test instead of passing.
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
        readers.add(new Reader(i, mix, client));
      }
      sender.readThroughPong(TIMEOUT);
      long awayRecipients = readers.stream().filter(r -> awayNotify(r.mix)).count();
      messagesPending = new CountDownLatch(recipients * MESSAGES);
      awaysPending = new CountDownLatch((int) awayRecipients * AWAYS);
      groupsPending = new CountDownLatch(recipients * GROUPS);
      for (Reader reader : readers) {
        reader.thread = Thread.ofVirtual().start(() -> read(reader));
      }

      List<Phase> phases = new ArrayList<>();
      String payload = "x".repeat(100);

      long cpu = cpuNanos();
      long bytes = allocatedBytes();
      long start = System.nanoTime();
      for (int seq = 0; seq < MESSAGES; seq++) {
        messageSent.set(seq, System.nanoTime());
        sender.send("@+fan=" + seq + " PRIVMSG #fan :m" + seq + " " + payload);
      }
      await(messagesPending, "message");
      phases.add(
          new Phase(
              "message",
              sorted(readers, r -> r.messageNanos),
              System.nanoTime() - start,
              cpuNanos() - cpu,
              allocatedBytes() - bytes));

      cpu = cpuNanos();
      bytes = allocatedBytes();
      start = System.nanoTime();
      for (int seq = 0; seq < AWAYS; seq++) {
        awaySent.set(seq, System.nanoTime());
        sender.send("AWAY :a" + seq + " busy");
      }
      await(awaysPending, "AWAY");
      phases.add(
          new Phase(
              "away",
              sorted(readers, r -> r.awayNanos),
              System.nanoTime() - start,
              cpuNanos() - cpu,
              allocatedBytes() - bytes));

      // The sender reads its own listings; the readers ignore them.
      sender.readThroughPong(TIMEOUT);
      List<Long> listings = new ArrayList<>();
      cpu = cpuNanos();
      bytes = allocatedBytes();
      start = System.nanoTime();
      for (int i = 0; i < LISTINGS; i++) {
        long sent = System.nanoTime();
        sender.send("NAMES #fan");
        sender.readUntil("366", TIMEOUT);
        listings.add(System.nanoTime() - sent);
        sent = System.nanoTime();
        sender.send("WHO #fan");
        sender.readUntil("315", TIMEOUT);
        listings.add(System.nanoTime() - sent);
      }
      phases.add(
          new Phase(
              "member",
              listings.stream().mapToLong(Long::longValue).sorted().toArray(),
              System.nanoTime() - start,
              cpuNanos() - cpu,
              allocatedBytes() - bytes));

      cpu = cpuNanos();
      bytes = allocatedBytes();
      start = System.nanoTime();
      for (int seq = 0; seq < GROUPS; seq++) {
        for (Reader reader : readers) {
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
          reader.groupSent.set(seq, System.nanoTime());
          server
              .application
              .nicknameRegistry()
              .lookup("r" + reader.index)
              .orElseThrow()
              .writer()
              .enqueueGroup("fan", List.of(), members);
        }
      }
      await(groupsPending, "group");
      phases.add(
          new Phase(
              "group",
              sorted(readers, r -> r.groupNanos),
              System.nanoTime() - start,
              cpuNanos() - cpu,
              allocatedBytes() - bytes));

      for (Phase phase : phases) phase.print(recipients);
      for (Phase phase : phases) {
        assertThat(phase.maxMs()).as(phase.name()).isLessThan(1000.0);
      }

      // Nothing more is sent; each reader checks everything queued before its barrier.
      for (Reader reader : readers) reader.client.send("PING " + BARRIER);
      for (Reader reader : readers) reader.thread.join(TimeUnit.SECONDS.toMillis(30));
      assertThat(readers)
          .hasSize(recipients)
          .allSatisfy(
              reader -> {
                int groups = framed(reader.mix) ? GROUPS : 0;
                assertThat(reader.drained).as("r%d drained", reader.index).isTrue();
                assertThat(reader.violations).as("r%d", reader.index).isEmpty();
                assertThat(reader.messagesSeen.cardinality()).isEqualTo(MESSAGES);
                assertThat(reader.nextMessage).isEqualTo(MESSAGES);
                assertThat(reader.awaysSeen.cardinality())
                    .isEqualTo(awayNotify(reader.mix) ? AWAYS : 0);
                assertThat(reader.nextGroupMember).isEqualTo(GROUPS * GROUP_MEMBERS);
                assertThat(reader.opens).as("r%d opens", reader.index).isEqualTo(groups);
                assertThat(reader.closes).as("r%d closes", reader.index).isEqualTo(groups);
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
              + Arrays.toString(MIXES.stream().map(m -> String.join("|", m)).toArray()));
    }
  }
}
