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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jircd.core.session.SessionWriter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** All readers survive one 2,000-client JOIN wave; latency belongs to JoinStormLoadTest. */
@Tag("load")
@Timeout(120)
class SingleWaveJoinStormLoadTest {
    private static final int CLIENTS = 2000;
    private static final int CONNECT_CONCURRENCY = 25;

    // ClientPool owns all borrowed sockets, including connects still running during failure.
    @SuppressWarnings("PMD.CloseResource")
    @Test
    void allReadersSurviveOneWave() throws Exception {
        AtomicBoolean stormOver = new AtomicBoolean();
        CompletableFuture<Void> failure = new CompletableFuture<>();
        var threads = Executors.newVirtualThreadPerTaskExecutor();
        try (threads;
                TestServer server =
                        TestServer.start("maxConnections: 10000\nmaxConnectionsPerIp: 10000\n");
                var clients = new JoinStormLoadTest.ClientPool();
                LogCapture overflow = new LogCapture(SessionWriter.class)) {
            try {
                Semaphore slots = new Semaphore(CONNECT_CONCURRENCY);
                List<CompletableFuture<RawIrcClient>> connecting = new ArrayList<>();
                for (int i = 0; i < CLIENTS; i++) {
                    int index = i;
                    connecting.add(
                            watch(
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    slots.acquire();
                                                    try {
                                                        RawIrcClient client =
                                                                clients.connect(
                                                                        server.plaintextPort());
                                                        client.registerAndAwaitWelcome(
                                                                "storm" + index, "storm" + index);
                                                        return client;
                                                    } finally {
                                                        slots.release();
                                                    }
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new CompletionException(e);
                                                } catch (java.io.IOException e) {
                                                    throw new CompletionException(e);
                                                }
                                            },
                                            threads),
                                    failure));
                }
                awaitAll(connecting, failure);
                CountDownLatch gate = new CountDownLatch(1);
                List<CompletableFuture<Void>> joined = new ArrayList<>();
                List<CompletableFuture<Void>> draining = new ArrayList<>();
                for (var connection : connecting) {
                    RawIrcClient client = connection.join();
                    CompletableFuture<Void> endOfNames = new CompletableFuture<>();
                    joined.add(endOfNames);
                    draining.add(
                            watch(
                                    CompletableFuture.runAsync(
                                            () -> {
                                                try {
                                                    gate.await();
                                                    client.send("JOIN #storm");
                                                    JoinStormLoadTest.awaitEndOfNames(client);
                                                    endOfNames.complete(null);
                                                    JoinStormLoadTest.drainUntil(client, stormOver);
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new CompletionException(e);
                                                } catch (java.io.IOException e) {
                                                    throw new CompletionException(e);
                                                }
                                            },
                                            threads),
                                    failure));
                }
                long start = System.nanoTime();
                gate.countDown();
                awaitAll(joined, failure);
                assertThat(server.application.nicknameRegistry().all())
                        .hasSize(CLIENTS)
                        .allMatch(session -> !session.lifecycle().isClosing());
                assertThat(overflow.messages())
                        .noneMatch(line -> line.contains("Outbound queue overflow"));
                if (failure.isDone()) failure.get();
                System.out.printf(
                        "SingleWaveJoinStormLoadTest: clients=%d completed=%d overflowDisconnects=0 stormMs=%d%n",
                        CLIENTS,
                        joined.size(),
                        Duration.ofNanos(System.nanoTime() - start).toMillis());
                stormOver.set(true);
                clients.close();
                CompletableFuture.allOf(draining.toArray(CompletableFuture[]::new))
                        .get(30, TimeUnit.SECONDS);
            } finally {
                stormOver.set(true);
                clients.close();
                threads.shutdownNow();
            }
        }
    }

    private static <T> CompletableFuture<T> watch(
            CompletableFuture<T> task, CompletableFuture<Void> failure) {
        task.whenComplete(
                (result, error) -> {
                    if (error != null) failure.completeExceptionally(error);
                });
        return task;
    }

    private static void awaitAll(
            List<? extends CompletableFuture<?>> tasks, CompletableFuture<Void> failure)
            throws Exception {
        CompletableFuture.anyOf(
                        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)), failure)
                .get(60, TimeUnit.SECONDS);
    }
}
