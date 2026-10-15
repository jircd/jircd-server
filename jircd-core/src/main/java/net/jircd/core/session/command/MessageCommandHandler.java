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

import java.util.Set;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.OutboundMessage;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.NickMask;
import net.jircd.protocol.NumericReply;

/**
 * {@code PRIVMSG}/{@code NOTICE} — channel and direct messaging (FR-004, FR-005). Builds one shared
 * {@link OutboundMessage} and enqueues it onto every recipient's {@code SessionWriter} — this
 * handler applies no capability-dependent formatting itself; that happens per-recipient at drain
 * time.
 */
public final class MessageCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final boolean notice;

  public MessageCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      boolean notice) {
    this.channelRegistry = channelRegistry;
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.notice = notice;
  }

  @Override
  public void handle(ClientSession session, net.jircd.protocol.Message message) {
    String commandName = notice ? "NOTICE" : "PRIVMSG";
    if (message.params().size() < 2) {
      if (!notice) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_NEEDMOREPARAMS,
            commandName,
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
        Replies.send(session, serverName.get(), NumericReply.ERR_NOTEXTTOSEND, "No text to send");
      }
      return;
    }

    Set<ClientSession> recipients =
        resolveRecipients(
            channelRegistry,
            nicknameRegistry,
            extensionRegistry,
            serverName,
            session,
            target,
            !notice);
    if (recipients.isEmpty()) {
      return;
    }
    if (!target.startsWith("#")) {
      // Direct-message form only (002-extended-irc-commands FR-007) — a channel target's away
      // members, if any, are not individually called out.
      ClientSession recipient = recipients.iterator().next();
      if (recipient.isAway()) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.RPL_AWAY,
            recipient.nickname(),
            recipient.awayReason());
      }
    }

    String presentedForm = PresentedIdentity.presentedForm(session, extensionRegistry);
    // 005-fix-batch-conformance FR-010 — pass the sender's own tags through, the same way
    // TagmsgCommandHandler already does; CapabilityTagRenderer merges these into the
    // per-recipient rendered map alongside server-contributed ones (msgid/time).
    OutboundMessage outbound =
        OutboundMessage.now(presentedForm, commandName, target, body, message.tags());
    boolean echoToSender = includeSenderInFanOut(extensionRegistry, session);
    boolean direct = !target.startsWith("#");
    for (ClientSession recipient : recipients) {
      if (recipient == session && !direct && !echoToSender) {
        continue; // echo-message (Story 2) decides whether the sender sees its own channel message
      }
      if (recipient.writer() != null) {
        recipient.writer().enqueue(outbound);
      }
    }
    if (echoToSender && direct && !recipients.contains(session) && session.writer() != null) {
      // 005-fix-batch-conformance FR-003 — a direct message's recipients set is Set.of(target),
      // so the loop above only reaches the sender when it targeted itself; that case is already
      // delivered there as the DM itself (issue #18), so echoing it again would duplicate it.
      session.writer().enqueue(outbound);
    }
  }

  /**
   * Resolves {@code target} into its recipient set for {@code PRIVMSG}/{@code NOTICE}/{@code
   * TAGMSG} alike (002-extended-irc-commands FR-022, research.md "TAGMSG delivery reuse"): a
   * channel name must exist and pass the {@code SEND} gate below, a nickname must be connected.
   * Returns an empty set on failure, having already sent the appropriate error to {@code session}
   * when {@code reportErrors} is {@code true} ({@code NOTICE}-style silence otherwise).
   */
  static Set<ClientSession> resolveRecipients(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      ClientSession session,
      String target,
      boolean reportErrors) {
    if (target.startsWith("#")) {
      var channel = channelRegistry.lookup(target);
      if (channel.isEmpty() || !passesSendGate(channel.get(), session, extensionRegistry)) {
        if (reportErrors) {
          Replies.send(
              session,
              serverName.get(),
              NumericReply.ERR_CANNOTSENDTOCHAN,
              target,
              "Cannot send to channel");
        }
        return Set.of();
      }
      return channel.get().members();
    }
    var recipient = nicknameRegistry.lookup(target);
    if (recipient.isEmpty()) {
      if (reportErrors) {
        Replies.send(
            session, serverName.get(), NumericReply.ERR_NOSUCHNICK, target, "No such nick/channel");
      }
      return Set.of();
    }
    return Set.of(recipient.get());
  }

  /** Whether {@code echo-message} (Story 2) wants {@code session} included in its own fan-out. */
  static boolean includeSenderInFanOut(ExtensionRegistry extensionRegistry, ClientSession session) {
    return extensionRegistry.enabled().stream()
        .filter(net.jircd.core.extension.CapabilityExtension.class::isInstance)
        .map(net.jircd.core.extension.CapabilityExtension.class::cast)
        .anyMatch(capability -> capability.includeSenderInFanOut(session));
  }

  /**
   * The {@code SEND}-gate check point FR-043 requires: every currently-recognized flag whose {@code
   * gates} includes {@code SEND}, checked independently — {@code members-only} requires membership;
   * {@code moderated} requires operator or voice (FR-045); {@code ban-mask} requires neither the
   * sender's presented nor real identity to match any active ban (FR-062, dual-matched to resist
   * {@code cloak} evasion) — muting a matched sender without removing them from members.
   */
  private static boolean passesSendGate(
      Channel channel, ClientSession session, ExtensionRegistry extensionRegistry) {
    if (channel.activeModes().contains(CoreChannelModes.MEMBERS_ONLY)
        && !channel.members().contains(session)) {
      return false;
    }
    if (channel.activeModes().contains(CoreChannelModes.MODERATED)
        && !channel.operators().contains(session)
        && !channel.voiced().contains(session)) {
      return false;
    }
    if (!channel.bans().isEmpty()) {
      String presented = PresentedIdentity.presentedForm(session, extensionRegistry);
      String real = Hostmask.format(session.nickname(), session.ident(), session.realHostname());
      for (var ban : channel.bans()) {
        if (NickMask.matches(presented, ban.mask()) || NickMask.matches(real, ban.mask())) {
          return false;
        }
      }
    }
    return true;
  }
}
