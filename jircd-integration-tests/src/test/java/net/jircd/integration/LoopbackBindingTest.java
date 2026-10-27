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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * #38: a listener on the wildcard address can share its port with another local process's {@code
 * 127.0.0.1} listener, and loopback clients then reach that other process instead. Test servers
 * therefore bind {@code 127.0.0.1} itself.
 */
class LoopbackBindingTest {

    private static final Duration ACCEPT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * The failure #38 reports, made deterministic: the port is chosen to collide instead of waiting
     * for the OS to hand one out. On macOS the wildcard bind succeeds and the client never reaches
     * the server; platforms that refuse the bind cannot hit #38 this way.
     */
    @Test
    void wildcardListenerLosesLoopbackClientsToALoopbackListenerOnTheSamePort() throws Exception {
        try (ServerSocket otherProcess = new ServerSocket(0, 50, loopback())) {
            int port = otherProcess.getLocalPort();
            TestServer server;
            try {
                // A later top-level 'listeners' key replaces TestServer.baseYaml()'s own.
                server = TestServer.start("listeners:\n  - port: " + port + "\n    tls: false\n");
            } catch (BindException _) {
                assumeTrue(false, "this platform refuses a wildcard bind over a loopback listener");
                return;
            }
            try (server;
                    RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                    Socket taken = acceptOrSkip(otherProcess)) {
                assertThat(taken.getPort()).isEqualTo(alice.localPort());
                taken.close();
                assertThatThrownBy(() -> alice.registerAndAwaitWelcome("alice", "alice"))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void testServerListensOnlyOnLoopback() throws Exception {
        try (TestServer server = TestServer.start()) {
            for (int port : new int[] {server.plaintextPort(), server.tlsPort()}) {
                InetAddress ipv6Loopback = InetAddress.ofLiteral("::1");
                assertThatThrownBy(() -> connect(ipv6Loopback, port))
                        .isInstanceOf(IOException.class);
            }
            try (RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
                alice.registerAndAwaitWelcome("alice", "alice");
            }
        }
    }

    /**
     * Some platforms allow both binds but route the client to the wildcard listener. Nothing then
     * reaches {@code otherProcess}, and #38 cannot happen there.
     */
    private static Socket acceptOrSkip(ServerSocket otherProcess) throws IOException {
        otherProcess.setSoTimeout((int) ACCEPT_TIMEOUT.toMillis());
        try {
            return otherProcess.accept();
        } catch (SocketTimeoutException _) {
            assumeTrue(false, "this platform routes loopback clients to the wildcard listener");
            throw new AssertionError("unreachable: the assumption above aborts the test");
        }
    }

    /** Opens and closes a connection; only the open is expected to throw. */
    private static void connect(InetAddress address, int port) throws IOException {
        try (var _ = new Socket(address, port)) {
            // Reaching here means the connection opened.
        }
    }

    private static InetAddress loopback() {
        return InetAddress.ofLiteral(TestServer.LOOPBACK);
    }
}
