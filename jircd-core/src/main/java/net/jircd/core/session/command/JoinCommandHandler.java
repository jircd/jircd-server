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
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.Recipients;
import net.jircd.protocol.ChannelName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.Message;
import net.jircd.protocol.NickMask;
import net.jircd.protocol.NumericReply;

/**
 * {@code JOIN} — create-or-join (FR-003), with the {@code JOIN}-gate check point FR-043 requires:
 * ban-mask (FR-062), invite-only (FR-065), user-limit, and channel-key (006-complete-core-protocol
 * FR-002/FR-005), each checked independently — a pending invitation exempts the latter three alike,
 * peeked once and consumed at most once per join, never per gate (research.md "Story 1"
 * invite-exemption decision).
 *
 * <p>Accepts a comma-separated list of channel names, with an optional matching comma-separated
 * list of keys (RFC1459 §4.2.1, 005-fix-batch-conformance FR-013) — each named channel is processed
 * independently through the same single-channel logic; one channel failing its own check doesn't
 * stop the others in the same command from being processed.
 */
public final class JoinCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final IntSupplier channelNameMaxLength;

  public JoinCommandHandler(
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
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "JOIN",
          "Not enough parameters");
      return;
    }
    // 005-fix-batch-conformance FR-013 — comma-separated channel (and optional matching
    // comma-separated key) lists are core JOIN grammar (RFC1459 §4.2.1), not an extension. A
    // fewer-keys-than-channels list leaves the remainder keyless. Each named channel is
    // processed independently — one failing its own check doesn't stop the others.
    List<String> names = List.of(message.params().getFirst().split(",", -1));
    List<String> keys =
        message.params().size() > 1 ? List.of(message.params().get(1).split(",", -1)) : List.of();
    for (int i = 0; i < names.size(); i++) {
      joinOne(session, names.get(i), i < keys.size() ? keys.get(i) : null);
    }
  }

  private void joinOne(ClientSession session, String name, String suppliedKey) {
    if (!ChannelName.isValid(name, channelNameMaxLength.getAsInt())) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_BADCHANMASK, name, "Bad Channel Mask");
      return;
    }

    var failure = new java.util.concurrent.atomic.AtomicReference<AdmissionFailure>();
    var joined =
        channelRegistry.join(
            name,
            session,
            channel -> {
              failure.set(admissionFailure(channel, session, suppliedKey));
              return failure.get() == null;
            });
    if (joined.isEmpty()) {
      AdmissionFailure rejected = failure.get();
      if (rejected != null) {
        Replies.send(
            session, serverName.get(), rejected.reply(), rejected.channelName(), rejected.reason());
      }
      return;
    }
    var result = joined.get();
    if (result.alreadyMember()) {
      return;
    }
    Channel channel = result.channel();

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    Message joinNotification =
        new Message(Map.of(), presentedForm, Command.JOIN, "JOIN", List.of(channel.name()));
    Recipients.deliver(result.recipients(), joinNotification);

    // 005-fix-batch-conformance FR-014 — the same 332-only shape TopicCommandHandler's own
    // query path already uses (this project tracks no set-at timestamp, so no 333 either).
    if (channel.topic() != null) {
      Replies.send(
          session, serverName.get(), NumericReply.RPL_TOPIC, channel.name(), channel.topic());
    }

    sendNamesReply(session, channel, serverName.get(), result.memberListing());
  }

  private record AdmissionFailure(NumericReply reply, String channelName, String reason) {}

  private AdmissionFailure admissionFailure(
      Channel channel, ClientSession session, String suppliedKey) {
    if (!passesBanGate(channel, session)) {
      return new AdmissionFailure(
          NumericReply.ERR_BANNEDFROMCHAN, channel.name(), "Cannot join channel (+b)");
    }

    // 006-complete-core-protocol FR-002/FR-005 (spec.md Clarifications) — a pending invitation
    // exempts a join from +i, +l, and +k alike; peeked here (not consumed) so a channel with more
    // than one of these active doesn't have its invitation consumed by the first gate checked and
    // then incorrectly fail the second. Consumed exactly once, below, only once every applicable
    // gate has passed.
    boolean invited = channel.isInvited(session.nickname());

    if (!invited && channel.activeModes().contains(CoreChannelModes.INVITE_ONLY)) {
      return new AdmissionFailure(
          NumericReply.ERR_INVITEONLYCHAN, channel.name(), "Cannot join channel (+i)");
    }
    if (!invited
        && channel.memberLimit() > 0
        && channel.members().size() >= channel.memberLimit()) {
      return new AdmissionFailure(
          NumericReply.ERR_CHANNELISFULL, channel.name(), "Cannot join channel (+l)");
    }
    if (!invited && channel.key() != null && !channel.key().equals(suppliedKey)) {
      return new AdmissionFailure(
          NumericReply.ERR_BADCHANNELKEY, channel.name(), "Cannot join channel (+k)");
    }

    if (invited) {
      channel.consumeInvitation(session.nickname());
    }

    return null;
  }

  public static void sendNamesReply(ClientSession requester, Channel channel, String serverName) {
    sendNamesReply(requester, channel, serverName, channel.memberListing());
  }

  /** Sends a membership snapshot captured by a join transition, followed by its closing numeric. */
  public static void sendNamesReply(
      ClientSession requester, Channel channel, String serverName, List<String> memberListing) {
    sendNamesLine(requester, channel, serverName, memberListing);
    Replies.send(
        requester, serverName, NumericReply.RPL_ENDOFNAMES, channel.name(), "End of /NAMES list");
  }

  /**
   * The {@code RPL_NAMREPLY} half of {@link #sendNamesReply} on its own, with no closing {@code
   * RPL_ENDOFNAMES} — extracted so a bare, argument-less {@code NAMES} (006-complete-core-protocol
   * FR-010, {@code NamesCommandHandler}) can call this once per visible channel and send exactly
   * one closing reply of its own afterward, instead of one per channel.
   */
  public static void sendNamesLine(ClientSession requester, Channel channel, String serverName) {
    sendNamesLine(requester, channel, serverName, channel.memberListing());
  }

  private static void sendNamesLine(
      ClientSession requester, Channel channel, String serverName, List<String> memberListing) {
    String visibility = visibilitySymbol(channel);
    Replies.sendListed(
        requester,
        serverName,
        NumericReply.RPL_NAMREPLY,
        List.of(visibility, channel.name()),
        memberListing);
  }

  private static String visibilitySymbol(Channel channel) {
    if (channel.activeModes().contains(CoreChannelModes.SECRET)) {
      return "@";
    }
    if (channel.activeModes().contains(CoreChannelModes.PRIVATE)) {
      return "*";
    }
    return "=";
  }

  private boolean passesBanGate(Channel channel, ClientSession session) {
    if (channel.bans().isEmpty()) {
      return true;
    }
    String presented = PresentedIdentity.presentedForm(session, extensionRegistry);
    String real = Hostmask.format(session.nickname(), session.ident(), session.realHostname());
    for (var ban : channel.bans()) {
      if (NickMask.matches(presented, ban.mask()) || NickMask.matches(real, ban.mask())) {
        return false;
      }
    }
    return true;
  }
}
