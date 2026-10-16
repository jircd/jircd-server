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
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.Recipients;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code PART} — leaves a channel. {@code <reason>}, if given, is echoed (absent otherwise — no
 * default synthesized, unlike {@code QUIT}). Removes the parting session from the channel's
 * members, operators, and voiced sets together (data-model.md {@code Channel} validation rules).
 */
public final class PartCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public PartCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this.channelRegistry = channelRegistry;
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
          "PART",
          "Not enough parameters");
      return;
    }
    String channelName = message.params().getFirst();
    String reason = message.params().size() > 1 ? message.params().get(1) : null;

    var found = channelRegistry.lookup(channelName);
    var departed = found.flatMap(channel -> channelRegistry.part(channel, session));
    if (departed.isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NOTONCHANNEL,
          channelName,
          "You're not on that channel");
      return;
    }
    Channel channel = departed.get().channel();

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    List<String> params =
        reason != null ? List.of(channel.name(), reason) : List.of(channel.name());
    Message partNotification = new Message(Map.of(), presentedForm, Command.PART, "PART", params);
    Recipients.deliver(departed.get().recipients(), partNotification);
  }
}
