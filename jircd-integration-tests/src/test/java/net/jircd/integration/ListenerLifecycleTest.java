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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.session.ConnectionHandler;
import net.jircd.protocol.Command;
import net.jircd.server.JircdServerApplication;
import net.jircd.server.JircdServerTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ListenerLifecycleTest {

  @TempDir Path temporaryDirectory;

  @Test
  void stopWaitsForRegisteredAndUnregisteredConnectionsToClose() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient registered = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient unregistered = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      registered.registerAndAwaitWelcome("shutdown", "shutdown");
      registered.readThroughPong(Duration.ofSeconds(5));
      unregistered.send("PING :accepted");
      unregistered.readUntil("PONG", Duration.ofSeconds(5));
      server.application.stop();
      assertThat(server.application.nicknameRegistry().all()).isEmpty();
      assertThat(registered.readLine()).isNull();
      assertThat(unregistered.readLine()).isNull();
    }
  }

  @Test
  void listenerFailureStillDrainsConnectionsAndReportsAllFailures() throws Exception {
    try (TestServer server = TestServer.start();
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      client.registerAndAwaitWelcome("failstop", "failstop");
      client.readThroughPong(Duration.ofSeconds(5));
      IOException first = new IOException("first listener failure");
      IOException second = new IOException("second listener failure");
      ownedListeners(server.application)
          .add(
              () -> {
                throw first;
              });
      ownedListeners(server.application)
          .add(
              () -> {
                throw second;
              });
      try {
        assertThatThrownBy(server.application::stop)
            .isSameAs(first)
            .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(second));
        assertThat(server.application.nicknameRegistry().all()).isEmpty();
        assertThat(client.readLine()).isNull();
      } finally {
        // Injected failures are permanent; remove them before the fixture's final close.
        ownedListeners(server.application).clear();
      }
    }
  }

  @Test
  void stoppedApplicationRejectsLateAcceptsAndCanRestart() throws Exception {
    try (TestServer server = TestServer.start();
        Socket late = new Socket()) {
      ConnectionHandler handler = JircdServerTestAccess.connectionHandler(server.application);
      server.application.stop();
      handler.accept(late);
      assertThat(late.isClosed()).isTrue();
      server.application.start();
      try (RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
        client.registerAndAwaitWelcome("restart", "restart");
      }
    }
  }

  @Test
  void connectionShutdownTimesOutLoudlyAndCanBeWaitedForAgain() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (TestServer server = TestServer.start();
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      ConnectionHandler handler = JircdServerTestAccess.connectionHandler(server.application);
      handler.registerHandler(
          Command.PING,
          (_, _) -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
          });
      try {
        client.send("PING :block");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> handler.closeConnections(Duration.ofMillis(20)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Timed out waiting for 1 connections to stop");
      } finally {
        release.countDown();
      }
      server.application.stop();
      assertThat(client.readLine()).isNull();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stopClosesEveryListenerOfTheSameType(boolean tls) throws Exception {
    try (ServerSocket firstReservation = loopbackListener();
        ServerSocket secondReservation = loopbackListener()) {
      int firstPort = firstReservation.getLocalPort();
      int secondPort = secondReservation.getLocalPort();
      var application = application(listener(firstPort, tls) + listener(secondPort, tls));
      firstReservation.close();
      secondReservation.close();
      try {
        application.start();
        assertBound(firstPort);
        assertBound(secondPort);
        application.stop();
        assertAvailable(firstPort);
        assertAvailable(secondPort);
        assertThatCode(application::stop).doesNotThrowAnyException();
      } finally {
        application.stop();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void startupFailureClosesEarlierListenersBeforeRethrowing(boolean tls) throws Exception {
    try (ServerSocket firstReservation = loopbackListener();
        ServerSocket occupied = loopbackListener()) {
      int firstPort = firstReservation.getLocalPort();
      var application =
          application(listener(firstPort, tls) + listener(occupied.getLocalPort(), tls));
      firstReservation.close();
      try {
        assertThatThrownBy(application::start).isInstanceOf(BindException.class);
        assertAvailable(firstPort);
        assertThatCode(application::stop).doesNotThrowAnyException();
      } finally {
        application.stop();
      }
    }
  }

  @Test
  void startupFailureClosesConnectionsAcceptedDuringTheAttempt() throws Exception {
    try (ServerSocket firstReservation = loopbackListener();
        ServerSocket occupied = loopbackListener();
        ServerSocket pairing = loopbackListener();
        Socket client = new Socket(pairing.getInetAddress(), pairing.getLocalPort());
        Socket accepted = pairing.accept()) {
      int firstPort = firstReservation.getLocalPort();
      var application =
          application(listener(firstPort, false) + listener(occupied.getLocalPort(), false));
      firstReservation.close();
      ConnectionHandler handler = JircdServerTestAccess.connectionHandler(application);
      // Deterministic stand-in for a client accepted by the first listener before the second fails.
      handler.accept(accepted);
      client.setSoTimeout(5000);
      client
          .getOutputStream()
          .write("PING :up\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      try {
        assertThatThrownBy(application::start).isInstanceOf(BindException.class);
        awaitServerClose(client);
        try (Socket late = new Socket()) {
          handler.accept(late);
          assertThat(late.isClosed()).isTrue();
        }
      } finally {
        application.stop();
      }
    }
  }

  /**
   * Reads until the server closes the connection. The server may close with the client's {@code
   * PING} still unread, in which case the kernel sends RST and the read fails with a connection
   * reset instead of returning EOF; both mean the connection was closed. A {@code
   * SocketTimeoutException} is not a {@code SocketException}, so a connection left open still fails
   * the test through the soTimeout.
   */
  private static void awaitServerClose(Socket client) throws IOException {
    try {
      while (client.getInputStream().read() != -1) {
        // read until the server closes the connection
      }
    } catch (SocketException e) {
      assertThat(e.getMessage()).containsIgnoringCase("reset");
    }
  }

  @Test
  void stopRetriesFailedListenersButNeverClosesSuccessfulListenersTwice() throws Exception {
    var application = application(listener(0, false));
    var firstFailure = new IOException("first close failure");
    var secondFailure = new IOException("second close failure");
    var firstAttempts = new AtomicInteger();
    var secondAttempts = new AtomicInteger();
    var successfulAttempts = new AtomicInteger();
    ownedListeners(application)
        .add(
            () -> {
              if (firstAttempts.incrementAndGet() == 1) {
                throw firstFailure;
              }
            });
    ownedListeners(application)
        .add(
            () -> {
              if (secondAttempts.incrementAndGet() == 1) {
                throw secondFailure;
              }
            });
    ownedListeners(application).add(successfulAttempts::incrementAndGet);

    assertThatThrownBy(application::stop)
        .isSameAs(firstFailure)
        .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(secondFailure));
    assertThat(successfulAttempts.get()).isEqualTo(1);
    assertThat(ownedListeners(application)).hasSize(2);
    assertThatCode(application::stop).doesNotThrowAnyException();
    assertThat(firstAttempts.get()).isEqualTo(2);
    assertThat(secondAttempts.get()).isEqualTo(2);
    assertThat(ownedListeners(application)).isEmpty();
    application.stop();
    assertThat(successfulAttempts.get()).isEqualTo(1);
  }

  @Test
  void stopPrefersIOExceptionAndContinuesAfterRuntimeFailures() throws Exception {
    var application = application(listener(0, false));
    var runtimeFailure = new IllegalStateException("runtime close failure");
    var ioFailure = new IOException("IO close failure");
    var laterFailure = new IllegalArgumentException("later runtime failure");
    var attempts = new AtomicInteger();
    ownedListeners(application)
        .add(
            () -> {
              throw runtimeFailure;
            });
    ownedListeners(application)
        .add(
            () -> {
              throw ioFailure;
            });
    ownedListeners(application)
        .add(
            () -> {
              throw laterFailure;
            });
    ownedListeners(application).add(attempts::incrementAndGet);

    assertThatThrownBy(application::stop)
        .isSameAs(ioFailure)
        .satisfies(
            failure ->
                assertThat(failure.getSuppressed()).containsExactly(runtimeFailure, laterFailure));
    assertThat(attempts.get()).isEqualTo(1);
    assertThat(ownedListeners(application)).hasSize(3);
  }

  @Test
  void stopRethrowsRuntimeFailureWhenNoIOExceptionOccurs() throws Exception {
    var application = application(listener(0, false));
    var firstFailure = new IllegalStateException("first runtime failure");
    var secondFailure = new IllegalArgumentException("second runtime failure");
    var attempts = new AtomicInteger();
    ownedListeners(application)
        .add(
            () -> {
              throw firstFailure;
            });
    ownedListeners(application)
        .add(
            () -> {
              throw secondFailure;
            });
    ownedListeners(application).add(attempts::incrementAndGet);

    assertThatThrownBy(application::stop)
        .isSameAs(firstFailure)
        .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(secondFailure));
    assertThat(attempts.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failedSecondStartPreservesListenersFromSuccessfulStart(boolean tls) throws Exception {
    try (var reservation = loopbackListener()) {
      int port = reservation.getLocalPort();
      var application = application(listener(0, tls) + listener(port, tls));
      reservation.close();
      try {
        application.start();
        var originalListeners = new ArrayList<>(ownedListeners(application));
        assertThatThrownBy(application::start).isInstanceOf(BindException.class);
        assertBound(port);
        assertThat(ownedListeners(application)).containsExactlyElementsOf(originalListeners);
        assertThat(tls ? application.tlsPort() : application.plaintextPort()).isEqualTo(port);
      } finally {
        application.stop();
      }
      assertAvailable(port);
    }
  }

  @Test
  void rollbackLeavesResourcesOwnedBeforeTheAttemptUntouched() throws Exception {
    try (var occupied = loopbackListener()) {
      var application = application(listener(occupied.getLocalPort(), false));
      var attempts = new AtomicInteger();
      ownedListeners(application).add(attempts::incrementAndGet);
      assertThatThrownBy(application::start).isInstanceOf(BindException.class);
      assertThat(attempts.get()).isZero();
      application.stop();
      assertThat(attempts.get()).isEqualTo(1);
    }
  }

  @Test
  void startWaitsForAnInProgressStop() throws Exception {
    var application = application(listener(0, false));
    var closing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var starting = new CountDownLatch(1);
    application.start();
    ownedListeners(application)
        .add(
            () -> {
              closing.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
              }
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var stopping =
            executor.submit(
                () -> {
                  application.stop();
                  return null;
                });
        assertThat(closing.await(1, TimeUnit.SECONDS)).isTrue();
        var restarting =
            executor.submit(
                () -> {
                  starting.countDown();
                  application.start();
                  return null;
                });
        assertThat(starting.await(1, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> restarting.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        release.countDown();
        stopping.get(1, TimeUnit.SECONDS);
        restarting.get(1, TimeUnit.SECONDS);
        assertBound(application.plaintextPort());
      } finally {
        release.countDown();
      }
    } finally {
      application.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reloadPublishesBacklogWithoutRebindingRunningListener(boolean tls) throws Exception {
    var application = application(listener(0, tls) + "    backlog: 16\n");
    try {
      application.start();
      int boundPort = tls ? application.tlsPort() : application.plaintextPort();
      var originalListeners = new ArrayList<>(ownedListeners(application));
      Files.writeString(
          application.reloader().configPath(),
          "serverName: test.jircd.local\nlisteners:\n" + listener(0, tls) + "    backlog: 2048\n");
      assertThat(application.reloader().reload().listeners().getFirst().backlog()).isEqualTo(2048);
      assertThat(tls ? application.tlsPort() : application.plaintextPort()).isEqualTo(boundPort);
      assertThat(ownedListeners(application)).containsExactlyElementsOf(originalListeners);
      try (RawIrcClient client =
          tls ? RawIrcClient.connectTls(boundPort) : RawIrcClient.connectPlaintext(boundPort)) {
        client.registerAndAwaitWelcome("backlog", "backlog");
      }
    } finally {
      application.stop();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void lifecycleListenerReservesItsLoopbackEndpoint(boolean tls) throws Exception {
    var application = application(listener(0, tls));
    try (ServerSocket competing = new ServerSocket()) {
      application.start();
      int port = tls ? application.tlsPort() : application.plaintextPort();
      // On macOS a more specific bind can share a wildcard listener's port and steal clients.
      // Guards only on macOS: Linux rejects this even with SO_REUSEADDR, so passes without the fix.
      competing.setReuseAddress(true);
      var endpoint = new InetSocketAddress(TestServer.LOOPBACK, port);
      assertThatThrownBy(() -> competing.bind(endpoint)).isInstanceOf(BindException.class);
    } finally {
      application.stop();
    }
  }

  @Test
  void stopBeforeStartIsHarmless() throws Exception {
    var application = application(listener(0, false));
    assertThatCode(
            () -> {
              application.stop();
              application.stop();
            })
        .doesNotThrowAnyException();
  }

  private JircdServerApplication application(String listeners) throws Exception {
    Path config = temporaryDirectory.resolve("jircd.yaml");
    Files.writeString(config, "serverName: test.jircd.local\nlisteners:\n" + listeners);
    return new JircdServerApplication(config);
  }

  private static String listener(int port, boolean tls) {
    // Match RawIrcClient's destination so another process cannot steal loopback connections (#105).
    return "  - port: "
        + port
        + "\n    bindAddress: "
        + TestServer.LOOPBACK
        + "\n    tls: "
        + tls
        + "\n"
        + (tls
            ? "    keystorePath: \""
                + TestServer.resourcePath("tls/test-keystore.p12")
                + "\"\n    keystorePassword: changeit\n"
            : "");
  }

  // Inject failing resources because real socket close failures cannot be triggered reliably.
  @SuppressWarnings("unchecked")
  private static List<Closeable> ownedListeners(JircdServerApplication application)
      throws Exception {
    var field = JircdServerApplication.class.getDeclaredField("ownedListeners");
    field.setAccessible(true);
    return (List<Closeable>) field.get(application);
  }

  private static void assertBound(int port) {
    assertThatThrownBy(
            () -> {
              try (var socket =
                  new ServerSocket(port, 1, InetAddress.ofLiteral(TestServer.LOOPBACK))) {
                socket.getLocalPort();
              }
            })
        .isInstanceOf(BindException.class);
  }

  private static void assertAvailable(int port) throws IOException {
    try (var socket = new ServerSocket(port, 1, InetAddress.ofLiteral(TestServer.LOOPBACK))) {
      assertThat(socket.getLocalPort()).isEqualTo(port);
    }
  }

  // Bind to loopback, not the wildcard: on macOS a more specific bind of the same port by another
  // process wins the connect, and accept() would then wait forever. The timeout bounds that case.
  private static ServerSocket loopbackListener() throws IOException {
    ServerSocket listener = new ServerSocket(0, 1, InetAddress.ofLiteral(TestServer.LOOPBACK));
    listener.setSoTimeout(5000);
    return listener;
  }
}
