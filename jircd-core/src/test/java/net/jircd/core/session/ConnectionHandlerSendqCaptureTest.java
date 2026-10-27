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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/** A reload between accept and writer construction must not change the accepted limit. */
class ConnectionHandlerSendqCaptureTest {
    @Test
    void connectionKeepsTheLimitReadWhenItWasAccepted() throws Exception {
        CountDownLatch cleaning = new CountDownLatch(1);
        AtomicInteger configured = new AtomicInteger(16384);
        ExtensionRegistry extensions = new ExtensionRegistry();
        DisconnectCleanup cleanup =
                new DisconnectCleanup(
                        new NicknameRegistry(),
                        new ChannelRegistry(),
                        new WhowasHistory(() -> 100),
                        extensions,
                        cleaning::countDown);
        // The customizer runs on the connection thread after accept() and before the writer exists.
        ConnectionHandler handler =
                new ConnectionHandler(
                        extensions,
                        cleanup,
                        () -> "test.local",
                        () -> new ServerConfiguration.RateLimit(100, 100),
                        () -> 60,
                        configured::get,
                        socket -> configured.set(2_000_000));
        handler.registerHandler(
                Command.PING,
                (session, message) ->
                        session.writer().enqueueRaw(Message.of(Command.PING, "x".repeat(20000))));
        try (ServerSocket listener = loopbackListener();
                Socket client = new Socket(listener.getInetAddress(), listener.getLocalPort());
                Socket accepted = listener.accept()) {
            handler.accept(accepted);
            try {
                client.getOutputStream()
                        .write("PING :overflow\r\n".getBytes(StandardCharsets.UTF_8));
                assertThat(cleaning.await(2, TimeUnit.SECONDS)).isTrue();
            } finally {
                handler.closeConnections(Duration.ofSeconds(5));
            }
        }
    }

    // Bind to loopback, not the wildcard: on macOS a more specific bind of the same port by another
    // process wins the connect, and accept() would then wait forever. The timeout bounds that case.
    private static ServerSocket loopbackListener() throws IOException {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(5000);
        return listener;
    }
}
