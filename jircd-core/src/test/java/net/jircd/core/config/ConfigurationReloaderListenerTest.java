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
package net.jircd.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.testing.LogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

/**
 * Reload listeners (spec 016 FR-006b): how the application learns about a configuration that was
 * applied, whichever path reloaded it (SIGHUP or {@code REHASH} both call {@link
 * ConfigurationReloader#reloadWithOutcome()}).
 */
class ConfigurationReloaderListenerTest {

    private static final String HEADER = "serverName: test.jircd.local\n";

    @TempDir Path dir;

    private Path config;
    private ConfigurationReloader reloader;
    private LogCapture logs;

    @BeforeEach
    void start() throws Exception {
        config = Files.writeString(dir.resolve("jircd.yaml"), HEADER);
        ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of());
        reloader =
                new ConfigurationReloader(
                        config, loader, new ExtensionRegistry(), loader.load(config));
        logs = new LogCapture(ConfigurationReloader.class);
    }

    @AfterEach
    void stopCapturing() {
        logs.close();
    }

    @Test
    void aListenerGetsTheAppliedConfigurationOnceAfterItIsPublished() throws Exception {
        List<ServerConfiguration> seen = new CopyOnWriteArrayList<>();
        List<ServerConfiguration> currentDuringCall = new CopyOnWriteArrayList<>();
        reloader.addReloadListener(
                applied -> {
                    seen.add(applied);
                    currentDuringCall.add(reloader.current());
                });
        Files.writeString(config, HEADER + "topicMaxLength: 123\n");

        ServerConfiguration applied = reloader.reloadWithOutcome().configuration();

        assertThat(seen).containsExactly(applied);
        assertThat(applied.topicMaxLength()).isEqualTo(123);
        assertThat(currentDuringCall).containsExactly(applied);
    }

    @Test
    void reloadNotifiesToo() throws Exception {
        List<ServerConfiguration> seen = new CopyOnWriteArrayList<>();
        reloader.addReloadListener(seen::add);

        reloader.reload();
        reloader.reload();

        assertThat(seen).hasSize(2);
    }

    @Test
    void aRejectedReloadNotifiesNobody() throws Exception {
        List<ServerConfiguration> seen = new CopyOnWriteArrayList<>();
        reloader.addReloadListener(seen::add);
        ServerConfiguration before = reloader.current();
        Files.writeString(config, HEADER + "logging:\n  console: false\n");

        assertThatThrownBy(reloader::reloadWithOutcome).isInstanceOf(ConfigurationException.class);

        assertThat(seen).isEmpty();
        assertThat(reloader.current()).isSameAs(before);
    }

    @Test
    void aFailingListenerIsLoggedAndNeitherUndoesTheReloadNorStopsTheOthers() throws Exception {
        List<ServerConfiguration> later = new CopyOnWriteArrayList<>();
        reloader.addReloadListener(
                applied -> {
                    throw new IllegalStateException("listener broke");
                });
        reloader.addReloadListener(later::add);
        Files.writeString(config, HEADER + "topicMaxLength: 200\n");

        ServerConfiguration applied = reloader.reloadWithOutcome().configuration();

        assertThat(reloader.current()).isSameAs(applied);
        assertThat(later).containsExactly(applied);
        assertThat(logs.events())
                .anySatisfy(
                        e -> {
                            assertThat(e.level()).isEqualTo(Level.WARN);
                            assertThat(e.throwableMessage()).isEqualTo("listener broke");
                        });
    }

    @Test
    void listenersRunOutsideTheReloadSoTheyCannotDeadlockAnotherReload() throws Exception {
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicBoolean otherReloadFinished = new AtomicBoolean();
        reloader.addReloadListener(
                applied -> {
                    if (!first.getAndSet(false)) return;
                    Thread other =
                            Thread.ofVirtual()
                                    .start(
                                            () -> {
                                                try {
                                                    reloader.reload();
                                                    otherReloadFinished.set(true);
                                                } catch (Exception e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            });
                    try {
                        other.join(TimeUnit.SECONDS.toMillis(5));
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    }
                });

        reloader.reload();

        assertThat(otherReloadFinished).isTrue();
    }

    @Test
    void anOlderReloadNeverDeliversItsConfigurationAfterANewerOne() throws Exception {
        CountDownLatch firstInListener = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        List<Integer> delivered = new CopyOnWriteArrayList<>();
        reloader.addReloadListener(
                applied -> {
                    if (firstCall.getAndSet(false)) {
                        firstInListener.countDown();
                        await(releaseFirst);
                    }
                });
        reloader.addReloadListener(applied -> delivered.add(applied.topicMaxLength()));

        Files.writeString(config, HEADER + "topicMaxLength: 111\n");
        Thread a = Thread.ofVirtual().start(this::reloadQuietly);
        assertThat(firstInListener.await(5, TimeUnit.SECONDS)).isTrue();
        Files.writeString(config, HEADER + "topicMaxLength: 222\n");
        Thread b = Thread.ofVirtual().start(this::reloadQuietly);
        b.join(TimeUnit.SECONDS.toMillis(5));
        releaseFirst.countDown();
        a.join(TimeUnit.SECONDS.toMillis(5));
        b.join(TimeUnit.SECONDS.toMillis(5));

        // Whatever the interleaving, the last configuration a listener holds is current().
        assertThat(reloader.current().topicMaxLength()).isEqualTo(222);
        assertThat(delivered).isSorted().isNotEmpty().last().isEqualTo(222);
    }

    @Test
    void aReloadStartedByAListenerIsNotFollowedByTheOlderConfigurationForLaterListeners()
            throws Exception {
        AtomicBoolean nested = new AtomicBoolean(true);
        List<Integer> later = new CopyOnWriteArrayList<>();
        reloader.addReloadListener(
                applied -> {
                    if (!nested.getAndSet(false)) return;
                    try {
                        Files.writeString(config, HEADER + "topicMaxLength: 333\n");
                        reloader.reload();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        reloader.addReloadListener(applied -> later.add(applied.topicMaxLength()));
        Files.writeString(config, HEADER + "topicMaxLength: 111\n");

        reloader.reload();

        assertThat(later).containsExactly(333);
        assertThat(reloader.current().topicMaxLength()).isEqualTo(333);
    }

    private void reloadQuietly() {
        try {
            reloader.reload();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aListenerErrorDoesNotStrandANewerConfigurationPublishedMeanwhile() throws Exception {
        CountDownLatch inListener = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        List<Integer> seen = new CopyOnWriteArrayList<>();
        AssertionError sentinel = new AssertionError("listener sentinel");
        reloader.addReloadListener(
                applied -> {
                    seen.add(applied.topicMaxLength());
                    if (first.getAndSet(false)) {
                        inListener.countDown();
                        await(release);
                        throw sentinel;
                    }
                });
        Files.writeString(config, HEADER + "topicMaxLength: 111\n");
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread a =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        reloader.reload();
                                    } catch (Throwable t) {
                                        thrown.set(t);
                                    }
                                });
        assertThat(inListener.await(5, TimeUnit.SECONDS)).isTrue();
        Files.writeString(config, HEADER + "topicMaxLength: 222\n");

        // B publishes while A is delivering, and returns at once: A's thread owes it a delivery.
        reloader.reload();
        release.countDown();
        a.join(TimeUnit.SECONDS.toMillis(5));

        // No third reload: the newest configuration was still delivered after the Error.
        assertThat(seen).containsExactly(111, 222);
        assertThat(thrown.get()).isSameAs(sentinel);
        assertThat(reloader.current().topicMaxLength()).isEqualTo(222);
    }

    @Test
    void aListenerErrorDoesNotSkipTheOtherListenersForTheSameConfiguration() throws Exception {
        List<Integer> later = new CopyOnWriteArrayList<>();
        AssertionError sentinel = new AssertionError("first listener");
        reloader.addReloadListener(
                applied -> {
                    throw sentinel;
                });
        reloader.addReloadListener(applied -> later.add(applied.topicMaxLength()));
        Files.writeString(config, HEADER + "topicMaxLength: 77\n");

        assertThatThrownBy(reloader::reload).isSameAs(sentinel);

        assertThat(later).containsExactly(77);
    }

    @Test
    void theSameErrorThrownByTwoListenersDoesNotAbortDelivery() throws Exception {
        List<Integer> later = new CopyOnWriteArrayList<>();
        AssertionError shared = new AssertionError("shared sentinel");
        reloader.addReloadListener(
                applied -> {
                    throw shared;
                });
        reloader.addReloadListener(
                applied -> {
                    throw shared;
                });
        reloader.addReloadListener(applied -> later.add(applied.topicMaxLength()));
        Files.writeString(config, HEADER + "topicMaxLength: 88\n");

        assertThatThrownBy(reloader::reload).isSameAs(shared);

        assertThat(later).containsExactly(88);
    }
}
