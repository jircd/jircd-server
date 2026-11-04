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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.ConnectionHandler;
import net.jircd.server.JircdServerApplication;
import net.jircd.server.JircdServerTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Graceful shutdown (spec 016 FR-009, SC-003), in process: every connected client is told why
 * before its connection ends, new connections are refused, and the whole thing fits its budget.
 */
class GracefulShutdownTest {

    private static final String REASON = "Server shutting down";
    private static final String EXPECTED_ERROR =
            "ERROR :Closing Link: " + TestServer.LOOPBACK + " (" + REASON + ")";
    private static final Duration PRODUCTION_BUDGET = Duration.ofSeconds(8);
    private static final String MANY_CLIENTS = "maxConnectionsPerIp: 1000\n";

    @TempDir Path dir;

    /** Reads every line until end of stream; the last one is what the server said last. */
    private static CompletableFuture<List<String>> readToEnd(RawIrcClient client) {
        CompletableFuture<List<String>> lines = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            List<String> seen = new ArrayList<>();
                            try {
                                String line;
                                while ((line = client.readLine()) != null) seen.add(line);
                            } catch (IOException _) {
                                // A reset after the last line still ends the stream.
                            }
                            lines.complete(seen);
                        });
        return lines;
    }

    // Every client is closed in the finally block, not in the scope that opened it.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void everyClientIsToldWhyBeforeItsConnectionEnds() throws Exception {
        List<RawIrcClient> clients = new ArrayList<>();
        List<CompletableFuture<List<String>>> received = new ArrayList<>();
        try (TestServer server = TestServer.start(MANY_CLIENTS)) {
            for (int i = 0; i < 100; i++) {
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
                clients.add(client);
                client.registerAndAwaitWelcome("g" + i, "g" + i);
            }
            for (int i = 0; i < 3; i++) {
                RawIrcClient unregistered = RawIrcClient.connectPlaintext(server.plaintextPort());
                clients.add(unregistered);
                unregistered.send("NICK pending" + i);
            }
            // A connect() completes in the kernel's accept queue, before the server has accepted
            // it; shutdown resets what is still queued (container contract, Signals). Registered
            // clients proved themselves with a welcome, so wait for the unregistered ones.
            ConnectionHandler handler = JircdServerTestAccess.connectionHandler(server.application);
            assertThat(
                            Polling.until(
                                    Duration.ofSeconds(10),
                                    () -> handler.unregisteredConnectionCount() == 3))
                    .isTrue();
            for (RawIrcClient client : clients) received.add(readToEnd(client));

            long start = System.nanoTime();
            int forced =
                    JircdServerTestAccess.shutdown(server.application, REASON, PRODUCTION_BUDGET);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(forced).isZero();
            assertThat(elapsed).isLessThan(PRODUCTION_BUDGET);
            for (CompletableFuture<List<String>> lines : received) {
                assertThat(lines.get(10, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
            }
            assertThatThrownBy(() -> RawIrcClient.connectPlaintext(server.plaintextPort()))
                    .isInstanceOf(IOException.class);
        } finally {
            for (RawIrcClient client : clients) client.close();
        }
    }

    /**
     * Holds the writes of every session created after it is installed, so a test knows for certain
     * that a writer is blocked mid-write: no socket-buffer sizing, no timing. Opening it lets the
     * writes through; failing it makes them throw, as a reset connection does.
     */
    private static final class WriteGate implements AutoCloseable {
        final CountDownLatch writerBlocked = new CountDownLatch(1);
        private final CountDownLatch opened = new CountDownLatch(1);
        private volatile boolean closed;
        private volatile boolean failing;

        static WriteGate installOn(TestServer server) {
            WriteGate gate = new WriteGate();
            JircdServerTestAccess.connectionHandler(server.application)
                    .wrapSessionOutputForTesting(gate::wrap);
            return gate;
        }

        void hold() {
            closed = true;
        }

        void release() {
            opened.countDown();
        }

        @Override
        public void close() {
            release();
        }

        void failAndOpen() {
            failing = true;
            opened.countDown();
        }

        private OutputStream wrap(OutputStream out) {
            return new FilterOutputStream(out) {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (closed) {
                        writerBlocked.countDown();
                        try {
                            opened.await();
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("writer interrupted while held");
                        }
                    }
                    if (failing) throw new IOException("connection reset (test)");
                    out.write(bytes, offset, length);
                }
            };
        }
    }

    /** Registers a client, then holds its session's writer inside a write (a PONG). */
    private static ClientSession blockedClientSession(
            TestServer server, WriteGate gate, RawIrcClient client) throws Exception {
        client.registerAndAwaitWelcome("held", "held");
        ClientSession session = server.application.nicknameRegistry().lookup("held").orElseThrow();
        gate.hold();
        client.send("PING blocked");
        assertThat(gate.writerBlocked.await(10, TimeUnit.SECONDS)).isTrue();
        return session;
    }

    /** Starts the shutdown on its own thread; the result is how many connections were forced. */
    private static CompletableFuture<Integer> shutdownAsync(TestServer server, Duration budget) {
        return CompletableFuture.supplyAsync(
                () -> JircdServerTestAccess.shutdown(server.application, REASON, budget));
    }

    /** Waits until shutdown has claimed the session: its ERROR is queued behind the held write. */
    private static void awaitClosing(ClientSession session) throws InterruptedException {
        assertThat(Polling.until(Duration.ofSeconds(10), () -> session.lifecycle().isClosing()))
                .isTrue();
    }

    @Test
    void aClientThatStopsReadingIsForceClosedAtTheBudget() throws Exception {
        Logger appLog = (Logger) LoggerFactory.getLogger(JircdServerApplication.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        appLog.addAppender(logs);
        // The gate goes in before the client connects: it wraps sessions created after it.
        try (TestServer server = TestServer.start();
                WriteGate gate = WriteGate.installOn(server);
                RawIrcClient stuck = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            try {
                blockedClientSession(server, gate, stuck);

                long start = System.nanoTime();
                int forced =
                        JircdServerTestAccess.shutdown(
                                server.application, REASON, Duration.ofMillis(500));
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                assertThat(forced).isEqualTo(1);
                assertThat(elapsed).isLessThan(Duration.ofSeconds(4));
                assertThat(logs.list)
                        .anySatisfy(
                                e -> {
                                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                                    assertThat(e.getFormattedMessage())
                                            .contains("force-closed 1 connection");
                                });
            } finally {
                gate.release();
            }
        } finally {
            appLog.detachAppender(logs);
        }
    }

    @Test
    void aLineSentWhileTheFinalErrorIsStillDrainingDoesNotCutItOff() throws Exception {
        // The gate goes in before the client connects: it wraps sessions created after it.
        try (TestServer server = TestServer.start();
                WriteGate gate = WriteGate.installOn(server);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            try {
                ClientSession session = blockedClientSession(server, gate, client);
                CountDownLatch readLoopEnded = new CountDownLatch(1);
                JircdServerTestAccess.connectionHandler(server.application)
                        .onReadLoopEndForTesting(
                                ended -> {
                                    if (ended == session) readLoopEnded.countDown();
                                });
                CompletableFuture<Integer> forced = shutdownAsync(server, PRODUCTION_BUDGET);
                awaitClosing(session);

                // The server reads this, sees CLOSING and leaves its read loop while the ERROR is
                // still queued behind the held write.
                client.send("PING late");
                assertThat(readLoopEnded.await(10, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<List<String>> received = readToEnd(client);
                gate.release();

                assertThat(received.get(15, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
                assertThat(forced.get(15, TimeUnit.SECONDS)).isZero();
            } finally {
                gate.release();
            }
        }
    }

    @Test
    void aDrainLongerThanTwoSecondsStillDeliversTheErrorWithinTheBudget() throws Exception {
        // The gate goes in before the client connects: it wraps sessions created after it.
        try (TestServer server = TestServer.start();
                WriteGate gate = WriteGate.installOn(server);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            try {
                ClientSession session = blockedClientSession(server, gate, client);
                CompletableFuture<Integer> forced = shutdownAsync(server, PRODUCTION_BUDGET);
                awaitClosing(session);
                // Past the writer's own two-second drain limit, well inside the shutdown budget:
                // elapsed time is the condition under test here.
                Polling.pause(Duration.ofMillis(2500));
                CompletableFuture<List<String>> received = readToEnd(client);
                gate.release();

                assertThat(received.get(15, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
                assertThat(forced.get(15, TimeUnit.SECONDS)).isZero();
            } finally {
                gate.release();
            }
        }
    }

    @Test
    void aWriterCutOffAtTheDeadlineIsCountedEvenWhenItsThreadThenFinishes() throws Exception {
        // The gate goes in before the client connects: it wraps sessions created after it.
        try (TestServer server = TestServer.start();
                WriteGate gate = WriteGate.installOn(server);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            try {
                ClientSession session = blockedClientSession(server, gate, client);
                // Longer than the writer's two-second drain limit: it must not decide.
                CompletableFuture<Integer> forced = shutdownAsync(server, Duration.ofSeconds(3));
                awaitClosing(session);
                // The read loop ends now, so the connection's thread lives exactly as long as its
                // writer's drain: it can finish right after the cut-off, inside the deadline
                // check, and only the truncation report then says that it was forced.
                client.send("PING late");

                assertThat(forced.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                gate.release();
            }
        }
    }

    @Test
    void aClientResetDuringShutdownIsNotCountedAsForced() throws Exception {
        // The gate goes in before the client connects: it wraps sessions created after it.
        try (TestServer server = TestServer.start();
                WriteGate gate = WriteGate.installOn(server);
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            CountDownLatch closerReached = new CountDownLatch(1);
            CountDownLatch closerGo = new CountDownLatch(1);
            try {
                ClientSession session = blockedClientSession(server, gate, client);
                JircdServerTestAccess.connectionHandler(server.application)
                        .beforeGracefulCloseForTesting(
                                closing -> {
                                    closerReached.countDown();
                                    try {
                                        closerGo.await();
                                    } catch (InterruptedException _) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                CompletableFuture<Integer> forced = shutdownAsync(server, PRODUCTION_BUDGET);
                assertThat(closerReached.await(10, TimeUnit.SECONDS)).isTrue();

                // The connection resets while shutdown is draining: the held write fails and the
                // writer's own failure cleanup closes it, before the closer gets to the session.
                gate.failAndOpen();
                awaitClosing(session);
                closerGo.countDown();

                assertThat(forced.get(15, TimeUnit.SECONDS)).isZero();
            } finally {
                closerGo.countDown();
                gate.release();
            }
        }
    }

    // The client is closed in the finally block.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void aConnectionAcceptedButNotYetServedStillGetsTheShutdownError() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TestServer server =
                TestServer.start(
                        "",
                        socket -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException _) {
                                Thread.currentThread().interrupt();
                            }
                        })) {
            RawIrcClient pending = RawIrcClient.connectPlaintext(server.plaintextPort());
            try {
                // Accepted and owned, but its thread has not yet published a session.
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<List<String>> received = readToEnd(pending);
                CompletableFuture<Integer> forced = shutdownAsync(server, PRODUCTION_BUDGET);
                // Not isAccepting(): admission closes before the drain starts. The drain must
                // already be under way when this connection publishes its session.
                assertThat(
                                Polling.until(
                                        Duration.ofSeconds(10),
                                        JircdServerTestAccess.connectionHandler(server.application)
                                                ::isDraining))
                        .isTrue();
                release.countDown();

                assertThat(received.get(15, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
                assertThat(forced.get(15, TimeUnit.SECONDS)).isZero();
            } finally {
                release.countDown();
                pending.close();
            }
        }
    }

    @Test
    void aSecondShutdownDoesNothing() throws Exception {
        try (TestServer server = TestServer.start()) {
            JircdServerTestAccess.shutdown(server.application, REASON, PRODUCTION_BUDGET);
            long start = System.nanoTime();
            int forced =
                    JircdServerTestAccess.shutdown(server.application, REASON, PRODUCTION_BUDGET);
            assertThat(forced).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void shutdownBeforeStartReturnsAtOnce() throws Exception {
        Path config = Files.writeString(dir.resolve("jircd.yaml"), TestServer.baseYaml());
        JircdServerApplication application = new JircdServerApplication(config);
        long start = System.nanoTime();
        int forced = JircdServerTestAccess.shutdown(application, REASON, PRODUCTION_BUDGET);
        assertThat(forced).isZero();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void aShutdownReasonWithLineBreaksOrExcessLengthStillGivesOneFinalErrorLine() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("rude", "rude");
            CompletableFuture<List<String>> received = readToEnd(client);

            JircdServerTestAccess.shutdown(
                    server.application,
                    "bye\r\nQUIT :injected\0" + "x".repeat(3000),
                    PRODUCTION_BUDGET);

            List<String> lines = received.get(10, TimeUnit.SECONDS);
            assertThat(lines).noneMatch("QUIT :injected"::equals);
            assertThat(lines.getLast())
                    .startsWith("ERROR :Closing Link: " + TestServer.LOOPBACK + " (bye")
                    .endsWith(")")
                    .doesNotContain("\0");
            assertThat(lines.getLast().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .hasSizeLessThanOrEqualTo(510);
        }
    }

    @Test
    void stopWaitsForARunningGracefulShutdownInsteadOfClosingSocketsUnderIt() throws Exception {
        CountDownLatch stopAtHook = new CountDownLatch(1);
        CountDownLatch stopGo = new CountDownLatch(1);
        CountDownLatch closerReached = new CountDownLatch(1);
        CountDownLatch closerGo = new CountDownLatch(1);
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("both", "both");
            JircdServerTestAccess.beforeStopClosesConnections(
                    server.application,
                    () -> {
                        stopAtHook.countDown();
                        await(stopGo);
                    });
            JircdServerTestAccess.connectionHandler(server.application)
                    .beforeGracefulCloseForTesting(
                            closing -> {
                                closerReached.countDown();
                                await(closerGo);
                            });
            try {
                // stop() holds the lifecycle and is about to close the connections.
                CompletableFuture<Void> stop =
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        server.application.stop();
                                    } catch (IOException e) {
                                        throw new java.io.UncheckedIOException(e);
                                    }
                                });
                assertThat(stopAtHook.await(10, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Integer> forced = shutdownAsync(server, PRODUCTION_BUDGET);
                assertThat(closerReached.await(10, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<List<String>> received = readToEnd(client);

                stopGo.countDown();
                Polling.pause(Duration.ofMillis(500));
                // The drain owns the connections: stop() must not have closed them yet.
                assertThat(stop).isNotDone();
                closerGo.countDown();

                assertThat(received.get(15, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
                stop.get(15, TimeUnit.SECONDS);
                assertThat(forced.get(15, TimeUnit.SECONDS)).isZero();
            } finally {
                stopGo.countDown();
                closerGo.countDown();
            }
        }
    }

    @Test
    void forcedClosingStaysInsideTheBudgetAndTheForcedCountSurvivesIt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("stubborn", "stubborn");
            // A connection thread that does not stop when its socket is closed.
            JircdServerTestAccess.connectionHandler(server.application)
                    .onReadLoopEndForTesting(ended -> await(release));
            Duration budget = Duration.ofSeconds(1);
            try {
                long start = System.nanoTime();
                int forced = JircdServerTestAccess.shutdown(server.application, REASON, budget);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                assertThat(forced).isEqualTo(1);
                // The post-deadline grace is part of the budget, not added to it.
                assertThat(elapsed).isLessThan(budget.plus(Duration.ofMillis(300)));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void anIdleClientGetsItsErrorAndEofPromptlyAndIsNotCountedAsForced() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("idle", "idle");
            CountDownLatch readLoopEnded = new CountDownLatch(1);
            JircdServerTestAccess.connectionHandler(server.application)
                    .onReadLoopEndForTesting(ended -> readLoopEnded.countDown());
            CompletableFuture<List<String>> received = readToEnd(client);

            // The budget is long and the liveness tick (five seconds, started at connect) is far
            // away: only the shutdown itself can end this idle connection's read loop now. Before
            // the fix it ended at the forced close, a whole budget later and counted as forced.
            Duration budget = Duration.ofSeconds(3);
            CompletableFuture<Integer> forced = shutdownAsync(server, budget);

            assertThat(readLoopEnded.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get(1, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
            assertThat(forced.get(10, TimeUnit.SECONDS)).isZero();
        }
    }

    @Test
    void aReaderTearingDownBetweenTheClaimAndTheErrorDoesNotLoseTheError() throws Exception {
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("race", "race");
            ConnectionHandler handler = JircdServerTestAccess.connectionHandler(server.application);
            CountDownLatch readLoopEnded = new CountDownLatch(1);
            handler.onReadLoopEndForTesting(ended -> readLoopEnded.countDown());
            handler.afterShutdownClaimForTesting(
                    session -> {
                        claimed.countDown();
                        await(go);
                    });
            CompletableFuture<List<String>> received = readToEnd(client);
            try {
                CompletableFuture<Integer> forced = shutdownAsync(server, PRODUCTION_BUDGET);
                assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

                // The session is CLOSING but the ERROR is not queued: the read loop sees CLOSING
                // on the next line and goes on to close the writer.
                client.send("PING late");
                assertThat(readLoopEnded.await(10, TimeUnit.SECONDS)).isTrue();
                Polling.pause(Duration.ofMillis(300));
                go.countDown();

                assertThat(received.get(15, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
                assertThat(forced.get(15, TimeUnit.SECONDS)).isZero();
            } finally {
                go.countDown();
            }
        }
    }

    @Test
    void anInterruptedStopStillWaitsForTheDrainInsteadOfClosingSocketsUnderIt() throws Exception {
        CountDownLatch stopAtHook = new CountDownLatch(1);
        CountDownLatch stopGo = new CountDownLatch(1);
        CountDownLatch closerReached = new CountDownLatch(1);
        CountDownLatch closerGo = new CountDownLatch(1);
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("intr", "intr");
            JircdServerTestAccess.beforeStopClosesConnections(
                    server.application,
                    () -> {
                        stopAtHook.countDown();
                        await(stopGo);
                    });
            JircdServerTestAccess.connectionHandler(server.application)
                    .beforeGracefulCloseForTesting(
                            closing -> {
                                closerReached.countDown();
                                await(closerGo);
                            });
            try {
                Thread stopper =
                        Thread.ofPlatform()
                                .start(
                                        () -> {
                                            try {
                                                server.application.stop();
                                            } catch (IOException _) {
                                                // Not under test.
                                            }
                                        });
                assertThat(stopAtHook.await(10, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<Integer> forced = shutdownAsync(server, PRODUCTION_BUDGET);
                assertThat(closerReached.await(10, TimeUnit.SECONDS)).isTrue();
                CompletableFuture<List<String>> received = readToEnd(client);
                stopGo.countDown();
                Polling.pause(Duration.ofMillis(300));

                // stop() is now waiting for the drain; interrupting it must not end that wait.
                stopper.interrupt();
                Polling.pause(Duration.ofMillis(300));
                assertThat(stopper.isAlive()).isTrue();
                closerGo.countDown();

                assertThat(received.get(15, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
                stopper.join(TimeUnit.SECONDS.toMillis(15));
                assertThat(stopper.isAlive()).isFalse();
                assertThat(forced.get(15, TimeUnit.SECONDS)).isZero();
            } finally {
                stopGo.countDown();
                closerGo.countDown();
            }
        }
    }

    @Test
    void aShutdownDuringAStopThatOwnsTheConnectionsLeavesThemToIt() throws Exception {
        CountDownLatch stopOwns = new CountDownLatch(1);
        CountDownLatch stopGo = new CountDownLatch(1);
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("owned", "owned");
            JircdServerTestAccess.afterStopClaimsClosure(
                    server.application,
                    () -> {
                        stopOwns.countDown();
                        await(stopGo);
                    });
            CompletableFuture<List<String>> received = readToEnd(client);
            try {
                CompletableFuture<Void> stop =
                        CompletableFuture.runAsync(
                                () -> {
                                    try {
                                        server.application.stop();
                                    } catch (IOException e) {
                                        throw new java.io.UncheckedIOException(e);
                                    }
                                });
                assertThat(stopOwns.await(10, TimeUnit.SECONDS)).isTrue();

                // stop() owns the closure and has not closed anything yet: shutdown returns
                // promptly, drains nothing, and the connection is still open.
                int forced =
                        CompletableFuture.supplyAsync(
                                        () ->
                                                JircdServerTestAccess.shutdown(
                                                        server.application,
                                                        REASON,
                                                        PRODUCTION_BUDGET))
                                .get(3, TimeUnit.SECONDS);
                assertThat(forced).isZero();
                assertThat(received).isNotDone();

                stopGo.countDown();
                stop.get(15, TimeUnit.SECONDS);
                assertThat(received.get(15, TimeUnit.SECONDS)).doesNotContain(EXPECTED_ERROR);
            } finally {
                stopGo.countDown();
            }
        }
    }

    @Test
    void aShutdownAfterAStopAndARestartDrainsTheNewConnections() throws Exception {
        try (TestServer server = TestServer.start()) {
            server.application.stop();
            server.application.start();
            try (RawIrcClient client =
                    RawIrcClient.connectPlaintext(server.application.plaintextPort())) {
                client.registerAndAwaitWelcome("again", "again");
                CompletableFuture<List<String>> received = readToEnd(client);

                int forced =
                        JircdServerTestAccess.shutdown(
                                server.application, REASON, PRODUCTION_BUDGET);

                assertThat(forced).isZero();
                assertThat(received.get(10, TimeUnit.SECONDS)).last().isEqualTo(EXPECTED_ERROR);
                int port = server.application.plaintextPort();
                assertThatThrownBy(() -> RawIrcClient.connectPlaintext(port))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
