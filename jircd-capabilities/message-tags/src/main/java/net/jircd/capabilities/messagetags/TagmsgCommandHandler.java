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
package net.jircd.capabilities.messagetags;

import java.util.List;
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.MessageDelivery;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code TAGMSG} — a tag-only message, no text body (002-extended-irc-commands FR-020 through
 * FR-023): reuses core's {@link MessageDelivery} target resolution and send gates verbatim, then
 * fans out to recipients that have {@code message-tags} negotiated only — a client without it has
 * nothing to render from a message with no body. That includes a sender copy: it is delivered under
 * the same recipient rule.
 */
final class TagmsgCommandHandler implements CommandHandler {

    private static final String COMMAND_NAME = "TAGMSG";

    private final Supplier<MessageDelivery> delivery;
    private final Supplier<String> serverName;

    TagmsgCommandHandler(Supplier<MessageDelivery> delivery, Supplier<String> serverName) {
        this.delivery = delivery;
        this.serverName = serverName;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        if (message.params().isEmpty()) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NEEDMOREPARAMS,
                    List.of(COMMAND_NAME),
                    "Not enough parameters");
            return;
        }
        if (message.tags().isEmpty()) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_UNKNOWNCOMMAND,
                    List.of(COMMAND_NAME),
                    "Malformed message");
            return;
        }
        String target = message.params().getFirst();
        MessageDelivery shared = delivery.get();
        MessageDelivery.AuthorizedTarget recipients = shared.authorize(session, target, true);
        if (recipients.isEmpty()) {
            return;
        }
        shared.deliver(
                session,
                COMMAND_NAME,
                target,
                null,
                message.tags(),
                recipients,
                // FR-021: nothing to render for a client without message-tags negotiated.
                recipient -> recipient.negotiatedCapabilities().contains(MessageTagsExtension.ID));
    }
}
