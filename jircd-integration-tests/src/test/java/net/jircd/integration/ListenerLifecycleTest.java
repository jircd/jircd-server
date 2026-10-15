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
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.server.JircdServerApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ListenerLifecycleTest {

  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stopClosesEveryListenerOfTheSameType(boolean tls) throws Exception {
    try (ServerSocket firstReservation = new ServerSocket(0);
        ServerSocket secondReservation = new ServerSocket(0)) {
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
    try (ServerSocket firstReservation = new ServerSocket(0);
        ServerSocket occupied = new ServerSocket(0)) {
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
    try (var reservation = new ServerSocket(0)) {
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
    try (var occupied = new ServerSocket(0)) {
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
    return "  - port: "
        + port
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
              try (var socket = new ServerSocket(port)) {
                socket.getLocalPort();
              }
            })
        .isInstanceOf(BindException.class);
  }

  private static void assertAvailable(int port) throws IOException {
    try (var socket = new ServerSocket(port)) {
      assertThat(socket.getLocalPort()).isEqualTo(port);
    }
  }
}
