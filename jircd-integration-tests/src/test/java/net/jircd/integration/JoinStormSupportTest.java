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

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class JoinStormSupportTest {
  @Test
  void namesNicknameDoesNotCountAsEndOfNames() throws Exception {
    try (ServerSocket listener =
            new ServerSocket(0, 1, java.net.InetAddress.getByName(TestServer.LOOPBACK));
        RawIrcClient client = RawIrcClient.connectPlaintext(listener.getLocalPort());
        var peer = listener.accept()) {
      peer.getOutputStream()
          .write(":server 353 storm366 = #storm :storm366\r\n".getBytes(StandardCharsets.UTF_8));
      peer.shutdownOutput();
      assertThatThrownBy(() -> JoinStormLoadTest.awaitEndOfNames(client))
          .isInstanceOf(IOException.class);
    }
  }

  @Test
  void acceptsTaggedEndOfNamesCommand() throws Exception {
    try (ServerSocket listener =
            new ServerSocket(0, 1, java.net.InetAddress.getByName(TestServer.LOOPBACK));
        RawIrcClient client = RawIrcClient.connectPlaintext(listener.getLocalPort());
        var peer = listener.accept()) {
      peer.getOutputStream()
          .write(
              "@time=now :server 366 storm366 #storm :End of NAMES\r\n"
                  .getBytes(StandardCharsets.UTF_8));
      JoinStormLoadTest.awaitEndOfNames(client);
    }
  }

  @Test
  void unexpectedDisconnectFailsDrain() throws Exception {
    try (ServerSocket listener =
            new ServerSocket(0, 1, java.net.InetAddress.getByName(TestServer.LOOPBACK));
        RawIrcClient client = RawIrcClient.connectPlaintext(listener.getLocalPort());
        var peer = listener.accept()) {
      peer.shutdownOutput();
      assertThatThrownBy(() -> JoinStormLoadTest.drainUntil(client, new AtomicBoolean()))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("disconnected");
    }
  }

  // The pool owns this borrowed client; closing the pool is the behavior under test.
  @SuppressWarnings("PMD.CloseResource")
  @Test
  void teardownUnblocksIdleDrainAndRejectsLateConnections() throws Exception {
    try (var threads = Executors.newVirtualThreadPerTaskExecutor();
        ServerSocket listener =
            new ServerSocket(0, 2, java.net.InetAddress.getByName(TestServer.LOOPBACK));
        var clients = new JoinStormLoadTest.ClientPool()) {
      RawIrcClient client = clients.connect(listener.getLocalPort());
      try (var peer = listener.accept()) {
        AtomicBoolean stormOver = new AtomicBoolean();
        var draining =
            threads.submit(
                () -> {
                  JoinStormLoadTest.drainUntil(client, stormOver);
                  return null;
                });
        stormOver.set(true);
        clients.close();
        draining.get(1, TimeUnit.SECONDS);
        assertThat(peer.getInputStream().read()).isEqualTo(-1);
        assertThatThrownBy(() -> clients.connect(listener.getLocalPort()))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("already closed");
        try (var latePeer = listener.accept()) {
          latePeer.setSoTimeout(1000);
          assertThat(latePeer.getInputStream().read()).isEqualTo(-1);
        }
      }
    }
  }
}
