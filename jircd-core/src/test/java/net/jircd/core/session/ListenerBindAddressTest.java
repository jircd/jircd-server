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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.Test;

/** A listener's {@code bindAddress} restricts it to that one local address (#38). */
class ListenerBindAddressTest {

    private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");

    @Test
    void plaintextListenerBoundToLoopbackAcceptsALoopbackClient() throws Exception {
        try (PlaintextListener listener =
                new PlaintextListener(listenerOn(LOOPBACK, 0), connectionHandler())) {
            listener.start();
            try (Socket client = new Socket(LOOPBACK, listener.boundPort());
                    BufferedReader reader =
                            new BufferedReader(
                                    new InputStreamReader(
                                            client.getInputStream(), StandardCharsets.UTF_8))) {
                client.getOutputStream().write("HELLO\r\n".getBytes(StandardCharsets.UTF_8));

                assertThat(reader.readLine()).contains(" 421 ");
            }
        }
    }

    @Test
    void plaintextListenerEnablesTcpNoDelayOnTheSocketReceivedByConnectionHandler()
            throws Exception {
        var received = new CountDownLatch(1);
        var tcpNoDelay = new AtomicBoolean();
        ConnectionHandler handler = connectionHandler(recordSocketOptions(received, tcpNoDelay));
        try (PlaintextListener listener = new PlaintextListener(listenerOn(LOOPBACK, 0), handler)) {
            listener.start();
            try (Socket client = new Socket(LOOPBACK, listener.boundPort())) {
                assertThat(client.getRemoteSocketAddress())
                        .isEqualTo(new InetSocketAddress(LOOPBACK, listener.boundPort()));
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(tcpNoDelay).isTrue();
            }
        } finally {
            handler.closeConnections(Duration.ofSeconds(5));
        }
    }

    @Test
    void tlsListenerEnablesTcpNoDelayOnTheSocketReceivedByConnectionHandler() throws Exception {
        var received = new CountDownLatch(1);
        var tcpNoDelay = new AtomicBoolean();
        var tlsSocket = new AtomicBoolean();
        ConnectionHandler handler =
                connectionHandler(
                        socket -> {
                            tlsSocket.set(socket instanceof SSLSocket);
                            recordSocketOptions(received, tcpNoDelay).accept(socket);
                        });
        SSLServerSocket serverSocket =
                (SSLServerSocket)
                        SSLServerSocketFactory.getDefault().createServerSocket(0, 50, LOOPBACK);
        try (TlsListener listener = new TlsListener(serverSocket, handler)) {
            listener.start();
            try (Socket client = new Socket(LOOPBACK, listener.boundPort())) {
                assertThat(client.getRemoteSocketAddress())
                        .isEqualTo(new InetSocketAddress(LOOPBACK, listener.boundPort()));
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(tcpNoDelay).isTrue();
                assertThat(tlsSocket).isTrue();
            }
        } finally {
            handler.closeConnections(Duration.ofSeconds(5));
        }
    }

    private static Consumer<Socket> recordSocketOptions(
            CountDownLatch received, AtomicBoolean tcpNoDelay) {
        return socket -> {
            try {
                tcpNoDelay.set(socket.getTcpNoDelay());
            } catch (SocketException e) {
                throw new AssertionError(e);
            } finally {
                received.countDown();
            }
        };
    }

    @Test
    void plaintextListenerBoundToLoopbackIsNotReachableOnAnotherAddress() throws Exception {
        try (PlaintextListener listener =
                new PlaintextListener(listenerOn(LOOPBACK, 0), connectionHandler())) {
            listener.start();

            assertThatThrownBy(() -> new Socket(InetAddress.ofLiteral("::1"), listener.boundPort()))
                    .isInstanceOf(IOException.class);
        }
    }

    /**
     * The #38 failure mode: a wildcard listener can share its port with another socket's loopback
     * listener and lose loopback clients to it. Two listeners on the same specific address cannot
     * share a port, so a loopback-bound listener fails to start instead.
     */
    @Test
    void plaintextListenerBoundToLoopbackRefusesAPortAlreadyHeldThere() throws Exception {
        try (ServerSocket other = new ServerSocket()) {
            other.bind(new InetSocketAddress(LOOPBACK, 0));

            assertThatThrownBy(
                            () ->
                                    new PlaintextListener(
                                            listenerOn(LOOPBACK, other.getLocalPort()),
                                            connectionHandler()))
                    .isInstanceOf(java.net.BindException.class);
        }
    }

    private static ServerConfiguration.Listener listenerOn(InetAddress bindAddress, int port) {
        return new ServerConfiguration.Listener(port, false, bindAddress, null, null, null, null);
    }

    private static ConnectionHandler connectionHandler() {
        return connectionHandler(socket -> {});
    }

    private static ConnectionHandler connectionHandler(Consumer<Socket> acceptedSocketCustomizer) {
        ExtensionRegistry extensionRegistry = new ExtensionRegistry();
        DisconnectCleanup disconnectCleanup =
                new DisconnectCleanup(
                        new NicknameRegistry(),
                        new ChannelRegistry(),
                        new WhowasHistory(() -> 10),
                        extensionRegistry);
        return new ConnectionHandler(
                extensionRegistry,
                disconnectCleanup,
                () -> "test.jircd.local",
                () -> new ServerConfiguration.RateLimit(20, 10.0),
                () -> 60,
                () -> ServerConfiguration.DEFAULT_SENDQ_LIMIT_BYTES,
                acceptedSocketCustomizer);
    }
}
