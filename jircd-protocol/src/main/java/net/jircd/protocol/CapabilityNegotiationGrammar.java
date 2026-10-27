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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The {@code CAP} command's own sub-language (FR-006/FR-007/FR-008): {@code LS}/{@code REQ}/{@code
 * ACK}/{@code NAK}/{@code END} subcommands, each with a follow-on capability-list syntax.
 * Deliberately separate from the generic {@code COMMAND [params] [:trailing]} framing {@link
 * MessageParser} already handles, since {@code CAP}'s subcommands branch into genuinely different
 * follow-on syntax.
 */
public final class CapabilityNegotiationGrammar {

    public enum Subcommand {
        LS,
        LIST,
        REQ,
        ACK,
        NAK,
        END
    }

    /** The first {@code CAP LS} version with continuation lines and implicit cap-notify. */
    public static final int NOTIFY_VERSION = 302;

    private CapabilityNegotiationGrammar() {}

    /** Resolves a {@code CAP} message's subcommand token, or {@code null} if unrecognized. */
    public static Subcommand parseSubcommand(Message message) {
        if (message.params().isEmpty()) {
            return null;
        }
        String token = message.params().getFirst().toUpperCase(Locale.ROOT);
        try {
            return Subcommand.valueOf(token);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Splits a {@code CAP REQ}/{@code ACK}/{@code NAK} trailing capability list on spaces. */
    public static List<String> parseCapabilityList(Message message) {
        if (message.params().size() < 2) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : message.params().getLast().split(" ")) {
            if (!token.isBlank()) {
                result.add(token);
            }
        }
        return result;
    }

    /**
     * Parses the version a {@code CAP LS} carries: a nonnegative, digit-only decimal, saturating at
     * {@link Integer#MAX_VALUE} rather than overflowing. An absent, empty, signed or otherwise
     * malformed version reads as {@code 0}, the same as no version.
     */
    public static int parseLsVersion(Message message) {
        if (message.params().size() < 2) {
            return 0;
        }
        String token = message.params().get(1);
        if (token.isEmpty()) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            value = Math.min(Integer.MAX_VALUE, value * 10 + (c - '0'));
        }
        return (int) value;
    }

    /**
     * {@code CAP LS} replies offering {@code capabilities}. A 302-or-later client ({@code
     * continuation}) gets as many lines as the list needs, every one but the last marked {@code *};
     * an older client must get exactly one line.
     *
     * @throws MessageSerializer.MessageTooLongException if {@code continuation} is false and the
     *     list cannot fit one line; it is never truncated
     */
    public static List<Message> lsReplies(
            String serverName, String target, List<String> capabilities, boolean continuation) {
        return packed(serverName, target, "LS", capabilities, continuation);
    }

    /**
     * {@code CAP LIST} replies: the client's currently negotiated capabilities, distinct from
     * {@link #lsReplies} which reports everything the server offers regardless of negotiation state
     * (005-fix-batch-conformance FR-007). Packed like {@code LS}.
     */
    public static List<Message> listReplies(
            String serverName, String target, List<String> capabilities, boolean continuation) {
        return packed(serverName, target, "LIST", capabilities, continuation);
    }

    /**
     * {@code CAP NEW} or {@code CAP DEL} ({@code subcommand}) lines for {@code capabilities}: names
     * only, deduplicated and sorted, split on name boundaries into lines that are each a complete,
     * independently valid notification. There is no continuation marker for these.
     */
    public static List<Message> notifications(
            String serverName, String target, String subcommand, Collection<String> capabilities) {
        List<String> names = List.copyOf(new TreeSet<>(capabilities));
        List<Message> lines = new ArrayList<>();
        for (List<String> chunk : chunks(serverName, target, subcommand, names)) {
            lines.add(line(serverName, target, subcommand, false, chunk));
        }
        return lines;
    }

    /** Whether one {@code subcommand} line listing {@code capabilities} fits the 512-byte limit. */
    public static boolean fitsOneLine(
            String serverName, String target, String subcommand, List<String> capabilities) {
        return MessageSerializer.commandBytes(
                        line(serverName, target, subcommand, false, capabilities))
                <= MessageSerializer.MAX_COMMAND_BYTES;
    }

    private static List<Message> packed(
            String serverName,
            String target,
            String subcommand,
            List<String> capabilities,
            boolean continuation) {
        if (!continuation) {
            if (!fitsOneLine(serverName, target, subcommand, capabilities)) {
                throw new MessageSerializer.MessageTooLongException(
                        "CAP " + subcommand + " list does not fit one line");
            }
            return List.of(line(serverName, target, subcommand, false, capabilities));
        }
        List<List<String>> chunks = chunks(serverName, target, subcommand, capabilities);
        List<Message> lines = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            lines.add(line(serverName, target, subcommand, i < chunks.size() - 1, chunks.get(i)));
        }
        return lines;
    }

    /**
     * Splits {@code names} greedily into runs that each fit one line, sized as if every line
     * carried the continuation marker. An empty list is one empty run.
     */
    private static List<List<String>> chunks(
            String serverName, String target, String subcommand, List<String> names) {
        int fixed =
                MessageSerializer.commandBytes(
                        line(serverName, target, subcommand, true, List.of()));
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int bytes = fixed;
        for (String name : names) {
            int added = MessageSerializer.utf8Bytes(name) + (current.isEmpty() ? 0 : 1);
            if (bytes + added > MessageSerializer.MAX_COMMAND_BYTES && !current.isEmpty()) {
                chunks.add(current);
                current = new ArrayList<>();
                added = MessageSerializer.utf8Bytes(name);
                bytes = fixed;
            }
            if (fixed + added > MessageSerializer.MAX_COMMAND_BYTES) {
                throw new MessageSerializer.MessageTooLongException(
                        "capability name cannot fit a CAP " + subcommand + " line");
            }
            current.add(name);
            bytes += added;
        }
        chunks.add(current);
        return chunks;
    }

    private static Message line(
            String serverName,
            String target,
            String subcommand,
            boolean more,
            List<String> capabilities) {
        List<String> params =
                more
                        ? List.of(target, subcommand, "*", join(capabilities))
                        : List.of(target, subcommand, join(capabilities));
        return new Message(java.util.Map.of(), serverName, Command.CAP, "CAP", params);
    }

    public static Message ack(String serverName, String target, List<String> capabilities) {
        return new Message(
                java.util.Map.of(),
                serverName,
                Command.CAP,
                "CAP",
                List.of(target, "ACK", join(capabilities)));
    }

    public static Message nak(String serverName, String target, List<String> capabilities) {
        return new Message(
                java.util.Map.of(),
                serverName,
                Command.CAP,
                "CAP",
                List.of(target, "NAK", join(capabilities)));
    }

    private static String join(List<String> capabilities) {
        return String.join(" ", capabilities);
    }
}
