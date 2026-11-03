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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import net.jircd.core.config.ConfigurationException;
import net.jircd.server.JircdServerApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup refuses with a message that tells a missing configuration file from an unreadable one
 * (spec 016 FR-010, R8); {@code main} prefixes it with "Refusing to start:" and exits 1.
 */
class ConfigurationFileErrorTest {

    @TempDir Path dir;

    @Test
    void aMissingFileIsNamedAsMissing() {
        Path missing = dir.resolve("jircd.yaml");
        assertThatThrownBy(() -> new JircdServerApplication(missing))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration file not found: " + missing);
    }

    @Test
    void anUnreadableFileIsNamedAsUnreadable() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "root can read any file");
        Path file = Files.writeString(dir.resolve("jircd.yaml"), "");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"));
        assertThatThrownBy(() -> new JircdServerApplication(file))
                .isInstanceOf(ConfigurationException.class)
                .hasMessage("Configuration file not readable by this user: " + file);
    }

    @Test
    void anInvalidLoggingSectionRefusesStartup() throws Exception {
        Path file = Files.writeString(dir.resolve("jircd.yaml"), "logging:\n  console: false\n");
        assertThatThrownBy(() -> new JircdServerApplication(file))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageStartingWith("logging: console is off and no file is set");
    }
}
