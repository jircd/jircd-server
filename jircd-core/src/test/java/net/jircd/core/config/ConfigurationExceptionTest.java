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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfigurationExceptionTest {

    private static final Path FILE = Path.of("/etc/jircd/jircd.yaml");

    @Test
    void namesTheFileWhenTheMessageDoesNot() {
        ConfigurationException e = new ConfigurationException("root contains invalid YAML");
        assertThat(e.messageNaming(FILE))
                .isEqualTo(
                        "root contains invalid YAML (configuration file: /etc/jircd/jircd.yaml)");
    }

    @Test
    void doesNotRepeatAPathTheMessageAlreadyCarries() {
        ConfigurationException e =
                new ConfigurationException("Configuration file not found: " + FILE);
        assertThat(e.messageNaming(FILE)).isEqualTo("Configuration file not found: " + FILE);
    }

    @Test
    void stillNamesAFileCalledLoggingWhenOnlyTheSectionNameMatches() {
        // A relative path that is just "logging": the section name inside the message used to be
        // mistaken for the file's path, so the file was never named.
        Path logging = Path.of("logging");
        ConfigurationException e = new ConfigurationException("logging must be a mapping");
        assertThat(e.messageNaming(logging))
                .isEqualTo("logging must be a mapping (configuration file: logging)");
    }

    @Test
    void recognizesTheLoadersCouldNotReadMessage() {
        ConfigurationException e =
                new ConfigurationException("Could not read configuration file: " + FILE);
        assertThat(e.messageNaming(FILE)).isEqualTo("Could not read configuration file: " + FILE);
    }
}
