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
import net.jircd.core.extension.capability.EventKind;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code KICK} — operator-only channel member removal. Always available, never gated by extension
 * toggling.
 */
public final class KickCommandHandler implements CommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(KickCommandHandler.class);

  private final ChannelRegistry channelRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final Runnable beforeTransition;
  private final Runnable afterTransition;

  public KickCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this(channelRegistry, extensionRegistry, serverName, () -> {}, () -> {});
  }

  /** Test-only seam: runs after the pre-checks and prefix preparation, before the transition. */
  KickCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      Runnable beforeTransition) {
    this(channelRegistry, extensionRegistry, serverName, beforeTransition, () -> {});
  }

  /**
   * Test-only seams: {@code beforeTransition} runs after the pre-checks and prefix preparation,
   * before the transition; {@code afterTransition} runs once a kick has committed, before its
   * notification and committed event.
   */
  KickCommandHandler(
      ChannelRegistry channelRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      Runnable beforeTransition,
      Runnable afterTransition) {
    this.afterTransition = afterTransition;
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
    // The kicked member's identity for the committed event is captured here too, while it is the
    // one the KICK names: a NICK of the target racing the kick must not rename the event. It is
    // never resolved again later, so a capture that fails or finds nobody skips the event.
    boolean listened = extensionRegistry.committedEvents().listensTo(EventKind.KICKED);
    ClientSession candidate = null;
    String candidateForm = null;
    String captureFailure = "target not found by name";
    if (listened) {
      candidate = channel.findMember(targetNickname).orElse(null);
      if (candidate != null) {
        try {
          candidateForm = PresentedIdentity.presentedForm(candidate, extensionRegistry);
        } catch (RuntimeException e) {
          // Only the optional event capture failed: the KICK itself must still go through. Only
          // the type is kept for the log; the message may carry extension or client data.
          captureFailure = e.getClass().getSimpleName();
        }
      }
    }
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
        afterTransition.run();
        List<String> params = List.of(channel.name(), departure.departed().nickname(), reason);
        Message notification = new Message(Map.of(), presentedForm, Command.KICK, "KICK", params);
        Recipients.deliver(departure.recipients(), notification);
        if (listened && candidateForm != null && departure.departed() == candidate) {
          extensionRegistry
              .committedEvents()
              .kicked(
                  presentedForm,
                  departure.departed(),
                  candidateForm,
                  channel.name(),
                  reason,
                  departure.recipients());
        } else if (listened) {
          LOG.warn("Committed KICKED event not published: {}", captureFailure);
        }
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
