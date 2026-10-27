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
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code USER} — one-shot per connection (FR-001): rejected with {@code 462 ERR_ALREADYREGISTRED}
 * if this session has already processed one, checked before anything else. Registration completes
 * (FR-051's burst) once this session also has a claimed nickname — see {@link
 * RegistrationCompletion}.
 */
public final class UserCommandHandler implements CommandHandler {

    private final java.util.function.Supplier<String> serverName;
    private final RegistrationCompletion registrationCompletion;

    public UserCommandHandler(
            java.util.function.Supplier<String> serverName,
            RegistrationCompletion registrationCompletion) {
        this.serverName = serverName;
        this.registrationCompletion = registrationCompletion;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        if (session.hasProcessedUser()) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_ALREADYREGISTRED,
                    List.of(),
                    "Unauthorized command (already registered)");
            return;
        }
        if (message.params().size() < 4) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NEEDMOREPARAMS,
                    List.of("USER"),
                    "Not enough parameters");
            return;
        }

        String username = message.params().getFirst();
        String realname = message.params().get(3);
        if (realname.isEmpty()) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NEEDMOREPARAMS,
                    List.of("USER"),
                    "Not enough parameters");
            return;
        }

        String ident = Hostmask.isValidUsernameContent(username) ? username : "user";
        if (ident.length() > ServerConfiguration.IDENT_MAX_CHARS) {
            ident = ident.substring(0, ServerConfiguration.IDENT_MAX_CHARS);
        }
        session.setIdent(ident);
        session.setRealname(realname);

        registrationCompletion.tryComplete(session);
    }
}
