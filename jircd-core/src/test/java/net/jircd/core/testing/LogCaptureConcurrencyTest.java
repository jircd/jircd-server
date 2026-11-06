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
package net.jircd.core.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.jircd.core.session.ConnectionMonitorLog;
import org.junit.jupiter.api.Test;

/** Issue #124: {@link LogCapture} must stay consistent while server threads are still logging. */
class LogCaptureConcurrencyTest {

    private static final int WRITERS = 4;
    private static final int LINES_PER_WRITER = 500;

    @Test
    void readersSeeConsistentSnapshotsAndEveryLoggedLineWhileWritersLog() throws Exception {
        try (ExecutorService writers = Executors.newFixedThreadPool(WRITERS);
                LogCapture capture = new LogCapture(ConnectionMonitorLog.class)) {
            // Writers start only after the reader's first snapshot, so every later read in the
            // loop happens while at least one writer is still running.
            CountDownLatch readerStarted = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int writer = 0; writer < WRITERS; writer++) {
                int id = writer;
                done.add(
                        writers.submit(
                                () -> {
                                    readerStarted.await();
                                    for (int i = 0; i < LINES_PER_WRITER; i++) {
                                        ConnectionMonitorLog.sendqOverflow(
                                                "w" + id + "-" + i, 1, 2L);
                                    }
                                    return null;
                                }));
            }

            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            int previous = capture.messages().size();
            int overlappedReads = 0;
            readerStarted.countDown();
            while (!done.stream().allMatch(Future::isDone)) {
                assertThat(System.nanoTime())
                        .as("writers still running after 30 s; captured %d lines", previous)
                        .isLessThan(deadline);
                List<String> snapshot = capture.messages();
                assertThat(snapshot).hasSizeGreaterThanOrEqualTo(previous);
                previous = snapshot.size();
                capture.awaitMessage(m -> m.startsWith("connection-event"), Duration.ofSeconds(5));
                overlappedReads++;
            }
            assertThat(overlappedReads)
                    .as("reads completed while a writer was still running")
                    .isPositive();
            for (Future<?> future : done) {
                future.get(10, TimeUnit.SECONDS);
            }

            for (int writer = 0; writer < WRITERS; writer++) {
                String last = "connection=w" + writer + "-" + (LINES_PER_WRITER - 1) + " ";
                capture.awaitMessage(m -> m.contains(last), Duration.ofSeconds(5));
            }
            assertThat(capture.messages()).hasSize(WRITERS * LINES_PER_WRITER);
        }
    }
}
