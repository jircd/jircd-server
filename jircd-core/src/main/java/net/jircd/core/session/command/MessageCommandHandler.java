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
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.MessageDelivery;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.protocol.NumericReply;

/**
 * {@code PRIVMSG}/{@code NOTICE} — channel and direct messaging (FR-004, FR-005). Target
 * resolution, the send gates and recipient selection are {@link MessageDelivery}'s; this handler
 * applies no capability-dependent formatting itself, which happens per recipient when the message
 * is queued.
 */
public final class MessageCommandHandler implements CommandHandler {

  private final MessageDelivery delivery;
  private final Supplier<String> serverName;
  private final boolean notice;

  public MessageCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      boolean notice) {
    this.delivery =
        new MessageDelivery(channelRegistry, nicknameRegistry, extensionRegistry, serverName);
    this.serverName = serverName;
    this.notice = notice;
  }

  @Override
  public void handle(ClientSession session, net.jircd.protocol.Message message) {
    String commandName = notice ? "NOTICE" : "PRIVMSG";
    if (message.params().size() < 2) {
      if (!notice) {
        Replies.sendFitted(
            session,
            serverName.get(),
            NumericReply.ERR_NEEDMOREPARAMS,
            List.of(commandName),
            "Not enough parameters");
      }
      return;
    }
    String target = message.params().getFirst();
    String body = message.params().get(1);
    if (body.isEmpty()) {
      // 005-fix-batch-conformance FR-004 — an empty body is syntactically present but
      // semantically equivalent to "no text to send"; NOTICE stays silent on error either way.
      if (!notice) {
        Replies.sendFitted(
            session, serverName.get(), NumericReply.ERR_NOTEXTTOSEND, List.of(), "No text to send");
      }
      return;
    }

    MessageDelivery.AuthorizedTarget recipients = delivery.authorize(session, target, !notice);
    if (recipients.isEmpty()) {
      return;
    }
    if (!target.startsWith("#")) {
      // Direct-message form only (002-extended-irc-commands FR-007) — a channel target's away
      // members, if any, are not individually called out.
      ClientSession recipient = recipients.iterator().next();
      if (recipient.isAway()) {
        Replies.sendFitted(
            session,
            serverName.get(),
            NumericReply.RPL_AWAY,
            List.of(recipient.nickname()),
            recipient.awayReason());
      }
    }

    // 005-fix-batch-conformance FR-010 — the sender's own client-only tags are offered to the
    // providers that forward them; CapabilityTagRenderer merges the selected ones into the
    // per-recipient rendered map alongside server-contributed ones (msgid/time).
    delivery.deliver(
        session, commandName, target, body, message.tags(), recipients, recipient -> true);
  }
}
