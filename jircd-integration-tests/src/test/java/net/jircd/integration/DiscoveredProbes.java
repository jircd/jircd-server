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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import net.jircd.core.extension.CapabilityExtension;

/**
 * Starts a {@link TestServer} that discovers test-only capability providers as if they were
 * installed modules, so the configuration loader knows their ids and {@code REHASH}/{@code SIGHUP}
 * can name them. Each provider class must be public with a public no-argument constructor. Only the
 * thread's context class loader is swapped, for the duration of the start.
 */
final class DiscoveredProbes {

    private DiscoveredProbes() {}

    @SafeVarargs
    static TestServer start(String extraYaml, Class<? extends CapabilityExtension>... providers)
            throws Exception {
        Path root = Files.createTempDirectory("jircd-probes-");
        Path services = root.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(
                services.resolve(CapabilityExtension.class.getName()),
                Arrays.stream(providers).map(Class::getName).collect(Collectors.joining("\n"))
                        + "\n");
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {root.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            return TestServer.start(extraYaml);
        } finally {
            thread.setContextClassLoader(previous);
            delete(root);
        }
    }

    private static void delete(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
