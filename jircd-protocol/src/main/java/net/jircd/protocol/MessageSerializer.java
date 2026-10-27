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

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Serializes a {@link Message} back into a raw wire line (without CR-LF). */
public final class MessageSerializer {

    public static final int MAX_COMMAND_BYTES = 512;
    public static final int MAX_TAG_SECTION_BYTES = 8191;
    public static final int MAX_TAG_DATA_BYTES_PER_ORIGIN = 4094;

    /** A message cannot be represented within the outbound wire budgets. */
    public static final class MessageTooLongException extends IllegalArgumentException {
        public MessageTooLongException(String reason) {
            super(reason);
        }
    }

    private MessageSerializer() {}

    /**
     * Serializes without CRLF, enforcing the command and combined tag wire budgets. Callers
     * assembling tags from multiple origins must also enforce each origin's data allowance.
     *
     * @throws MessageTooLongException if either wire section exceeds its allowance
     */
    public static String serialize(Message message) {
        String tags = serializeTags(message.tags());
        if (utf8Bytes(tags) > MAX_TAG_SECTION_BYTES) {
            throw new MessageTooLongException("Outbound tag section exceeds 8191 bytes");
        }
        String command = serializeCommand(message);
        if (utf8Bytes(command) + 2 > MAX_COMMAND_BYTES) {
            throw new MessageTooLongException("Outbound command section exceeds 512 bytes");
        }
        return tags + command;
    }

    /**
     * Command section size including CRLF, excluding tags; usable before splitting list replies.
     */
    public static int commandBytes(Message message) {
        return utf8Bytes(serializeCommand(message)) + 2;
    }

    private static String serializeCommand(Message message) {
        StringBuilder line = new StringBuilder();
        if (message.prefix() != null) {
            line.append(':').append(message.prefix()).append(' ');
        }

        line.append(message.rawCommand() != null ? message.rawCommand() : message.command().name());

        List<String> params = message.params();
        for (int i = 0; i < params.size(); i++) {
            String param = params.get(i);
            boolean isLast = i == params.size() - 1;
            boolean needsTrailingForm =
                    isLast && (param.isEmpty() || param.contains(" ") || param.startsWith(":"));
            line.append(' ');
            if (needsTrailingForm) {
                line.append(':');
            }
            line.append(param);
        }

        return line.toString();
    }

    /** UTF-8 wire bytes, independent of Java's UTF-16 string length. */
    public static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Escaped tag data bytes, excluding the leading @ and trailing space. */
    public static int tagDataBytes(Map<String, String> tags) {
        String section = serializeTags(tags);
        return section.isEmpty() ? 0 : utf8Bytes(section) - 2;
    }

    /**
     * Whether the escaped tag data fits {@code limit} bytes. The raw character count is a lower
     * bound of the escaped UTF-8 size, so an oversized map is rejected before any escaped section
     * is built.
     */
    public static boolean tagDataBytesAtMost(Map<String, String> tags, long limit) {
        long raw = 0;
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            raw += (long) entry.getKey().length() + entry.getValue().length();
            if (raw > limit) {
                return false;
            }
        }
        return tagDataBytes(tags) <= limit;
    }

    private static String serializeTags(Map<String, String> tags) {
        if (tags.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder("@");
        Iterator<Map.Entry<String, String>> it = tags.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            section.append(entry.getKey());
            if (!entry.getValue().isEmpty()) {
                section.append('=').append(escapeTagValue(entry.getValue()));
            }
            if (it.hasNext()) {
                section.append(';');
            }
        }
        return section.append(' ').toString();
    }

    /**
     * Preserves a fitting message verbatim. Only PRIVMSG/NOTICE text may be shortened; the prefix,
     * target and tags must fit intact. Truncation uses complete Unicode code points.
     */
    public static String serializeText(Message message) {
        String command =
                message.rawCommand() != null ? message.rawCommand() : message.command().name();
        if (!("PRIVMSG".equalsIgnoreCase(command) || "NOTICE".equalsIgnoreCase(command))
                || message.params().size() != 2) {
            return serialize(message);
        }
        if (commandBytes(message) <= MAX_COMMAND_BYTES) {
            return serialize(message);
        }
        Message empty =
                new Message(
                        message.tags(),
                        message.prefix(),
                        message.command(),
                        command,
                        List.of(message.params().getFirst(), ""));
        String header = serialize(empty);
        int remaining = MAX_COMMAND_BYTES - commandBytes(empty);
        String body = message.params().getLast();
        int end = 0;
        while (end < body.length()) {
            int cp = body.codePointAt(end);
            int width = Character.charCount(cp);
            int bytes = utf8Bytes(body.substring(end, end + width));
            if (bytes > remaining) {
                break;
            }
            remaining -= bytes;
            end += width;
        }
        if (end == 0) {
            throw new MessageTooLongException("Outbound text has no room for a complete character");
        }
        return header + body.substring(0, end);
    }

    private static String escapeTagValue(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case ';' -> result.append("\\:");
                case ' ' -> result.append("\\s");
                case '\\' -> result.append("\\\\");
                case '\r' -> result.append("\\r");
                case '\n' -> result.append("\\n");
                default -> result.append(c);
            }
        }
        return result.toString();
    }
}
