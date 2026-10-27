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
package net.jircd.serverextensions.admin;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code KILL} — administrator-forced disconnect (002-extended-irc-commands FR-011 through FR-015,
 * research.md "KILL disconnect path reuse"). Routes through the exact same {@link
 * DisconnectCleanup} path every other disconnect cause already uses — reintroducing a {@code
 * KILL}-specific close would risk the same cross-thread socket-close bug already fixed for the
 * keep-alive-timeout and writer-overflow paths.
 */
public final class KillCommandHandler implements CommandHandler {

    private static final String DEFAULT_REASON = "Killed";

    private final NicknameRegistry nicknameRegistry;
    private final ExtensionRegistry extensionRegistry;
    private final Supplier<String> serverName;
    private final DisconnectCleanup disconnectCleanup;

    public KillCommandHandler(
            NicknameRegistry nicknameRegistry,
            ExtensionRegistry extensionRegistry,
            Supplier<String> serverName,
            DisconnectCleanup disconnectCleanup) {
        this.nicknameRegistry = nicknameRegistry;
        this.extensionRegistry = extensionRegistry;
        this.serverName = serverName;
        this.disconnectCleanup = disconnectCleanup;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        if (!AdminPrivilege.isAuthorized(session, extensionRegistry)) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NOPRIVILEGES,
                    List.of(),
                    "Permission Denied- You're not an IRC operator");
            return;
        }
        if (message.params().isEmpty()) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NEEDMOREPARAMS,
                    List.of("KILL"),
                    "Not enough parameters");
            return;
        }
        String targetNickname = message.params().getFirst();
        var target = nicknameRegistry.lookup(targetNickname);
        if (target.isEmpty()) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NOSUCHNICK,
                    List.of(targetNickname),
                    "No such nick/channel");
            return;
        }
        String reason = message.params().size() > 1 ? message.params().get(1) : DEFAULT_REASON;
        ClientSession killed = target.get();
        // Borrowed reference to the target's own still-active writer — closed below via
        // disconnectCleanup.cleanup(), not directly by this handler.
        @SuppressWarnings("PMD.CloseResource")
        var writer = killed.writer();
        if (writer != null) {
            writer.enqueueRaw(new Message(Map.of(), null, Command.ERROR, "ERROR", List.of(reason)));
        }
        disconnectCleanup.cleanup(killed, "Killed by " + session.nickname() + " (" + reason + ")");
        AdminNotices.send(
                session, serverName.get(), "Killed " + targetNickname + " (" + reason + ")");
    }
}
