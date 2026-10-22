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
import net.jircd.core.session.ChannelVisibility;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code LIST} — every currently active channel (FR-042); a {@code private}/{@code secret} channel
 * is simply omitted for a non-member, non-administrator requester (FR-047).
 */
public final class ListCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public ListCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this.channelRegistry = channelRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    Replies.sendFitted(
        session, serverName.get(), NumericReply.RPL_LISTSTART, List.of("Channel"), "Users Name");
    for (var channel : channelRegistry.all()) {
      if (ChannelVisibility.isHiddenFrom(channel, session, extensionRegistry)) {
        continue;
      }
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.RPL_LIST,
          List.of(channel.name(), String.valueOf(channel.members().size())),
          channel.topic() != null ? channel.topic() : "");
    }
    Replies.sendFitted(
        session, serverName.get(), NumericReply.RPL_LISTEND, List.of(), "End of /LIST");
  }
}
