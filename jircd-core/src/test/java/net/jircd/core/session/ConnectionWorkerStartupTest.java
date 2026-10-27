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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConnectionWorkerStartupTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void workerConstructionAndStartFailuresCloseSocketsAndReleaseReservations(
            boolean rejected, boolean failOnStart) throws Exception {
        AtomicBoolean fail = new AtomicBoolean(!rejected);
        CountDownLatch release = new CountDownLatch(1);
        ThreadFactory workers =
                task -> {
                    if (!fail.get()) return Thread.ofVirtual().unstarted(task);
                    if (!failOnStart) throw new AssertionError("construction failed");
                    return new Thread(task) {
                        @Override
                        public void start() {
                            throw new AssertionError("start failed");
                        }
                    };
                };
        ExtensionRegistry extensions = new ExtensionRegistry();
        DisconnectCleanup cleanup =
                new DisconnectCleanup(
                        new NicknameRegistry(),
                        new ChannelRegistry(),
                        new WhowasHistory(() -> 100),
                        extensions,
                        () -> {});
        ConnectionHandler handler =
                new ConnectionHandler(
                        extensions,
                        cleanup,
                        () -> "test.local",
                        new ConnectionHandler.Settings(
                                () -> new ServerConfiguration.RateLimit(100, 100),
                                () -> 60,
                                () -> 524288,
                                () -> new ServerConfiguration.ConnectionLimits(1, 1)),
                        socket -> {
                            try {
                                release.await();
                            } catch (InterruptedException _) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        workers);
        try (TestSocket admitted = new TestSocket()) {
            try {
                if (rejected) handler.accept(admitted);
                fail.set(true);
                // Repeated rejection failures must not exhaust the 64 permits.
                for (int i = 0; i < 65; i++) {
                    try (TestSocket failed = new TestSocket()) {
                        assertThatThrownBy(() -> handler.accept(failed))
                                .isInstanceOf(AssertionError.class);
                        assertThat(failed.isClosed()).isTrue();
                    }
                }
                fail.set(false);
                if (!rejected) handler.accept(admitted);
                try (TestSocket probe = new TestSocket()) {
                    handler.accept(probe);
                    assertThat(probe.closed.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(probe.output.toString(StandardCharsets.UTF_8))
                            .isEqualTo("ERROR :Too many connections\r\n");
                }
            } finally {
                release.countDown();
                handler.closeConnections(Duration.ofSeconds(5));
            }
        }
    }

    private static final class TestSocket extends Socket {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public InetAddress getInetAddress() {
            return InetAddress.ofLiteral("127.0.0.1");
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                closed.countDown();
            }
        }
    }
}
