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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import net.jircd.core.config.ServerConfiguration;

/**
 * Plaintext connection listener: a {@link ServerSocketChannel} accept loop, one virtual thread per
 * connection (research.md "Networking model").
 */
public final class PlaintextListener extends AbstractListener<ServerSocketChannel> {

  public PlaintextListener(
      ServerConfiguration.Listener listener, ConnectionHandler connectionHandler)
      throws IOException {
    super(openServerChannel(listener), connectionHandler::accept, "plaintext-listener");
  }

  private static ServerSocketChannel openServerChannel(ServerConfiguration.Listener listener)
      throws IOException {
    return openServerChannel(listener, ServerSocketChannel.open());
  }

  static ServerSocketChannel openServerChannel(
      ServerConfiguration.Listener listener, ServerSocketChannel channel) throws IOException {
    try {
      // A null bindAddress makes this the wildcard address.
      channel.bind(
          new InetSocketAddress(listener.bindAddress(), listener.port()), listener.backlog());
      return channel;
    } catch (IOException | RuntimeException failure) {
      try {
        channel.close();
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  @Override
  public int boundPort() {
    return serverSocket.socket().getLocalPort();
  }

  @Override
  protected Socket accept() throws IOException {
    return serverSocket.accept().socket();
  }
}
