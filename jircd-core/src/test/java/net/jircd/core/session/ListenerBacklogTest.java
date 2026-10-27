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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Set;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import net.jircd.core.config.ServerConfiguration;
import org.junit.jupiter.api.Test;

class ListenerBacklogTest {
    private static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    private static final int BACKLOG = 2048;

    @Test
    void plaintextPassesConfiguredBacklogToChannelBind() throws Exception {
        try (RecordingChannel channel = new RecordingChannel()) {
            assertThat(PlaintextListener.openServerChannel(configuration(false), channel))
                    .isSameAs(channel);
            assertThat(channel.backlog).isEqualTo(BACKLOG);
            assertThat(channel.address).isEqualTo(new InetSocketAddress(LOOPBACK, 0));
        }
    }

    @Test
    void tlsPassesConfiguredBacklogToSocketFactory() throws Exception {
        RecordingSocketFactory factory = new RecordingSocketFactory();
        try (SSLServerSocket socket = TlsListener.openServerSocket(configuration(true), factory)) {
            assertThat(socket).isNotNull();
            assertThat(factory.backlog).isEqualTo(BACKLOG);
            assertThat(factory.address).isEqualTo(LOOPBACK);
            assertThat(factory.port).isZero();
        }
    }

    private static ServerConfiguration.Listener configuration(boolean tls) {
        return new ServerConfiguration.Listener(0, tls, LOOPBACK, null, null, null, null, BACKLOG);
    }

    private static final class RecordingChannel extends ServerSocketChannel {
        private int backlog;
        private SocketAddress address;

        RecordingChannel() {
            super(SelectorProvider.provider());
        }

        @Override
        public ServerSocketChannel bind(SocketAddress local, int requestedBacklog) {
            address = local;
            backlog = requestedBacklog;
            return this;
        }

        @Override
        public <T> ServerSocketChannel setOption(SocketOption<T> name, T value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T getOption(SocketOption<T> name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<SocketOption<?>> supportedOptions() {
            return Set.of();
        }

        @Override
        public ServerSocket socket() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SocketChannel accept() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SocketAddress getLocalAddress() {
            return address;
        }

        @Override
        protected void implCloseSelectableChannel() {
            // No native resources.
        }

        @Override
        protected void implConfigureBlocking(boolean block) {
            // No native resources.
        }
    }

    private static final class RecordingSocketFactory extends SSLServerSocketFactory {
        private int backlog;
        private int port;
        private InetAddress address;

        @Override
        public ServerSocket createServerSocket(
                int requestedPort, int requestedBacklog, InetAddress requestedAddress)
                throws IOException {
            port = requestedPort;
            backlog = requestedBacklog;
            address = requestedAddress;
            return SSLServerSocketFactory.getDefault().createServerSocket();
        }

        @Override
        public ServerSocket createServerSocket(int requestedPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ServerSocket createServerSocket(int requestedPort, int requestedBacklog) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return new String[0];
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return new String[0];
        }
    }
}
