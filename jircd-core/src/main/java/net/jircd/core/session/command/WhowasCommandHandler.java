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
package net.jircd.core.session.command;

import java.util.List;
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.WhowasEntry;
import net.jircd.core.session.WhowasHistory;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code WHOWAS} — last-known identity lookup for a disconnected nickname
 * (002-extended-irc-commands FR-016 through FR-019), backed by the bounded, global {@link
 * WhowasHistory}. Accepts an optional count parameter (006-complete-core-protocol FR-014/FR-015): a
 * positive number returns up to that many entries; omitted, zero, a negative number, or a
 * non-numeric value all mean "do a full search" (RFC1459 §4.5.3/RFC2812 §3.6.3's own rule for a
 * non-positive count — confirmed against irctest's own non-deprecated {@code testWhowasMultiple},
 * which sends no count at all and still expects every retained entry back — extended leniently to
 * an omitted or malformed count too).
 */
public final class WhowasCommandHandler implements CommandHandler {

    private final WhowasHistory whowasHistory;
    private final Supplier<String> serverName;

    public WhowasCommandHandler(WhowasHistory whowasHistory, Supplier<String> serverName) {
        this.whowasHistory = whowasHistory;
        this.serverName = serverName;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        String server = serverName.get();
        if (message.params().isEmpty()) {
            Replies.sendFitted(
                    session,
                    server,
                    NumericReply.ERR_NONICKNAMEGIVEN,
                    List.of(),
                    "No nickname given");
            return;
        }
        String nickname = message.params().getFirst();
        // An omitted count means "do a full search" (irctest's own non-deprecated
        // testWhowasMultiple sends no count at all and still expects every retained entry back) —
        // the same "0" sentinel mostRecentNFor already treats as unbounded.
        int count = message.params().size() > 1 ? parseCount(message.params().get(1)) : 0;
        List<WhowasEntry> found = whowasHistory.mostRecentNFor(nickname, count);
        if (found.isEmpty()) {
            Replies.sendFitted(
                    session,
                    server,
                    NumericReply.ERR_WASNOSUCHNICK,
                    List.of(nickname),
                    "There was no such nickname");
        } else {
            for (WhowasEntry entry : found) {
                // Same FR-038 resolution WHOIS/WHO already use: real hostname for an administrator,
                // the snapshotted presented (cloaked, if it was active) value for everyone else —
                // WHOWAS is not admin-gated like WHOHOST, so it must not leak the real value to an
                // ordinary client.
                String hostname =
                        session.isAdministrator()
                                ? entry.realHostname()
                                : entry.presentedHostname();
                Replies.sendFitted(
                        session,
                        server,
                        NumericReply.RPL_WHOWASUSER,
                        List.of(entry.nickname(), entry.ident(), hostname, "*"),
                        entry.realname());
            }
        }
        Replies.sendFitted(
                session, server, NumericReply.RPL_ENDOFWHOWAS, List.of(nickname), "End of WHOWAS");
    }

    private static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 0; // a malformed count is treated the same as a non-positive one — full search
        }
    }
}
