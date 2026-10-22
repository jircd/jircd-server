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
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.SecurityEventLog;
import net.jircd.protocol.ChannelName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code INVITE} — records a per-nickname invitation on a channel, pairing with the {@code
 * invite-only} flag ({@code ModeCommandHandler}/{@code JoinCommandHandler}'s {@code JOIN}-gate).
 * The invitation itself works regardless of whether {@code invite-only} is active; only *issuing*
 * one while it's active requires the sender to be an operator. Inviting to a channel that doesn't
 * exist anywhere on the server MUST succeed — RFC 2812 §3.2.7's own error set has no
 * not-found-channel case; unlike {@code JOIN}, this never creates the channel (research.md "Story
 * 5"), so a not-yet-existing target has no {@code Channel} to record the invitation against — it's
 * delivered as a one-time notification only, not a durable, later-checkable exemption the way an
 * invitation on an already-existing channel is.
 */
public final class InviteCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final IntSupplier channelNameMaxLength;

  public InviteCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      IntSupplier channelNameMaxLength) {
    this.channelNameMaxLength = channelNameMaxLength;
    this.channelRegistry = channelRegistry;
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().size() < 2) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          List.of("INVITE"),
          "Not enough parameters");
      return;
    }
    String targetNickname = message.params().getFirst();
    String channelName = message.params().get(1);

    // A genuinely nonexistent channel has no membership or
    // invite-only state to check against, so those checks below are skipped entirely (not
    // vacuously rejected); an EXISTING channel the inviter isn't a member of is still rejected,
    // unchanged.
    var found = channelRegistry.lookup(channelName);
    if (found.isPresent() && !found.get().members().contains(session)) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_NOTONCHANNEL,
          List.of(channelName),
          "You're not on that channel");
      return;
    }
    if (found.isEmpty() && !ChannelName.isValid(channelName, channelNameMaxLength.getAsInt())) {
      // The name is echoed in RPL_INVITING and the INVITE itself, so bound it as JOIN does.
      Replies.sendEchoing(
          session, serverName.get(), NumericReply.ERR_BADCHANMASK, channelName, "Bad Channel Mask");
      return;
    }
    Channel channel = found.orElse(null);

    var target = nicknameRegistry.lookup(targetNickname);
    if (target.isEmpty()) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHNICK,
          List.of(targetNickname),
          "No such nick/channel");
      return;
    }
    if (channel != null && channel.members().contains(target.get())) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_USERONCHANNEL,
          List.of(targetNickname, channel.name()),
          "is already on channel");
      return;
    }
    if (channel != null
        && channel.activeModes().contains(CoreChannelModes.INVITE_ONLY)
        && !channel.operators().contains(session)) {
      SecurityEventLog.rejectedModerationAction(
          session.connectionId(), "INVITE", channelName, "not a channel operator");
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_CHANOPRIVSNEEDED,
          List.of(channel.name()),
          "You're not channel operator");
      return;
    }

    if (channel != null) {
      channel.invite(target.get().nickname());
    }
    Replies.send(
        session, serverName.get(), NumericReply.RPL_INVITING, target.get().nickname(), channelName);

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    Message inviteMessage =
        new Message(
            Map.of(),
            presentedForm,
            Command.INVITE,
            "INVITE",
            List.of(target.get().nickname(), channelName));
    Replies.sendChecked(target.get(), inviteMessage);
  }
}
