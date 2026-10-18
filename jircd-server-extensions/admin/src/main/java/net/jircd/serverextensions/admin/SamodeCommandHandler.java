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
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.Recipients;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code SAMODE <channel> <+o|-o>} — grants or revokes the sender's own operator status on a
 * channel it's already a member of, bypassing the "sender must already be an operator" precondition
 * ordinary {@code MODE} enforces. Self-targeting only — no target parameter, unlike {@code
 * ModeCommandHandler}'s {@code +o}/{@code -o}.
 */
public final class SamodeCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final Runnable beforeMemberChange;

  public SamodeCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this(channelRegistry, extensionRegistry, serverName, () -> {});
  }

  // Test seam for a departure after the membership pre-check, outside membership monitors.
  SamodeCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      Runnable beforeMemberChange) {
    this.beforeMemberChange = beforeMemberChange;
    this.channelRegistry = channelRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (AdminPrivilege.rejectUnlessAuthorized(session, extensionRegistry, serverName.get())) {
      return;
    }
    if (AdminPrivilege.rejectIfTooFewParams(
        session, serverName.get(), "SAMODE", message.params(), 2)) {
      return;
    }
    String channelName = message.params().getFirst();
    String modeArg = message.params().get(1);

    var found = channelRegistry.lookup(channelName);
    if (found.isEmpty() || !found.get().members().contains(session)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOTONCHANNEL,
          channelName,
          "You're not on that channel");
      return;
    }
    Channel channel = found.get();

    char sign;
    if ("+o".equals(modeArg)) {
      sign = '+';
    } else if ("-o".equals(modeArg)) {
      sign = '-';
    } else {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNMODE,
          modeArg,
          "is unknown mode char to me");
      return;
    }

    beforeMemberChange.run();
    boolean accepted;
    synchronized (channel) {
      accepted = channel.members().contains(session);
      if (accepted) {
        if (sign == '+') {
          channel.grantOperator(session);
        } else {
          channel.revokeOperator(session);
        }
      }
    }
    if (!accepted) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_USERNOTINCHANNEL,
          session.nickname(),
          "They aren't on that channel");
      return;
    }

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    Message echo =
        new Message(
            Map.of(),
            presentedForm,
            Command.MODE,
            "MODE",
            List.of(channel.name(), sign + "o", session.nickname()));
    Recipients.deliver(channel.members(), echo);
  }
}
