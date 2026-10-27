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
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.UserMode;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code WALLOPS} — administrator broadcast to every connected session that has opted in via the
 * {@code wallops} (`+w`) user mode (010-wallops-notices FR-001 through FR-011). Delivers no
 * confirmation reply to the sender on success (research.md "reuse existing numeric replies") — the
 * sender sees their own notice only if their own {@code +w} is set, the same as any other
 * recipient.
 */
public final class WallopsCommandHandler implements CommandHandler {

    private final NicknameRegistry nicknameRegistry;
    private final ExtensionRegistry extensionRegistry;
    private final Supplier<String> serverName;

    public WallopsCommandHandler(
            NicknameRegistry nicknameRegistry,
            ExtensionRegistry extensionRegistry,
            Supplier<String> serverName) {
        this.nicknameRegistry = nicknameRegistry;
        this.extensionRegistry = extensionRegistry;
        this.serverName = serverName;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        if (AdminPrivilege.rejectUnlessAuthorized(session, extensionRegistry, serverName.get())) {
            return;
        }
        if (AdminPrivilege.rejectIfTooFewParams(
                session, serverName.get(), "WALLOPS", message.params(), 1)) {
            return;
        }
        String text = message.params().getFirst();
        if (text.isBlank()) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NOTEXTTOSEND,
                    List.of(),
                    "No text to send");
            return;
        }

        String prefix =
                Hostmask.format(session.nickname(), session.ident(), session.realHostname());
        Message notice = new Message(Map.of(), prefix, Command.WALLOPS, "WALLOPS", List.of(text));
        for (ClientSession recipient : nicknameRegistry.all()) {
            if (recipient.userModes().contains(UserMode.WALLOPS) && recipient.writer() != null) {
                recipient.writer().enqueueRaw(notice);
            }
        }
    }
}
