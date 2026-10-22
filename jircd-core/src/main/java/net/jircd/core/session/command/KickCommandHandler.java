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
import net.jircd.core.session.Departure;
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
  private final Runnable beforeTransition;

  public KickCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this(channelRegistry, extensionRegistry, serverName, () -> {});
  }

  /** Test-only seam: runs after the pre-checks and prefix preparation, before the transition. */
  KickCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      Runnable beforeTransition) {
    this.channelRegistry = channelRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.beforeTransition = beforeTransition;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().size() < 2) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          List.of("KICK"),
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
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHCHANNEL,
          List.of(channelName),
          "No such channel");
      return;
    }
    Channel channel = found.get();

    // A non-member kicker is not an operator, so it is answered as such without the extension.
    if (!channel.members().contains(session)) {
      rejectNotOperator(session, channel, channelName);
      return;
    }
    // The notification prefix may run hostname-display extension code, so it is resolved for every
    // member kicker before the membership transition and outside every monitor: a failure leaves
    // the target a member. The registry still decides authoritatively, and a request that turns
    // out invalid discards the prefix.
    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    beforeTransition.run();
    switch (channelRegistry.kick(channel, session, targetNickname)) {
      case KickOutcome.NotOperator() -> rejectNotOperator(session, channel, channelName);
      case KickOutcome.NoSuchMember() ->
          Replies.sendFitted(
              session,
              serverName.get(),
              NumericReply.ERR_USERNOTINCHANNEL,
              List.of(targetNickname),
              "They aren't on that channel");
      case KickOutcome.Kicked(Departure departure) -> {
        List<String> params = List.of(channel.name(), departure.departed().nickname(), reason);
        Message notification = new Message(Map.of(), presentedForm, Command.KICK, "KICK", params);
        Recipients.deliver(departure.recipients(), notification);
      }
    }
  }

  private void rejectNotOperator(ClientSession session, Channel channel, String channelName) {
    SecurityEventLog.rejectedModerationAction(
        session.connectionId(), "KICK", channelName, "not a channel operator");
    Replies.sendFitted(
        session,
        serverName.get(),
        NumericReply.ERR_CHANOPRIVSNEEDED,
        List.of(channel.name()),
        "You're not channel operator");
  }
}
