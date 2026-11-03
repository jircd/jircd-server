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

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates the optional {@code logging} section (spec 016 FR-006, FR-006a;
 * contracts/logging-config.md). Unlike most sections, unknown keys are rejected: a misspelled
 * {@code file} would otherwise silently turn file logging off.
 */
final class LoggingSection {

    private static final String SECTION = "logging";
    private static final String CONSOLE = "console";
    private static final String FILE = "file";
    private static final Set<String> KEYS = Set.of(CONSOLE, FILE);

    private LoggingSection() {}

    /**
     * With {@code -Dlogback.configurationFile} set, logback is configured entirely by that file and
     * this section is ignored (contracts/logging-config.md), so it is not validated either: a path
     * check here would reject a configuration that is never used.
     */
    static ServerConfiguration.Logging parse(Object raw) throws ConfigurationException {
        if (System.getProperty("logback.configurationFile") != null) {
            return ServerConfiguration.Logging.DEFAULT;
        }
        if (raw == null) {
            return ServerConfiguration.Logging.DEFAULT;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ConfigurationException(SECTION + " must be a mapping");
        }
        for (Object key : map.keySet()) {
            // Type first: Set.of rejects a null lookup, and a non-string key (a YAML complex key)
            // may itself hold a credential, so it is only ever echoed redacted.
            if (!(key instanceof String name)) {
                throw new ConfigurationException(
                        SECTION
                                + " keys must be setting names (console, file), got: "
                                + ConfigurationLoader.redactSecrets(key));
            }
            if (!KEYS.contains(name)) {
                throw new ConfigurationException(
                        SECTION + "." + name + " is not a known setting (console, file)");
            }
        }
        boolean console = !map.containsKey(CONSOLE) || console(map.get(CONSOLE));
        Path file = map.containsKey(FILE) ? file(map.get(FILE)) : null;
        if (!console && file == null) {
            throw new ConfigurationException(
                    "logging: console is off and no file is set; the server would produce no"
                            + " log output");
        }
        return new ServerConfiguration.Logging(console, file);
    }

    private static boolean console(Object value) throws ConfigurationException {
        if (!(value instanceof Boolean bool)) {
            throw new ConfigurationException(
                    SECTION
                            + "."
                            + CONSOLE
                            + " must be true or false, got: "
                            + ConfigurationLoader.redactSecrets(value));
        }
        return bool;
    }

    /** Absolute and normalized; its directory must exist and be writable right now. */
    private static Path file(Object value) throws ConfigurationException {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ConfigurationException(
                    SECTION
                            + "."
                            + FILE
                            + " must be a non-blank path, got: "
                            + ConfigurationLoader.redactSecrets(value));
        }
        Path file;
        try {
            file = Path.of(text.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new ConfigurationException(
                    SECTION + "." + FILE + " is not a valid path: " + e.getMessage());
        }
        Path directory = file.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new ConfigurationException(
                    SECTION + "." + FILE + " directory does not exist: " + directory);
        }
        if (!Files.isWritable(directory)) {
            throw new ConfigurationException(
                    SECTION + "." + FILE + " directory is not writable: " + directory);
        }
        if (Files.exists(file)) {
            if (!Files.isRegularFile(file)) {
                throw new ConfigurationException(
                        SECTION + "." + FILE + " is not a regular file: " + file);
            }
            if (!Files.isWritable(file)) {
                throw new ConfigurationException(
                        SECTION + "." + FILE + " is not writable: " + file);
            }
        }
        return file;
    }
}
