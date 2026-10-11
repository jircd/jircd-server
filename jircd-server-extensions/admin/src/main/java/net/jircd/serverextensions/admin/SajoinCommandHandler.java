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
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.ChannelName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code SAJOIN} — the sender's own session joins a channel via the same create-or-join path
 * ordinary {@code JOIN} uses, but skipping the {@code JOIN}-gate check point entirely (FR-057,
 * research.md "Administrator channel override"). Grammar/UTF-8 validity are NOT skipped.
 */
public final class SajoinCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final IntSupplier channelNameMaxLength;

  public SajoinCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      IntSupplier channelNameMaxLength) {
    this.channelRegistry = channelRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.channelNameMaxLength = channelNameMaxLength;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (AdminPrivilege.rejectUnlessAuthorized(session, extensionRegistry, serverName.get())) {
      return;
    }
    if (AdminPrivilege.rejectIfTooFewParams(
        session, serverName.get(), "SAJOIN", message.params(), 1)) {
      return;
    }
    String name = message.params().getFirst();

    if (!ChannelName.isValid(name, channelNameMaxLength.getAsInt())) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_BADCHANMASK, name, "Bad Channel Mask");
      return;
    }

    Channel channel = channelRegistry.join(name, session, ignored -> true).orElseThrow();

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    Message joinNotification =
        new Message(Map.of(), presentedForm, Command.JOIN, "JOIN", List.of(channel.name()));
    for (ClientSession member : channel.members()) {
      if (member.writer() != null) {
        member.writer().enqueueRaw(joinNotification);
      }
    }

    JoinCommandHandler.sendNamesReply(session, channel, serverName.get());
  }
}
