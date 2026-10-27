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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.UserMode;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code USERHOST} — host information for up to 5 nicknames (RFC1459 §4.9,
 * 005-fix-batch-conformance FR-018), completing a command previously recognized but unhandled. One
 * space-separated {@code nick[*]=[+|-]ident@host} entry per found nickname — {@code *} marks an IRC
 * operator, {@code +}/{@code -} marks present/away. The host follows the same FR-038 three-tier
 * resolution {@code WHOIS}/{@code WHO}/{@code WHOWAS} already use — never a second, independent
 * computation of "who gets to see the real value" (research.md "Cloak extension boundary").
 */
public final class UserhostCommandHandler implements CommandHandler {

    private static final int MAX_NICKNAMES = 5;

    private final NicknameRegistry nicknameRegistry;
    private final ExtensionRegistry extensionRegistry;
    private final Supplier<String> serverName;

    public UserhostCommandHandler(
            NicknameRegistry nicknameRegistry,
            ExtensionRegistry extensionRegistry,
            Supplier<String> serverName) {
        this.nicknameRegistry = nicknameRegistry;
        this.extensionRegistry = extensionRegistry;
        this.serverName = serverName;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        List<String> entries = new ArrayList<>();
        int count = Math.min(message.params().size(), MAX_NICKNAMES);
        for (int i = 0; i < count; i++) {
            nicknameRegistry
                    .lookup(message.params().get(i))
                    .ifPresent(
                            target -> {
                                String hostname =
                                        target == session || session.isAdministrator()
                                                ? target.realHostname()
                                                : PresentedIdentity.displayHostname(
                                                        target, extensionRegistry);
                                entries.add(
                                        target.nickname()
                                                + (target.userModes().contains(UserMode.OPERATOR)
                                                        ? "*"
                                                        : "")
                                                + '='
                                                + (target.isAway() ? '-' : '+')
                                                + target.ident()
                                                + '@'
                                                + hostname);
                            });
        }
        Replies.sendListed(
                session, serverName.get(), NumericReply.RPL_USERHOST, List.of(), entries);
    }
}
