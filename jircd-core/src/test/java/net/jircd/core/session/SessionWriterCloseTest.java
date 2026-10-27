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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/** {@link SessionWriter#close()} called twice, as DisconnectCleanup and the liveness thread do. */
class SessionWriterCloseTest {

    private static final int LIMIT_BYTES = 16384;
    private static final int LINES = 1 + LIMIT_BYTES / 512;

    @Test
    void writeFailureCallbackClosesWithoutWaitingForItsOwnWriterThread() throws Exception {
        CountDownLatch callbackReturned = new CountDownLatch(1);
        OutputStream failing =
                new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("socket closed");
                    }
                };
        try (failing;
                SessionWriter writer =
                        new SessionWriter(
                                new ClientSession(
                                        "failure", "host.example", RateLimitBucket.withDefaults()),
                                failing,
                                TagRenderer.NONE)) {
            writer.setOnWriteFailure(
                    () -> {
                        writer.close();
                        callbackReturned.countDown();
                    });
            writer.enqueueRaw(line(0));
            assertThat(callbackReturned.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void secondCloseOnAFullQueueWaitsForTheDrainInsteadOfInterrupting() throws Exception {
        try (BlockingOutputStream out = new BlockingOutputStream();
                SessionWriter writer =
                        new SessionWriter(
                                new ClientSession(
                                        "c1", "host.example", RateLimitBucket.withDefaults()),
                                out,
                                TagRenderer.NONE,
                                LIMIT_BYTES)) {
            writer.enqueueRaw(line(0));
            out.firstWriteStarted.await();
            for (int i = 1; i < LINES; i++) {
                writer.enqueueRaw(line(i));
            }

            // The poison pill uses its reserved closing allowance outside the data budget.
            Thread firstClose = Thread.ofPlatform().start(writer::close);
            awaitWaiting(firstClose);
            Thread secondClose = Thread.ofPlatform().start(writer::close);
            awaitWaitingOrInterruptedTheWriter(secondClose, out);
            out.release.countDown();
            firstClose.join();
            secondClose.join();

            assertThat(out.lines())
                    .hasSize(LINES)
                    .endsWith("ERROR " + ("line" + (LINES - 1)).repeat(100).substring(0, 504));
        }
    }

    private static Message line(int number) {
        return new Message(
                Map.of(),
                null,
                Command.ERROR,
                "ERROR",
                List.of(("line" + number).repeat(101).substring(0, 504)));
    }

    private static void awaitWaiting(Thread thread) {
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            Thread.onSpinWait();
        }
    }

    /**
     * After the fix, the second close waits for the drain. Before it, the second close interrupted
     * the writer and returned; wait until that interrupt has reached the stream, so the release
     * below cannot beat it.
     */
    private static void awaitWaitingOrInterruptedTheWriter(Thread thread, BlockingOutputStream out)
            throws InterruptedException {
        while (thread.getState() != Thread.State.TIMED_WAITING
                && thread.getState() != Thread.State.TERMINATED) {
            Thread.onSpinWait();
        }
        if (thread.getState() == Thread.State.TERMINATED) {
            out.interrupted.await();
        }
    }

    /**
     * Blocks the first write until released. An interrupt closes it for good, as it does a socket
     * channel ({@link java.nio.channels.ClosedByInterruptException}).
     */
    private static final class BlockingOutputStream extends OutputStream {
        final CountDownLatch firstWriteStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
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
            } catch (InterruptedException _) {
                closedByInterrupt = true;
                interrupted.countDown();
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
