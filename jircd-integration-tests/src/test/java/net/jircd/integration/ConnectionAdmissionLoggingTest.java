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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.session.SecurityEventLog;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ConnectionAdmissionLoggingTest {
  // Socket aliases are borrowed from pending, whose try-with-resources scope owns them all.
  @SuppressWarnings("PMD.CloseResource")
  @Test
  void slowRejectionLoggingDoesNotBlockAdmissionAndOverflowIsSummarized() throws Exception {
    AtomicReference<Thread> owner = new AtomicReference<>();
    try (TestServer server =
            TestServer.start("maxConnections: 1\n", socket -> owner.set(Thread.currentThread()));
        BlockingSecurityLog logs = new BlockingSecurityLog();
        RawIrcClient first = RawIrcClient.connectTls(server.tlsPort());
        Sockets pending = new Sockets()) {
      first.registerAndAwaitWelcome("first", "first");
      Thread firstOwner = owner.get();
      for (int i = 0; i < 64; i++) pending.open(server.tlsPort());
      assertThat(logs.allWorkersLogging.await(5, TimeUnit.SECONDS)).isTrue();
      // All rejection workers are blocked inside the appender. The next drops must only count.
      for (int i = 0; i < 20; i++) {
        Socket dropped = pending.open(server.tlsPort());
        dropped.setSoTimeout(5000);
        assertThat(dropped.getInputStream().read()).isEqualTo(-1);
      }
      first.close();
      assertThat(firstOwner.join(Duration.ofSeconds(10))).isTrue();
      try (RawIrcClient valid = RawIrcClient.connectTls(server.tlsPort())) {
        valid.registerAndAwaitWelcome("valid", "valid");
        // Registration completed before any blocked rejection log was allowed to return.
        assertThat(logs.release.getCount()).isEqualTo(1);
        assertThat(logs.allWorkersLogging.getCount()).isZero();
        assertThat(logs.individualEvents.get()).isEqualTo(64);
      }
      assertThat(logs.summaries.poll(3, TimeUnit.SECONDS))
          .isEqualTo("security-event=connection-limit-suppressed count=20");
      assertThat(logs.individualEvents.get()).isEqualTo(64);
    }
  }

  private static final class BlockingSecurityLog extends UnsynchronizedAppenderBase<ILoggingEvent>
      implements AutoCloseable {
    private final Logger logger = (Logger) LoggerFactory.getLogger(SecurityEventLog.class);
    private final CountDownLatch allWorkersLogging = new CountDownLatch(64);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger individualEvents = new AtomicInteger();
    private final BlockingQueue<String> summaries = new LinkedBlockingQueue<>();

    BlockingSecurityLog() {
      start();
      logger.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
      String message = event.getFormattedMessage();
      if (message.startsWith("security-event=connection-limit-suppressed")) {
        summaries.add(message);
      } else if (message.startsWith("security-event=connection-limit ")) {
        individualEvents.incrementAndGet();
        allWorkersLogging.countDown();
        try {
          release.await();
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public void close() {
      release.countDown();
      logger.detachAppender(this);
      stop();
    }
  }

  private static final class Sockets implements AutoCloseable {
    private final List<Socket> sockets = new ArrayList<>();

    // This owner retains each socket and closes the whole collection in close().
    @SuppressWarnings("PMD.CloseResource")
    Socket open(int port) throws IOException {
      Socket socket = new Socket(TestServer.LOOPBACK, port);
      sockets.add(socket);
      return socket;
    }

    // The loop closes every retained socket; PMD does not recognize aliases from the collection.
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void close() throws IOException {
      for (Socket socket : sockets) socket.close();
    }
  }
}
