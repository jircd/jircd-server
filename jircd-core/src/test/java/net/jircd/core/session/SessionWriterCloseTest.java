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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/** {@link SessionWriter#close()} called twice, as DisconnectCleanup and the liveness thread do. */
class SessionWriterCloseTest {

  /** One line the writer takes off the queue and blocks on, plus enough to fill the queue. */
  private static final int LINES = 256;

  @Test
  void secondCloseOnAFullQueueWaitsForTheDrainInsteadOfInterrupting() throws Exception {
    try (BlockingOutputStream out = new BlockingOutputStream();
        SessionWriter writer =
            new SessionWriter(
                new ClientSession("c1", "host.example", RateLimitBucket.withDefaults()),
                out,
                TagRenderer.NONE)) {
      writer.enqueueRaw(line(0));
      out.firstWriteStarted.await();
      for (int i = 1; i < LINES; i++) {
        writer.enqueueRaw(line(i));
      }

      // The first close's poison pill takes the queue's last free slot.
      Thread firstClose = Thread.ofPlatform().start(writer::close);
      awaitWaiting(firstClose);
      Thread secondClose = Thread.ofPlatform().start(writer::close);
      awaitWaitingOrDone(secondClose);
      out.release.countDown();
      firstClose.join();
      secondClose.join();

      assertThat(out.lines()).hasSize(LINES).endsWith("ERROR :line " + (LINES - 1));
    }
  }

  private static Message line(int number) {
    return new Message(Map.of(), null, Command.ERROR, "ERROR", List.of("line " + number));
  }

  private static void awaitWaiting(Thread thread) throws InterruptedException {
    while (thread.getState() != Thread.State.TIMED_WAITING) {
      Thread.sleep(1);
    }
  }

  /** Before the fix, the second close interrupts the writer and returns without waiting. */
  private static void awaitWaitingOrDone(Thread thread) throws InterruptedException {
    while (thread.getState() != Thread.State.TIMED_WAITING
        && thread.getState() != Thread.State.TERMINATED) {
      Thread.sleep(1);
    }
    thread.join(Duration.ofMillis(100));
  }

  /**
   * Blocks the first write until released. An interrupt closes it for good, as it does a socket
   * channel ({@link java.nio.channels.ClosedByInterruptException}).
   */
  private static final class BlockingOutputStream extends OutputStream {
    final CountDownLatch firstWriteStarted = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private volatile boolean closedByInterrupt;

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
      firstWriteStarted.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        closedByInterrupt = true;
      }
      if (closedByInterrupt) {
        throw new InterruptedIOException("closed by interrupt");
      }
      written.write(bytes, offset, length);
    }

    synchronized List<String> lines() {
      String text = written.toString(StandardCharsets.UTF_8);
      return text.isEmpty() ? List.of() : List.of(text.split("\r\n"));
    }
  }
}
