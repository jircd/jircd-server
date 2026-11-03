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
package net.jircd.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Overlapping reloads must leave the logging of the last published configuration applied. */
class LoggingReloadOrderTest {

    @TempDir Path dir;

    private String yaml(String logFile) {
        return "serverName: test.jircd.local\nlisteners:\n  - port: 0\n    bindAddress: 127.0.0.1\n"
                + "    tls: false\nlogging:\n  file: \""
                + dir.resolve(logFile)
                + "\"\n";
    }

    @Test
    void aReloadThatAppliesLateStillAppliesTheLatestPublishedLogging() throws Exception {
        Path config = Files.writeString(dir.resolve("jircd.yaml"), yaml("start.log"));
        List<Path> applied = new CopyOnWriteArrayList<>();
        AtomicReference<CountDownLatch> holdNext = new AtomicReference<>();
        CountDownLatch firstIsApplying = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        JircdServerApplication application =
                new JircdServerApplication(
                        config,
                        socket -> {},
                        Duration.ofSeconds(1),
                        logging -> {
                            if (holdNext.getAndSet(null) != null) {
                                firstIsApplying.countDown();
                                try {
                                    releaseFirst.await();
                                } catch (InterruptedException _) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            // Recorded when the apply finishes: that is the order effects land.
                            applied.add(logging.file());
                        });
        holdNext.set(new CountDownLatch(1));

        Files.writeString(config, yaml("a.log"));
        CompletableFuture<?> reloadA = CompletableFuture.runAsync(() -> reload(application));
        assertThat(firstIsApplying.await(10, TimeUnit.SECONDS)).isTrue();

        Files.writeString(config, yaml("b.log"));
        CompletableFuture<?> reloadB = CompletableFuture.runAsync(() -> reload(application));
        // B is published (current() is B) while A's apply is still held up.
        assertThat(
                        untilTrue(
                                Duration.ofSeconds(10),
                                () ->
                                        application
                                                .reloader()
                                                .current()
                                                .logging()
                                                .file()
                                                .endsWith("b.log")))
                .isTrue();
        releaseFirst.countDown();
        reloadA.get(10, TimeUnit.SECONDS);
        reloadB.get(10, TimeUnit.SECONDS);

        assertThat(applied.getLast().getFileName()).hasToString("b.log");
    }

    /** Checks until the condition holds or the timeout passes, pausing with a timed park. */
    private static boolean untilTrue(Duration timeout, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) return false;
            long pauseEnd = System.nanoTime() + Duration.ofMillis(20).toNanos();
            long left;
            while ((left = pauseEnd - System.nanoTime()) > 0) {
                LockSupport.parkNanos(left);
                if (Thread.interrupted()) throw new InterruptedException();
            }
        }
        return true;
    }

    private static void reload(JircdServerApplication application) {
        try {
            application.reloader().reload();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
