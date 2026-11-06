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
package net.jircd.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AbstractListenerTest {

    @SuppressWarnings("PMD.CloseResource") // Listener and test scope own these accepted sockets.
    @Test
    void closesSocketWhenTcpNoDelayFailsAndContinuesAccepting() throws Exception {
        var failed = new FailingTcpNoDelaySocket();
        var next = new Socket();
        var accepted = new CountDownLatch(1);
        var sockets = new ArrayBlockingQueue<Socket>(2);
        var dispatched = new ConcurrentLinkedQueue<Socket>();
        var closed = new CountDownLatch(1);
        sockets.add(failed);
        sockets.add(next);
        var listener =
                new AbstractListener<Closeable>(
                        closed::countDown,
                        socket -> {
                            dispatched.add(socket);
                            accepted.countDown();
                        },
                        "tcp-no-delay-failure-listener") {
                    @Override
                    protected Socket accept() throws IOException {
                        Socket socket = sockets.poll();
                        if (socket != null) return socket;
                        awaitClose(closed);
                        throw new IOException("No more test sockets");
                    }

                    @Override
                    public int boundPort() {
                        return 0;
                    }
                };
        try (listener;
                failed;
                next) {
            listener.start();
            assertThat(accepted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failed.closed.get()).isTrue();
            assertThat(next.getTcpNoDelay()).isTrue();
            assertThat(dispatched).contains(next).doesNotContain(failed);
        }
    }

    private static void awaitClose(CountDownLatch closed) throws IOException {
        try {
            closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static final class FailingTcpNoDelaySocket extends Socket {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
            throw new SocketException("TCP_NODELAY unavailable");
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    @Test
    void closeFailsWhenAcceptThreadDoesNotExitWithinTimeout() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closed = new AtomicBoolean();
        var acceptThread = new AtomicReference<Thread>();
        var listener =
                new AbstractListener<Closeable>(
                        () -> closed.set(true),
                        socket -> fail("No connection expected"),
                        "blocked-listener",
                        Duration.ofMillis(50)) {
                    @Override
                    protected Socket accept() throws IOException {
                        acceptThread.set(Thread.currentThread());
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        throw new IOException("Accept released after socket closure");
                    }

                    @Override
                    public int boundPort() {
                        return 0;
                    }
                };
        try (listener) {
            try {
                listener.start();
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                assertTimeoutPreemptively(
                        Duration.ofSeconds(1),
                        () ->
                                assertThatThrownBy(listener::close)
                                        .isInstanceOf(IOException.class)
                                        .hasMessageContaining(
                                                "Timed out stopping blocked-listener"));
                assertThat(closed).isTrue();
                assertThat(acceptThread.get().isAlive()).isTrue();
            } finally {
                release.countDown();
                assertThat(acceptThread.get().join(Duration.ofSeconds(1))).isTrue();
            }
        }
    }

    @Test
    void socketCloseFailureStillJoinsAndSuppressesJoinFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closeFailure = new IOException("socket close failure");
        var acceptThread = new AtomicReference<Thread>();
        var closeAttempted = new AtomicBoolean();
        var listener =
                new AbstractListener<Closeable>(
                        () -> {
                            if (!closeAttempted.getAndSet(true)) {
                                throw closeFailure;
                            }
                        },
                        socket -> fail("No connection expected"),
                        "failing-listener",
                        Duration.ofMillis(50)) {
                    @Override
                    protected Socket accept() throws IOException {
                        acceptThread.set(Thread.currentThread());
                        entered.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        throw new IOException("Released");
                    }

                    @Override
                    public int boundPort() {
                        return 0;
                    }
                };
        try (listener) {
            try {
                listener.start();
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                Thread.currentThread().interrupt();
                assertThatThrownBy(listener::close)
                        .isSameAs(closeFailure)
                        .satisfies(
                                failure -> {
                                    assertThat(failure.getSuppressed()).hasSize(1);
                                    assertThat(failure.getSuppressed()[0])
                                            .isInstanceOf(IOException.class)
                                            .hasCauseInstanceOf(InterruptedException.class);
                                });
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
                release.countDown();
            }
        }
        assertThat(acceptThread.get().join(Duration.ofSeconds(1))).isTrue();
    }
}
