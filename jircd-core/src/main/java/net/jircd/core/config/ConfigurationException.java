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

import java.nio.file.Path;
import java.util.List;

/**
 * A specific, actionable configuration validation failure (FR-012, SC-008), naming the offending
 * key/value.
 */
public class ConfigurationException extends Exception {

    /** The loader's own messages, each ending with the file's path. */
    static final String NOT_FOUND = "Configuration file not found: ";

    static final String NOT_READABLE = "Configuration file not readable by this user: ";
    static final String COULD_NOT_READ = "Could not read configuration file: ";
    private static final List<String> FILE_MESSAGES =
            List.of(NOT_FOUND, NOT_READABLE, COULD_NOT_READ);

    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * The message with the configuration file's path (spec 016 FR-010), unless it already carries
     * it: a missing or unreadable file's message names the file itself.
     */
    public String messageNaming(Path configPath) {
        String message = getMessage();
        for (String prefix : FILE_MESSAGES) {
            if (message.equals(prefix + configPath)) {
                return message;
            }
        }
        return message + " (configuration file: " + configPath + ")";
    }
}
