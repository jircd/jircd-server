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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AbstractListenerTest {

  @Test
  void closeReturnsWhenAcceptThreadDoesNotExitWithinTimeout() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closed = new AtomicBoolean();
    var acceptThread = new AtomicReference<Thread>();
    var listener =
        new AbstractListener<Closeable>(
            () -> closed.set(true),
            socket -> fail("No connection expected"),
            "blocked-listener",
            Duration.ofMillis(50)) {
          @Override
          protected Socket accept() throws IOException {
            acceptThread.set(Thread.currentThread());
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
            throw new IOException("Accept released after socket closure");
          }

          @Override
          public int boundPort() {
            return 0;
          }
        };
    try (listener) {
      try {
        listener.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertTimeoutPreemptively(Duration.ofSeconds(1), listener::close);
        assertThat(closed).isTrue();
        assertThat(acceptThread.get().isAlive()).isTrue();
      } finally {
        release.countDown();
      }
    }
    assertThat(acceptThread.get().join(Duration.ofSeconds(1))).isTrue();
  }

  @Test
  void socketCloseFailureStillJoinsAndSuppressesJoinFailure() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closeFailure = new IOException("socket close failure");
    var acceptThread = new AtomicReference<Thread>();
    var closeAttempted = new AtomicBoolean();
    var listener =
        new AbstractListener<Closeable>(
            () -> {
              if (!closeAttempted.getAndSet(true)) {
                throw closeFailure;
              }
            },
            socket -> fail("No connection expected"),
            "failing-listener",
            Duration.ofMillis(50)) {
          @Override
          protected Socket accept() throws IOException {
            acceptThread.set(Thread.currentThread());
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
            throw new IOException("Released");
          }

          @Override
          public int boundPort() {
            return 0;
          }
        };
    try (listener) {
      try {
        listener.start();
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.currentThread().interrupt();
        assertThatThrownBy(listener::close)
            .isSameAs(closeFailure)
            .satisfies(
                failure -> {
                  assertThat(failure.getSuppressed()).hasSize(1);
                  assertThat(failure.getSuppressed()[0])
                      .isInstanceOf(IOException.class)
                      .hasCauseInstanceOf(InterruptedException.class);
                });
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
      } finally {
        Thread.interrupted();
        release.countDown();
      }
    }
    assertThat(acceptThread.get().join(Duration.ofSeconds(1))).isTrue();
  }
}
