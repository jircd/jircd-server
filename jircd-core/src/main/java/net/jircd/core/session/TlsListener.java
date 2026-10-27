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

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import net.jircd.core.config.ServerConfiguration;

/**
 * The optional TLS connection listener (FR-018): blocking {@link SSLServerSocket}/{@code SSLSocket}
 * API matching the virtual-thread model (research.md "TLS approach"), not {@code SSLEngine}.
 */
public final class TlsListener extends AbstractListener<SSLServerSocket> {

    public TlsListener(ServerConfiguration.Listener listener, ConnectionHandler connectionHandler)
            throws IOException, GeneralSecurityException {
        super(
                openServerSocket(
                        listener, TlsSupport.buildServerContext(listener).getServerSocketFactory()),
                connectionHandler::accept,
                "tls-listener");
    }

    static SSLServerSocket openServerSocket(
            ServerConfiguration.Listener listener, SSLServerSocketFactory factory)
            throws IOException {
        return (SSLServerSocket)
                factory.createServerSocket(
                        listener.port(), listener.backlog(), listener.bindAddress());
    }

    @Override
    public int boundPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    protected Socket accept() throws IOException {
        return serverSocket.accept();
    }
}
