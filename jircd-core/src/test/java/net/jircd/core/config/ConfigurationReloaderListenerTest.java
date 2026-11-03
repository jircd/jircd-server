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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger reloaderLog =
            (Logger) LoggerFactory.getLogger(ConfigurationReloader.class);

    @BeforeEach
    void start() throws Exception {
        config = Files.writeString(dir.resolve("jircd.yaml"), HEADER);
        ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of());
        reloader =
                new ConfigurationReloader(
                        config, loader, new ExtensionRegistry(), loader.load(config));
        logs.start();
        reloaderLog.addAppender(logs);
    }

    @AfterEach
    void stopCapturing() {
        reloaderLog.detachAppender(logs);
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
        assertThat(logs.list)
                .anySatisfy(
                        e -> {
                            assertThat(e.getLevel()).isEqualTo(Level.WARN);
                            assertThat(e.getThrowableProxy().getMessage())
                                    .isEqualTo("listener broke");
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
}
