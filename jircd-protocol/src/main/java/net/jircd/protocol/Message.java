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
package net.jircd.protocol;

import java.util.List;
import java.util.Map;

/**
 * A single IRC protocol line, decomposed into its wire-format parts.
 *
 * @param tags message-tags (FR-025), empty if none/not negotiated; insertion order preserved
 * @param prefix the {@code :source} prefix, absent for a client-originated line
 * @param command the recognized command, or {@code null} if the token didn't match any
 * @param rawCommand the literal command token as sent, for error messages naming an unknown command
 * @param params positional parameters in order; the last one may have been the ":trailing"
 *     parameter
 */
public record Message(
        Map<String, String> tags,
        String prefix,
        Command command,
        String rawCommand,
        List<String> params) {

    public Message {
        tags = Map.copyOf(tags);
        params = List.copyOf(params);
    }

    public static Message of(Command command, String... params) {
        return new Message(Map.of(), null, command, command.name(), List.of(params));
    }
}
