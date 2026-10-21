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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectionLimitsTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void countsUnregisteredConnectionsAndReleasesAfterEofOrSetupFailure(boolean failOutput)
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Thread> owner = new AtomicReference<>();
    ExtensionRegistry extensions = new ExtensionRegistry();
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            extensions,
            () -> {});
    ConnectionHandler handler =
        new ConnectionHandler(
            extensions,
            cleanup,
            () -> "test.local",
            new ConnectionHandler.Settings(
                () -> new ServerConfiguration.RateLimit(100, 100),
                () -> 60,
                () -> 524288,
                () -> new ServerConfiguration.ConnectionLimits(2, 1)),
            socket -> {
              owner.set(Thread.currentThread());
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new AssertionError("release not signalled");
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
              }
            });
    try (TestSocket first = new TestSocket("127.0.0.1", failOutput);
        TestSocket sameIp = new TestSocket("127.0.0.1", false);
        TestSocket otherIp = new TestSocket("127.0.0.2", false);
        TestSocket thirdIp = new TestSocket("127.0.0.3", false)) {
      try {
        handler.accept(first);
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread firstOwner = owner.get();
        handler.accept(sameIp);
        assertRejected(sameIp);
        handler.accept(otherIp);
        assertThat(otherIp.isClosed()).isFalse();
        assertThat(otherIp.output.toString(StandardCharsets.UTF_8)).isEmpty();
        handler.accept(thirdIp);
        assertRejected(thirdIp);
        release.countDown();
        assertThat(firstOwner.join(Duration.ofSeconds(5))).isTrue();
        // The released IP gets a fresh reservation. No QUIT/registration was required.
        try (TestSocket replacement = new TestSocket("127.0.0.1", false)) {
          handler.accept(replacement);
          handler.closeConnections(Duration.ofSeconds(5));
          assertThat(replacement.output.toString(StandardCharsets.UTF_8))
              .doesNotContain("Too many connections");
        }
      } finally {
        release.countDown();
        handler.closeConnections(Duration.ofSeconds(5));
      }
    }
  }

  // Socket aliases below are borrowed; each RejectionBatch owns and closes its entire collection.
  @SuppressWarnings("PMD.CloseResource")
  @Test
  void rejectionCapacityClosesOverflowImmediatelyAndReleasesPermits() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch releaseAdmitted = new CountDownLatch(1);
    ExtensionRegistry extensions = new ExtensionRegistry();
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            extensions,
            () -> {});
    ConnectionHandler handler =
        new ConnectionHandler(
            extensions,
            cleanup,
            () -> "test.local",
            new ConnectionHandler.Settings(
                () -> new ServerConfiguration.RateLimit(100, 100),
                () -> 60,
                () -> 524288,
                () -> new ServerConfiguration.ConnectionLimits(1, 1)),
            socket -> {
              try {
                releaseAdmitted.await();
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            });
    try (TestSocket admitted = new TestSocket("127.0.0.1", false);
        TestSocket overflow = new TestSocket("127.0.0.1", false);
        RejectionBatch secondBatch = new RejectionBatch(releaseAdmitted)) {
      handler.accept(admitted);
      try (RejectionBatch firstBatch = new RejectionBatch(release)) {
        for (int i = 0; i < 64; i++) {
          BlockingRejectionSocket socket = firstBatch.open();
          Thread acceptor = Thread.ofVirtual().start(() -> handler.accept(socket));
          firstBatch.acceptors.add(acceptor);
          // accept must return while the rejection write is still blocked.
          assertThat(acceptor.join(Duration.ofSeconds(10))).isTrue();
          assertThat(socket.writing.await(5, TimeUnit.SECONDS)).isTrue();
          assertThat(release.getCount()).isEqualTo(1);
          assertThat(socket.worker.get()).isNotEqualTo(acceptor);
        }
        handler.accept(overflow);
        assertThat(overflow.isClosed()).isTrue();
        assertThat(overflow.output.toString(StandardCharsets.UTF_8)).isEmpty();
      }
      // With the original admitted connection still held, every freed permit can write again.
      for (int i = 0; i < 64; i++) {
        BlockingRejectionSocket socket = secondBatch.open();
        handler.accept(socket);
        assertThat(socket.writing.await(5, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      releaseAdmitted.countDown();
      handler.closeConnections(Duration.ofSeconds(5));
    }
  }

  private static final class RejectionBatch implements AutoCloseable {
    private final CountDownLatch release;
    private final List<BlockingRejectionSocket> sockets = new ArrayList<>();
    private final List<Thread> acceptors = new ArrayList<>();

    RejectionBatch(CountDownLatch release) {
      this.release = release;
    }

    // The batch owns every returned socket and closes it after unblocking its worker.
    @SuppressWarnings("PMD.CloseResource")
    BlockingRejectionSocket open() {
      BlockingRejectionSocket socket = new BlockingRejectionSocket(release);
      sockets.add(socket);
      return socket;
    }

    @Override
    public void close() throws Exception {
      release.countDown();
      for (Thread acceptor : acceptors) assertThat(acceptor.join(Duration.ofSeconds(5))).isTrue();
      for (BlockingRejectionSocket socket : sockets) {
        try (socket) {
          Thread worker = socket.worker.get();
          if (worker != null) assertThat(worker.join(Duration.ofSeconds(5))).isTrue();
        }
      }
    }
  }

  private static void assertRejected(TestSocket socket) throws InterruptedException {
    assertThat(socket.closed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(socket.output.toString(StandardCharsets.UTF_8))
        .isEqualTo("ERROR :Too many connections\r\n");
    assertThat(socket.isClosed()).isTrue();
  }

  private static final class BlockingRejectionSocket extends TestSocket {
    private final CountDownLatch writing = new CountDownLatch(1);
    private final AtomicReference<Thread> worker = new AtomicReference<>();
    private final CountDownLatch release;

    BlockingRejectionSocket(CountDownLatch release) {
      super("127.0.0.1", false);
      this.release = release;
    }

    @Override
    public OutputStream getOutputStream() {
      return new OutputStream() {
        @Override
        public void write(int value) throws IOException {
          worker.set(Thread.currentThread());
          writing.countDown();
          try {
            // Deliberately stay blocked after close: capacity testing must not race the deadline.
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
        }
      };
    }
  }

  private static class TestSocket extends Socket {
    private final CountDownLatch closed = new CountDownLatch(1);
    private final InetAddress address;
    private final boolean failOutput;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    TestSocket(String address, boolean failOutput) {
      this.address = InetAddress.ofLiteral(address);
      this.failOutput = failOutput;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        closed.countDown();
      }
    }

    @Override
    public InetAddress getInetAddress() {
      return address;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
      if (failOutput) throw new IOException("setup failure");
      return output;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }
  }
}
