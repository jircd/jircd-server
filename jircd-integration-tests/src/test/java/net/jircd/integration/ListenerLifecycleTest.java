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
import java.util.List;
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
  void stopContinuesAfterCloseFailuresAndDoesNotCloseResourcesTwice() throws Exception {
    var application = application(listener(0, false));
    var firstFailure = new IOException("first close failure");
    var secondFailure = new IOException("second close failure");
    AtomicInteger attempts = new AtomicInteger();
    ownedListeners(application)
        .add(
            () -> {
              attempts.incrementAndGet();
              throw firstFailure;
            });
    ownedListeners(application)
        .add(
            () -> {
              attempts.incrementAndGet();
              throw secondFailure;
            });
    ownedListeners(application).add(() -> attempts.incrementAndGet());

    assertThatThrownBy(application::stop)
        .isSameAs(firstFailure)
        .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(secondFailure));
    assertThat(attempts.get()).isEqualTo(3);
    assertThatCode(application::stop).doesNotThrowAnyException();
    assertThat(attempts.get()).isEqualTo(3);
  }

  @Test
  void rollbackPreservesStartupFailureWhenCleanupFails() throws Exception {
    try (ServerSocket occupied = new ServerSocket(0)) {
      var application = application(listener(occupied.getLocalPort(), false));
      var cleanupFailure = new IOException("rollback close failure");
      ownedListeners(application)
          .add(
              () -> {
                throw cleanupFailure;
              });
      assertThatThrownBy(application::start)
          .isInstanceOf(BindException.class)
          .satisfies(
              failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
      assertThatCode(application::stop).doesNotThrowAnyException();
    }
  }

  @Test
  void stopBeforeStartIsHarmless() throws Exception {
    var application = application(listener(0, false));
    application.stop();
    application.stop();
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
