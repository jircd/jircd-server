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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

class SessionWriterOverflowTest {
    @Test
    void overflowDiscardsBacklogReservesErrorAndPillAndIgnoresLaterEnqueues() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        GatedOutput out = new GatedOutput();
        try (out;
                SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, 16)) {
            writer.setOnSendqOverflow(callbacks::incrementAndGet);
            writer.enqueueRaw(Message.of(Command.PING, "first"));
            assertThat(out.started.await(1, TimeUnit.SECONDS)).isTrue();
            try {
                writer.enqueueRaw(Message.of(Command.PING, "backlog"));
                writer.enqueueRaw(Message.of(Command.PING, "overflow"));
                writer.enqueueRaw(Message.of(Command.PING, "ignored"));
                writer.enqueue(OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "ignored"));
                assertThat(callbacks).hasValue(1);
                // 14 (backlog) + 15 (rejected); the in-flight line was already released at dequeue.
                assertThat(writer.overflowQueuedBytes()).isEqualTo(29);
            } finally {
                out.release.countDown();
            }
            writer.close();
            assertThat(out.text())
                    .isEqualTo("PING first\r\nERROR :Closing Link: 192.0.2.1 (SendQ exceeded)\r\n");
            assertThat(writer.queuedBytes()).isZero();
        }
    }

    @Test
    void eightRacingEnqueuersDispatchOnceAndReturnWhileTheStreamIsBlocked() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        GatedOutput out = new GatedOutput();
        try (out;
                SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, 16);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            writer.setOnSendqOverflow(callbacks::incrementAndGet);
            writer.enqueueRaw(Message.of(Command.PING, "first"));
            assertThat(out.started.await(1, TimeUnit.SECONDS)).isTrue();
            CountDownLatch gate = new CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int i = 0; i < 8; i++)
                    tasks.add(
                            threads.submit(
                                    () -> {
                                        try {
                                            gate.await();
                                        } catch (InterruptedException _) {
                                            Thread.currentThread().interrupt();
                                        }
                                        writer.enqueueRaw(Message.of(Command.PING, "x".repeat(32)));
                                    }));
                gate.countDown();
                for (var task : tasks) task.get(250, TimeUnit.MILLISECONDS);
                assertThat(callbacks).hasValue(1);
            } finally {
                out.release.countDown();
            }
        }
    }

    @Test
    void failingBestEffortErrorCannotInvokeTheUnrelatedWriteFailureCallback() throws Exception {
        AtomicInteger sendq = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        GatedOutput out = new GatedOutput();
        out.failWrites = true;
        try (out;
                SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, 16)) {
            writer.setOnSendqOverflow(sendq::incrementAndGet);
            writer.setOnWriteFailure(failures::incrementAndGet);
            writer.enqueueRaw(Message.of(Command.PING, "first"));
            assertThat(out.started.await(1, TimeUnit.SECONDS)).isTrue();
            try {
                writer.enqueueRaw(Message.of(Command.PING, "x".repeat(32)));
            } finally {
                out.release.countDown();
            }
            writer.close();
            assertThat(sendq).hasValue(1);
            assertThat(failures).hasValue(0);
        }
    }

    private static ClientSession session() {
        return new ClientSession("overflow", "192.0.2.1", RateLimitBucket.withDefaults());
    }

    private static final class GatedOutput extends OutputStream {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        boolean failWrites;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b});
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
            if (failWrites) throw new IOException("closed socket");
            bytes.write(data, offset, length);
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
