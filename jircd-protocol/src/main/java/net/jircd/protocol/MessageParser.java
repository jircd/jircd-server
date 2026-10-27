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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a single raw protocol line (without its trailing CR-LF) into a {@link Message}, per RFC
 * 1459/2812's grammar plus the IRCv3 message-tags framing (FR-025). Command recognition is
 * case-insensitive (FR-015).
 */
public final class MessageParser {

    private MessageParser() {}

    public static Message parse(String rawLine) throws MalformedMessageException {
        if (rawLine == null || rawLine.isEmpty()) {
            throw new MalformedMessageException("Empty line");
        }

        String remaining = rawLine;
        Map<String, String> tags = Map.of();
        if (remaining.startsWith("@")) {
            int spaceIndex = remaining.indexOf(' ');
            if (spaceIndex < 0) {
                throw new MalformedMessageException("Tags section with no command following it");
            }
            tags = parseTags(remaining.substring(1, spaceIndex));
            remaining = remaining.substring(spaceIndex + 1);
        }

        remaining = stripLeadingSpaces(remaining);

        String prefix = null;
        if (remaining.startsWith(":")) {
            int spaceIndex = remaining.indexOf(' ');
            if (spaceIndex < 0) {
                throw new MalformedMessageException("Prefix with no command following it");
            }
            prefix = remaining.substring(1, spaceIndex);
            remaining = stripLeadingSpaces(remaining.substring(spaceIndex + 1));
        }

        if (remaining.isEmpty()) {
            throw new MalformedMessageException("No command present");
        }

        int spaceIndex = remaining.indexOf(' ');
        String commandToken = spaceIndex < 0 ? remaining : remaining.substring(0, spaceIndex);
        if (commandToken.isEmpty()) {
            throw new MalformedMessageException("No command present");
        }
        String paramSection = spaceIndex < 0 ? "" : remaining.substring(spaceIndex + 1);

        List<String> params = parseParams(paramSection);
        Command command = Command.fromToken(commandToken);
        return new Message(tags, prefix, command, commandToken, params);
    }

    private static String stripLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return s.substring(i);
    }

    private static List<String> parseParams(String section) {
        List<String> params = new ArrayList<>();
        String remaining = stripLeadingSpaces(section);
        while (!remaining.isEmpty()) {
            if (remaining.charAt(0) == ':') {
                params.add(remaining.substring(1));
                break;
            }
            int spaceIndex = remaining.indexOf(' ');
            if (spaceIndex < 0) {
                params.add(remaining);
                break;
            }
            params.add(remaining.substring(0, spaceIndex));
            remaining = stripLeadingSpaces(remaining.substring(spaceIndex + 1));
        }
        return params;
    }

    private static Map<String, String> parseTags(String section) throws MalformedMessageException {
        if (section.isEmpty()) {
            throw new MalformedMessageException("Empty tags section");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (String entry : section.split(";")) {
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            String key = eq < 0 ? entry : entry.substring(0, eq);
            String rawValue = eq < 0 ? "" : entry.substring(eq + 1);
            if (key.isEmpty()) {
                throw new MalformedMessageException("Empty tag key");
            }
            tags.put(key, unescapeTagValue(rawValue));
        }
        return tags;
    }

    private static String unescapeTagValue(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case ':' -> result.append(';');
                    case 's' -> result.append(' ');
                    case '\\' -> result.append('\\');
                    case 'r' -> result.append('\r');
                    case 'n' -> result.append('\n');
                    default -> result.append(next);
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
