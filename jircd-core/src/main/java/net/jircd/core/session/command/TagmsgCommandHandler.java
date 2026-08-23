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

import java.util.Set;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.OutboundMessage;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code TAGMSG} — a tag-only message, no text body (002-extended-irc-commands FR-020 through
 * FR-023, research.md "TAGMSG delivery reuse"): reuses {@link MessageCommandHandler}'s target
 * resolution verbatim, then fans out to recipients that have {@code message-tags} negotiated only —
 * a client without it has nothing to render from a message with no body.
 */
public final class TagmsgCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public TagmsgCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this.channelRegistry = channelRegistry;
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "TAGMSG",
          "Not enough parameters");
      return;
    }
    if (message.tags().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNCOMMAND,
          "TAGMSG",
          "Malformed message");
      return;
    }
    String target = message.params().getFirst();
    Set<ClientSession> recipients =
        MessageCommandHandler.resolveRecipients(
            channelRegistry,
            nicknameRegistry,
            extensionRegistry,
            serverName,
            session,
            target,
            true);
    if (recipients.isEmpty()) {
      return;
    }

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    OutboundMessage outbound =
        OutboundMessage.now(presentedForm, "TAGMSG", target, null, message.tags());
    boolean echoToSender = MessageCommandHandler.includeSenderInFanOut(extensionRegistry, session);
    for (ClientSession recipient : recipients) {
      if (recipient == session && !echoToSender) {
        continue;
      }
      if (!recipient.negotiatedCapabilities().contains(CapabilityName.MESSAGE_TAGS)) {
        continue; // FR-021: nothing to render for a client without message-tags negotiated
      }
      if (recipient.writer() != null) {
        recipient.writer().enqueue(outbound);
      }
    }
  }
}
