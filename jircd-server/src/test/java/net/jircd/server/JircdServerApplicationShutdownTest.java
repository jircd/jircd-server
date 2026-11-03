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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The shutdown budget (spec 016 FR-009) against a lifecycle that is busy. */
class JircdServerApplicationShutdownTest {

    @TempDir Path dir;

    @Test
    void aSlowStartDoesNotPushShutdownPastItsBudget() throws Exception {
        Path config =
                Files.writeString(
                        dir.resolve("jircd.yaml"),
                        """
                        serverName: test.jircd.local
                        listeners:
                          - port: 0
                            bindAddress: 127.0.0.1
                            tls: false
                        """);
        JircdServerApplication application = new JircdServerApplication(config);
        ReentrantLock lifecycle = application.lifecycleLockForTesting();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Stands in for a start() that is still bringing listeners up.
        Thread slowStart =
                Thread.ofPlatform()
                        .start(
                                () -> {
                                    lifecycle.lock();
                                    try {
                                        held.countDown();
                                        release.await();
                                    } catch (InterruptedException _) {
                                        Thread.currentThread().interrupt();
                                    } finally {
                                        lifecycle.unlock();
                                    }
                                });
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Duration budget = Duration.ofMillis(800);
            long begun = System.nanoTime();

            CompletableFuture<Integer> shutdown =
                    CompletableFuture.supplyAsync(() -> application.shutdown("bye", budget));

            // It must finish on its own while start() still holds the lifecycle.
            assertThat(shutdown.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(Duration.ofNanos(System.nanoTime() - begun))
                    .isLessThan(budget.plus(Duration.ofSeconds(2)));
        } finally {
            release.countDown();
            slowStart.join();
        }
    }

    private JircdServerApplication application() throws Exception {
        Path config =
                Files.writeString(
                        dir.resolve("jircd.yaml"),
                        """
                        serverName: test.jircd.local
                        listeners:
                          - port: 0
                            bindAddress: 127.0.0.1
                            tls: false
                        """);
        return new JircdServerApplication(config);
    }

    /**
     * Runs start() until it reaches {@code pauseAt}, lets a shutdown with a short budget finish
     * meanwhile, then lets start() go on. Returns the application once start() has returned.
     */
    private JircdServerApplication startPausedAcrossAShutdown(
            JircdServerApplication.StartPhase pauseAt) throws Exception {
        JircdServerApplication application = application();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        application.onStartPhaseForTesting(
                phase -> {
                    if (phase != pauseAt) return;
                    paused.countDown();
                    try {
                        resume.await();
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                });
        CompletableFuture<Void> start =
                CompletableFuture.runAsync(
                        () -> {
                            try {
                                application.start();
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
        try {
            assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
            // start() holds the lifecycle, so this shutdown drains, gives up waiting for the
            // lifecycle when its budget runs out, and returns before start() goes on.
            assertThat(application.shutdown("bye", Duration.ofMillis(300))).isZero();
        } finally {
            resume.countDown();
        }
        start.get(10, TimeUnit.SECONDS);
        return application;
    }

    @Test
    void aStartPausedAfterItsShutdownCheckCannotReopenAdmission() throws Exception {
        JircdServerApplication application =
                startPausedAcrossAShutdown(JircdServerApplication.StartPhase.CHECKED_FOR_SHUTDOWN);

        assertThat(application.connectionHandlerForTesting().isAccepting()).isFalse();
        assertThat(application.ownedListenerCountForTesting()).isZero();
    }

    @Test
    void aStartPausedBeforeOpeningListenersClosesThemOnceShutdownHasBegun() throws Exception {
        JircdServerApplication application =
                startPausedAcrossAShutdown(JircdServerApplication.StartPhase.ACCEPTING);

        assertThat(application.connectionHandlerForTesting().isAccepting()).isFalse();
        assertThat(application.ownedListenerCountForTesting()).isZero();
    }
}
