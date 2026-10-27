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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.function.Supplier;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A connection leaves the unregistered-connection count on every exit from its handler. */
final class ConnectionLiveSessionsTest {

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime", "none"})
    void sessionLeavesTheLiveSetWhateverEndsTheConnection(String outputFailure) throws Exception {
        ExtensionRegistry extensions = new ExtensionRegistry();
        DisconnectCleanup cleanup =
                new DisconnectCleanup(
                        new NicknameRegistry(),
                        new ChannelRegistry(),
                        new WhowasHistory(() -> 100),
                        extensions);
        ConnectionHandler handler =
                new ConnectionHandler(
                        extensions,
                        cleanup,
                        () -> "test.local",
                        new ConnectionHandler.Settings(
                                () -> new ServerConfiguration.RateLimit(100, 100),
                                () -> 60,
                                () -> 524288,
                                () -> new ServerConfiguration.ConnectionLimits(10, 10)),
                        socket -> {},
                        task -> Thread.ofVirtual().unstarted(task));
        Supplier<OutputStream> output =
                switch (outputFailure) {
                    case "io" ->
                            () -> {
                                throw new java.io.UncheckedIOException(
                                        new IOException("no output"));
                            };
                    case "runtime" ->
                            () -> {
                                throw new IllegalStateException("writer setup failed");
                            };
                    default -> ByteArrayOutputStream::new;
                };

        try (FakeSocket socket = new FakeSocket(output)) {
            handler.accept(socket);
            handler.closeConnections(Duration.ofSeconds(5)); // joins the connection thread
        }

        assertThat(handler.unregisteredConnectionCount()).isZero();
    }

    private static final class FakeSocket extends Socket {
        private final Supplier<OutputStream> output;

        FakeSocket(Supplier<OutputStream> output) {
            this.output = output;
        }

        @Override
        public InetAddress getInetAddress() {
            return InetAddress.ofLiteral("127.0.0.1");
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            try {
                return output.get();
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
    }
}
