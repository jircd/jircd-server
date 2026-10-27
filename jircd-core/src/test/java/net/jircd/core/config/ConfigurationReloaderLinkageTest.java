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
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A provider whose class cannot link fails alone; the rest of the REHASH still applies. */
class ConfigurationReloaderLinkageTest {
    @TempDir Path directory;

    private static final class Simple implements ServerExtension {
        private final String id;
        private final boolean broken;

        Simple(String id, boolean broken) {
            this.id = id;
            this.broken = broken;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void start(ServerContext context) {
            if (broken) throw new NoClassDefFoundError("missing/Dependency");
        }

        @Override
        public void stop() {
            // No resources.
        }
    }

    @Test
    void aLinkageErrorWhileEnablingOneProviderDoesNotAbortTheReload() throws Exception {
        ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of("broken", "good"));
        ServerConfiguration initial =
                loader.load(
                        new ByteArrayInputStream(
                                "serverName: test.jircd.local\n".getBytes(StandardCharsets.UTF_8)));
        ExtensionRegistry extensions = new ExtensionRegistry();
        extensions.register(new Simple("broken", true));
        extensions.register(new Simple("good", false));
        Path yaml = directory.resolve("reload.yaml");
        Files.writeString(
                yaml,
                """
        serverName: test.jircd.local
        nicknameMaxLength: 20
        server-extensions: {broken: enabled, good: enabled}
        """);
        ConfigurationReloader reloader =
                new ConfigurationReloader(yaml, loader, extensions, initial);

        ServerConfiguration reloaded = reloader.reload();

        assertThat(reloader.current()).isSameAs(reloaded);
        assertThat(reloaded.nicknameMaxLength()).isEqualTo(20);
        assertThat(extensions.stateOf("broken")).isEqualTo(Extension.State.FAILED);
        assertThat(extensions.stateOf("good")).isEqualTo(Extension.State.ENABLED);
    }

    private static final class Flaky implements ServerExtension {
        volatile RuntimeException stopFailure;
        volatile Runnable onStart = () -> {};

        @Override
        public String id() {
            return "flaky";
        }

        @Override
        public void start(ServerContext context) {
            onStart.run();
        }

        @Override
        public void stop() {
            if (stopFailure != null) throw stopFailure;
        }
    }

    private static final String HEADER = "serverName: test.jircd.local\n";

    @Test
    void rehashRetriesTheCleanupOfAFailedProviderWhoseDesiredStateIsDisabled() throws Exception {
        ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of("flaky"));
        ServerConfiguration initial =
                loader.load(new ByteArrayInputStream(HEADER.getBytes(StandardCharsets.UTF_8)));
        Flaky flaky = new Flaky();
        ExtensionRegistry extensions = new ExtensionRegistry();
        extensions.register(flaky);
        extensions.enable("flaky");
        flaky.stopFailure = new IllegalStateException("stuck");
        assertThatThrownBy(() -> extensions.disable("flaky")).isSameAs(flaky.stopFailure);
        flaky.stopFailure = null;
        Path yaml = directory.resolve("retry.yaml");
        Files.writeString(yaml, HEADER + "server-extensions: {flaky: disabled}\n");

        new ConfigurationReloader(yaml, loader, extensions, initial).reload();

        assertThat(extensions.stateOf("flaky")).isEqualTo(Extension.State.DISABLED);
    }

    @Test
    void aProviderCallbackThatCallsReloadFailsFastInsteadOfDeadlocking() throws Exception {
        ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of("flaky"));
        ServerConfiguration initial =
                loader.load(new ByteArrayInputStream(HEADER.getBytes(StandardCharsets.UTF_8)));
        Path yaml = directory.resolve("reentrant.yaml");
        Files.writeString(yaml, HEADER);
        Flaky flaky = new Flaky();
        ExtensionRegistry extensions = new ExtensionRegistry();
        extensions.register(flaky);
        ConfigurationReloader reloader =
                new ConfigurationReloader(yaml, loader, extensions, initial);
        java.util.concurrent.atomic.AtomicReference<Throwable> attempt =
                new java.util.concurrent.atomic.AtomicReference<>();
        flaky.onStart = () -> attempt.set(catchThrowable(reloader::reload));

        extensions.enable("flaky");

        assertThat(attempt.get()).isInstanceOf(IllegalStateException.class);
    }
}
