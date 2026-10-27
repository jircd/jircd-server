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
package net.jircd.core.session;

import java.util.HashMap;
import java.util.Map;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;

/**
 * Checks that a message built by extension code serializes to exactly one well-formed line: the
 * serializer enforces sizes, not syntax, so a parameter carrying CR/LF would otherwise become extra
 * wire messages. Also enforces the per-origin tag budget separately for server and client-only
 * ({@code +}) tags.
 */
final class FrameSyntax {
    private FrameSyntax() {}

    static void validate(Message message) {
        String command =
                message.rawCommand() != null ? message.rawCommand() : message.command().name();
        if (!validCommand(command)) throw new IllegalArgumentException("Invalid command token");
        String prefix = message.prefix();
        if (prefix != null && (prefix.isEmpty() || hasAny(prefix, " \r\n\0"))) {
            throw new IllegalArgumentException("Invalid prefix");
        }
        int last = message.params().size() - 1;
        for (int i = 0; i <= last; i++) {
            String param = message.params().get(i);
            if (hasAny(param, "\r\n\0")) throw new IllegalArgumentException("Invalid parameter");
            if (i < last && (param.isEmpty() || param.startsWith(":") || param.indexOf(' ') >= 0)) {
                throw new IllegalArgumentException("Invalid middle parameter");
            }
        }
        validateTags(message.tags());
    }

    private static void validateTags(Map<String, String> tags) {
        Map<String, String> server = new HashMap<>();
        Map<String, String> client = new HashMap<>();
        for (var entry : tags.entrySet()) {
            String key = entry.getKey();
            if (!validKey(key)) throw new IllegalArgumentException("Invalid tag key");
            // CR and LF are escaped by the serializer, but NUL would be emitted unchanged.
            if (entry.getValue().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid tag value");
            (key.startsWith("+") ? client : server).put(key, entry.getValue());
        }
        for (Map<String, String> origin : java.util.List.of(server, client)) {
            if (!MessageSerializer.tagDataBytesAtMost(
                    origin, MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN)) {
                throw new IllegalArgumentException(
                        "Tag origin exceeds "
                                + MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN
                                + " data bytes");
            }
        }
    }

    /**
     * IRCv3 key: optional {@code +}, optional {@code vendor/} (a hostname), then
     * letters-digits-hyphens.
     */
    private static boolean validKey(String key) {
        String body = key.startsWith("+") ? key.substring(1) : key;
        int slash = body.indexOf('/');
        String name = slash < 0 ? body : body.substring(slash + 1);
        if (slash >= 0 && !validVendor(body.substring(0, slash))) return false;
        return !name.isEmpty() && name.chars().allMatch(c -> isAlnum(c) || c == '-');
    }

    private static boolean validVendor(String vendor) {
        if (vendor.isEmpty() || vendor.startsWith(".") || vendor.endsWith(".")) return false;
        return !vendor.contains("..")
                && vendor.chars().allMatch(c -> isAlnum(c) || c == '-' || c == '.');
    }

    private static boolean isAlnum(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static boolean validCommand(String command) {
        if (command.isEmpty()) return false;
        boolean digits = command.chars().allMatch(c -> c >= '0' && c <= '9');
        if (digits) return command.length() == 3;
        return command.chars().allMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
    }

    private static boolean hasAny(String text, String chars) {
        for (int i = 0; i < text.length(); i++) {
            if (chars.indexOf(text.charAt(i)) >= 0) return true;
        }
        return false;
    }
}
