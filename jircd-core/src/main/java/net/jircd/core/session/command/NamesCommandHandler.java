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
 * {@code NAMES} — current membership list of a channel, regardless of the requester's own
 * membership (FR-041); a {@code private}/{@code secret} channel is invisible to a non-member,
 * non-administrator requester, indistinguishable from a nonexistent one (FR-047). An invalid or
 * nonexistent single channel name gets only the closing {@code RPL_ENDOFNAMES} — RFC1459
 * §4.2.5/RFC2812 §3.2.5's own text: "there is no error reply for bad channel names"
 * (006-complete-core-protocol Polish, a pre-existing gap found and fixed alongside this feature's
 * bare-form addition below). A bare, argument-less {@code NAMES} lists every visible channel's
 * membership instead (006-complete-core-protocol FR-010), applying the identical visibility rule
 * per channel, closed by exactly one {@code RPL_ENDOFNAMES} targeted at {@code *} rather than one
 * per channel.
 */
public final class NamesCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public NamesCommandHandler(
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
      for (var channel : channelRegistry.all()) {
        if (!ChannelVisibility.isHiddenFrom(channel, session, extensionRegistry)) {
          JoinCommandHandler.sendNamesLine(extensionRegistry, session, channel, serverName.get());
        }
      }
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.RPL_ENDOFNAMES,
          List.of("*"),
          "End of /NAMES list");
      return;
    }
    String requestedName = message.params().getFirst();
    var found = channelRegistry.lookup(requestedName);
    if (found.isEmpty()
        || ChannelVisibility.isHiddenFrom(found.get(), session, extensionRegistry)) {
      // 006-complete-core-protocol Polish — "There is no error reply for bad channel names"
      // (RFC1459 §4.2.5/RFC2812 §3.2.5); an invalid or nonexistent single channel gets only the
      // closing RPL_ENDOFNAMES, the same as it would if it existed but had zero visible members —
      // never an error, unlike most other channel-targeted commands.
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.RPL_ENDOFNAMES,
          List.of(requestedName),
          "End of /NAMES list");
      return;
    }
    JoinCommandHandler.sendNamesReply(extensionRegistry, session, found.get(), serverName.get());
  }
}
