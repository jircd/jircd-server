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
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A JOIN storm on one large channel must not stall channel traffic on another one (#48). {@link
 * #STORM_CLIENTS} clients JOIN {@code #storm} at the same instant while a pair of clients exchanges
 * PRIVMSGs on {@code #quiet} at a steady cadence; the test bounds both the time the whole storm
 * takes to complete (every client has its own 366 end-of-NAMES) and the {@code #quiet} delivery
 * latency seen during it. {@code ChannelRegistry.lookup}, which every channel PRIVMSG goes through,
 * shares the server-wide registry monitor with JOIN, so a slow JOIN path shows up here as {@code
 * #quiet} latency.
 *
 * <p>Measured on {@code main} at 08bb476 (developer laptop, loopback, 2,000 clients in waves of
 * {@value #WAVE_SIZE}, 12 runs): storm completion 2.1-2.4 s; {@code #quiet} p50 16-33 ms, p99 and
 * max 55-137 ms. Only about 18 messages fit in a storm at the 110 ms cadence, so p99 is the max.
 * Budgets: storm under 15 s, p99 under 500 ms, max under 1 s (SC-002). Feature 012 (#19) moved the
 * JOIN NAMES listing, recipient copy and hostname-display calls out of the registry monitor; on
 * {@code main} at f59a68f (7 runs) the medians are storm 2.38 s and {@code #quiet} p50 18 ms, with
 * p99 and max 50-126 ms, so the registry lock is not the bottleneck at this size and {@code lookup}
 * needs no lock-free index. The same server is not expected to survive one wave of 250 or more:
 * each member's outbound queue holds 256 lines and overflow closes the connection, and every JOIN
 * is broadcast to every member, so bigger waves evicted slow readers (observed at 250 and at 1,000
 * clients).
 */
@Tag("load")
class JoinStormLoadTest {

  /**
   * Each storm client is one loopback socket plus one virtual thread; 2,000 sits well within the
   * file-descriptor limit of a default CI runner and double {@link
   * ConcurrentConnectionScaleLoadTest}'s 1,000 connections.
   */
  private static final int STORM_CLIENTS = 2000;

  /**
   * How many clients JOIN at the same instant; the next wave starts once the previous one has its
   * 366. Each member's outbound queue holds 256 lines and overflow closes the connection ({@code
   * SessionWriter}), and every JOIN is broadcast to every member, so a wave must stay under that or
   * the server evicts the slow readers and the storm never completes.
   */
  private static final int WAVE_SIZE = 100;

  /**
   * How many clients connect and register at once. The server binds its listener with the JDK's
   * default accept backlog (50, further capped by the OS: 128 on macOS), so 2,000 simultaneous
   * connects overflow it, the kernel drops SYNs, and some connects fail with {@code
   * ConnectException: Operation timed out} (1 failure in 7 runs, 222 of 2,000 clients). Staying
   * well under the backlog lets the accept loop keep up.
   */
  private static final int CONNECT_CONCURRENCY = 25;

  /** Messages sent before the storm, to warm the JIT and the connection, and not measured. */
  private static final int WARMUP_MESSAGES = 10;

  /**
   * Paced below the per-connection rate limit (FR-016, 20-token bucket refilling at 10/sec —
   * Story5BanCapTest) so the sender is never throttled.
   */
  private static final Duration SEND_CADENCE = Duration.ofMillis(110);

  private static final Duration STEP_TIMEOUT = Duration.ofSeconds(30);

  /** About 6x the slowest measured storm (see the class comment). */
  private static final Duration STORM_BOUND = Duration.ofSeconds(15);

  /** About 3.5x the worst measured p99. */
  private static final Duration P99_LATENCY_BOUND = Duration.ofMillis(500);

  /** SC-002's 1-second channel delivery budget, about 7x the worst measured maximum. */
  private static final Duration MAX_LATENCY_BOUND = Duration.ofSeconds(1);

  // ClientPool owns these borrowed clients and closes every socket before workers terminate.
  @SuppressWarnings("PMD.CloseResource")
  @Test
  void joinStormDoesNotStallTrafficOnAnotherChannel() throws Exception {
    AtomicBoolean stormOver = new AtomicBoolean();
    CompletableFuture<Void> failure = new CompletableFuture<>();
    ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    try (threads;
        ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
        TestServer server = TestServer.start();
        ClientPool clients = new ClientPool()) {
      try {
        RawIrcClient alice = clients.connect(server.plaintextPort());
        RawIrcClient bob = clients.connect(server.plaintextPort());
        alice.registerAndAwaitWelcome("alice", "alice");
        bob.registerAndAwaitWelcome("bob", "bob");
        alice.send("JOIN #quiet");
        alice.readUntil(" 366 ", Duration.ofSeconds(5));
        bob.send("JOIN #quiet");
        bob.readUntil(" 366 ", Duration.ofSeconds(5));
        List<RawIrcClient> stormClients =
            connectStormClients(server.plaintextPort(), clients, threads, failure);

        for (int i = 0; i < WARMUP_MESSAGES; i++) {
          alice.send("PRIVMSG #quiet :warmup " + i);
          bob.readUntil("warmup " + i, Duration.ofSeconds(2));
          clock.schedule(() -> {}, SEND_CADENCE.toNanos(), TimeUnit.NANOSECONDS).get();
        }

        CompletableFuture<List<Sample>> measured =
            watch(
                CompletableFuture.supplyAsync(
                    () -> {
                      try {
                        return measureLatencies(bob);
                      } catch (IOException e) {
                        throw new CompletionException(e);
                      }
                    },
                    threads),
                failure);
        StormWindow window = new StormWindow();
        Future<?> sender =
            clock.scheduleWithFixedDelay(
                () -> {
                  try {
                    window.send(alice);
                  } catch (IOException e) {
                    failure.completeExceptionally(e);
                  }
                },
                0,
                SEND_CADENCE.toNanos(),
                TimeUnit.NANOSECONDS);
        List<CompletableFuture<Void>> stormTasks = new ArrayList<>();
        for (int from = 0; from < STORM_CLIENTS; from += WAVE_SIZE) {
          CountDownLatch startGate = new CountDownLatch(1);
          List<CompletableFuture<Void>> joined = new ArrayList<>();
          int to = Math.min(from + WAVE_SIZE, STORM_CLIENTS);
          for (RawIrcClient client : stormClients.subList(from, to)) {
            CompletableFuture<Void> clientJoined = new CompletableFuture<>();
            joined.add(clientJoined);
            stormTasks.add(
                watch(
                    CompletableFuture.runAsync(
                        () -> {
                          try {
                            startGate.await();
                            client.send("JOIN #storm");
                            awaitEndOfNames(client);
                            clientJoined.complete(null);
                            drainUntil(client, stormOver);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new CompletionException(e);
                          } catch (IOException e) {
                            throw new CompletionException(e);
                          }
                        },
                        threads),
                    failure));
          }
          startGate.countDown();
          CompletableFuture.anyOf(
                  CompletableFuture.allOf(joined.toArray(CompletableFuture[]::new)), failure)
              .get(window.remainingNanos(), TimeUnit.NANOSECONDS);
        }
        long stormEnd = window.stop();
        sender.cancel(false);
        Duration stormDuration = Duration.ofNanos(stormEnd - window.start);
        alice.send("PRIVMSG #quiet :lat END 0");
        CompletableFuture.anyOf(measured, failure).get(STEP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        List<Sample> samples = measured.get();
        // Close only after checking failures, so an unexpected disconnect cannot become teardown.
        if (failure.isDone()) {
          failure.get();
        }
        stormOver.set(true);
        clients.close();
        CompletableFuture.allOf(stormTasks.toArray(CompletableFuture[]::new))
            .get(STEP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        long[] sorted =
            samples.stream()
                .filter(sample -> sample.sentAt() >= window.start && sample.sentAt() < stormEnd)
                .mapToLong(Sample::latencyNanos)
                .sorted()
                .toArray();
        assertThat(sorted).as("#quiet messages measured during the storm").isNotEmpty();
        Duration p99 = Duration.ofNanos(sorted[(int) Math.ceil(sorted.length * 0.99) - 1]);
        Duration max = Duration.ofNanos(sorted[sorted.length - 1]);
        System.out.printf(
            "JoinStormLoadTest: clients=%d stormMs=%d quietMessages=%d p50Ms=%.1f p99Ms=%.1f"
                + " maxMs=%.1f%n",
            STORM_CLIENTS,
            stormDuration.toMillis(),
            sorted.length,
            sorted[sorted.length / 2] / 1e6,
            p99.toNanos() / 1e6,
            max.toNanos() / 1e6);
        assertThat(stormDuration).as("total storm completion").isLessThan(STORM_BOUND);
        assertThat(p99).as("#quiet p99 delivery latency").isLessThan(P99_LATENCY_BOUND);
        assertThat(max).as("#quiet max delivery latency").isLessThan(MAX_LATENCY_BOUND);
      } finally {
        stormOver.set(true);
        // Interrupt pending connects and socket reads before resource cleanup waits for workers.
        threads.shutdownNow();
      }
    }
  }

  private static <T> CompletableFuture<T> watch(
      CompletableFuture<T> task, CompletableFuture<Void> failure) {
    task.whenComplete(
        (_, error) -> {
          if (error != null) {
            failure.completeExceptionally(error);
          }
        });
    return task;
  }

  private static List<RawIrcClient> connectStormClients(
      int port, ClientPool clients, ExecutorService threads, CompletableFuture<Void> failure)
      throws Exception {
    Semaphore connectSlots = new Semaphore(CONNECT_CONCURRENCY);
    List<CompletableFuture<RawIrcClient>> connecting = new ArrayList<>();
    for (int i = 0; i < STORM_CLIENTS; i++) {
      int index = i;
      connecting.add(
          watch(
              CompletableFuture.supplyAsync(
                  () -> {
                    try {
                      connectSlots.acquire();
                      try {
                        RawIrcClient client = clients.connect(port);
                        client.registerAndAwaitWelcome("storm" + index, "storm" + index);
                        return client;
                      } finally {
                        connectSlots.release();
                      }
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new CompletionException(e);
                    } catch (IOException e) {
                      throw new CompletionException(e);
                    }
                  },
                  threads),
              failure));
    }
    try {
      CompletableFuture.anyOf(
              CompletableFuture.allOf(connecting.toArray(CompletableFuture[]::new)), failure)
          .get(STEP_TIMEOUT.toSeconds() * 2, TimeUnit.SECONDS);
      return connecting.stream().map(CompletableFuture::join).toList();
    } finally {
      connecting.forEach(task -> task.cancel(true));
    }
  }

  static void awaitEndOfNames(RawIrcClient client) throws IOException {
    long deadline = System.nanoTime() + STEP_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      String line =
          client.readUntil(" ", Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
      if (line.matches("^(?:@[^ ]+ )?(?::[^ ]+ )?366(?: .*|$)")) {
        return;
      }
    }
    throw new SocketTimeoutException("Timed out waiting for end-of-NAMES command 366");
  }

  /** Drain JOIN broadcasts until explicit teardown; an earlier disconnect fails the storm. */
  static void drainUntil(RawIrcClient client, AtomicBoolean stormOver) throws IOException {
    while (!stormOver.get()) {
      try {
        if (client.readLine() == null) {
          throw new IOException("Storm client disconnected before teardown");
        }
      } catch (SocketTimeoutException _) {
        // A read timeout is not EOF; keep draining until the test closes the socket.
      } catch (IOException e) {
        if (!stormOver.get()) {
          throw e;
        }
      }
    }
  }

  private static List<Sample> measureLatencies(RawIrcClient bob) throws IOException {
    List<Sample> latencies = new ArrayList<>();
    while (true) {
      String line = bob.readUntil(":lat ", STEP_TIMEOUT);
      long receivedAt = System.nanoTime();
      String[] payload = line.substring(line.indexOf(":lat ") + 5).split(" ");
      if ("END".equals(payload[0])) {
        return latencies;
      }
      long sentAt = Long.parseLong(payload[1]);
      latencies.add(new Sample(sentAt, receivedAt - sentAt));
    }
  }

  private record Sample(long sentAt, long latencyNanos) {}

  private static final class StormWindow {
    private final long start = System.nanoTime();
    private boolean stopped;
    private int sequence;

    synchronized void send(RawIrcClient alice) throws IOException {
      if (!stopped) {
        alice.send("PRIVMSG #quiet :lat " + sequence++ + " " + System.nanoTime());
      }
    }

    synchronized long stop() {
      stopped = true;
      return System.nanoTime();
    }

    long remainingNanos() {
      return Math.max(1, STORM_BOUND.toNanos() - (System.nanoTime() - start));
    }
  }

  /** Own sockets immediately, including registrations still running when the test fails. */
  static final class ClientPool implements AutoCloseable {
    private final List<RawIrcClient> clients = new ArrayList<>();
    private boolean closed;

    // Ownership transfers to this pool, or the late client is closed immediately.
    @SuppressWarnings("PMD.CloseResource")
    RawIrcClient connect(int port) throws IOException {
      RawIrcClient client = RawIrcClient.connectPlaintext(port);
      synchronized (this) {
        if (closed) {
          client.close();
          throw new IOException("Client pool already closed");
        }
        clients.add(client);
      }
      return client;
    }

    // PMD does not credit this loop closing every owned entry while collecting close failures.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public synchronized void close() throws IOException {
      closed = true;
      IOException failure = null;
      for (RawIrcClient client : clients) {
        try {
          client.close();
        } catch (IOException e) {
          if (failure == null) {
            failure = e;
          } else {
            failure.addSuppressed(e);
          }
        }
      }
      clients.clear();
      if (failure != null) {
        throw failure;
      }
    }
  }
}
