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
import java.util.Map;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.KickOutcome;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.Recipients;
import net.jircd.core.session.SecurityEventLog;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code KICK} — operator-only channel member removal. Always available, never gated by extension
 * toggling.
 */
public final class KickCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public KickCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this.channelRegistry = channelRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().size() < 2) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "KICK",
          "Not enough parameters");
      return;
    }
    String channelName = message.params().getFirst();
    String targetNickname = message.params().get(1);
    // A no-comment KICK defaults to the kicker's own
    // nickname, the widely-implemented Modern IRC convention, rather than omitting it.
    String reason = message.params().size() > 2 ? message.params().get(2) : session.nickname();

    var found = channelRegistry.lookup(channelName);
    if (found.isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHCHANNEL,
          channelName,
          "No such channel");
      return;
    }
    Channel channel = found.get();

    switch (channelRegistry.kick(channel, session, targetNickname)) {
      case KickOutcome.NotOperator ignored -> {
        SecurityEventLog.rejectedModerationAction(
            session.connectionId(), "KICK", channelName, "not a channel operator");
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_CHANOPRIVSNEEDED,
            channel.name(),
            "You're not channel operator");
      }
      case KickOutcome.NoSuchMember ignored ->
          Replies.send(
              session,
              serverName.get(),
              NumericReply.ERR_USERNOTINCHANNEL,
              targetNickname,
              "They aren't on that channel");
      case KickOutcome.Kicked kicked -> {
        String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
        List<String> params =
            reason != null
                ? List.of(channel.name(), kicked.departure().departed().nickname(), reason)
                : List.of(channel.name(), kicked.departure().departed().nickname());
        Message notification = new Message(Map.of(), presentedForm, Command.KICK, "KICK", params);
        Recipients.deliver(kicked.departure().recipients(), notification);
      }
    }
  }
}
