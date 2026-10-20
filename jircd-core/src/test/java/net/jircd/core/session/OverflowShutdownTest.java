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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

class OverflowShutdownTest {
  @Test
  void senderReturnsPromptlyButShutdownWaitsForOverflowCleanup() throws Exception {
    CountDownLatch cleaning = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch senderReturned = new CountDownLatch(1);
    ExtensionRegistry extensions = new ExtensionRegistry();
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            extensions,
            () -> {
              cleaning.countDown();
              try {
                release.await();
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            });
    ConnectionHandler handler =
        new ConnectionHandler(
            extensions,
            cleanup,
            () -> "test.local",
            () -> new ServerConfiguration.RateLimit(100, 100),
            () -> 60,
            () -> 16384);
    handler.registerHandler(
        Command.PING,
        (session, message) -> {
          session.writer().enqueueRaw(Message.of(Command.PING, "x".repeat(20000)));
          senderReturned.countDown();
        });
    try (ServerSocket listener = loopbackListener();
        Socket client = new Socket(listener.getInetAddress(), listener.getLocalPort());
        Socket accepted = listener.accept();
        var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      handler.accept(accepted);
      try {
        client.getOutputStream().write("PING :overflow\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(cleaning.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(senderReturned.await(250, TimeUnit.MILLISECONDS)).isTrue();
        var stopping =
            threads.submit(
                () -> {
                  handler.closeConnections(Duration.ofSeconds(5));
                  return null;
                });
        assertThatThrownBy(() -> stopping.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        release.countDown();
        stopping.get(5, TimeUnit.SECONDS);
      } finally {
        release.countDown();
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
