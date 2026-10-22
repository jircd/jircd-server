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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.CaseMapping;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ChannelVisibility;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.UserMode;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code WHO} — dispatches on its single argument's shape (FR-061): a channel name reuses {@code
 * NAMES}'s exact membership-visibility rules verbatim (never filtered by {@code invisible} or
 * {@code whoMaskEnabled}); a {@code *}/{@code ?}-containing argument is a wildcard nickname match;
 * anything else is an exact nickname; omitted matches every connected session. The latter three
 * forms exclude an {@code invisible} match unless the requester shares a channel with it or holds
 * administrator privilege; the mask and no-argument forms only are additionally short-circuited to
 * zero matches when {@code ServerConfiguration.whoMaskEnabled} is {@code false} for a
 * non-administrator (research.md "WHO and invisibility" — the exact-nickname form is deliberately
 * untouched by that setting). Each match's hostname reuses {@code WHOIS}'s FR-038 resolution
 * verbatim. Core protocol behavior, never an optional extension.
 */
public final class WhoCommandHandler implements CommandHandler {

  private final ChannelRegistry channelRegistry;
  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;
  private final BooleanSupplier whoMaskEnabled;

  public WhoCommandHandler(
      ChannelRegistry channelRegistry,
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName,
      BooleanSupplier whoMaskEnabled) {
    this.channelRegistry = channelRegistry;
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
    this.whoMaskEnabled = whoMaskEnabled;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String arg = message.params().isEmpty() ? null : message.params().getFirst();

    if (arg != null && arg.charAt(0) == '#') {
      handleChannelForm(session, arg);
      return;
    }

    boolean isMask = arg != null && (arg.indexOf('*') >= 0 || arg.indexOf('?') >= 0);
    boolean maskGatedForm = isMask || arg == null;

    if (maskGatedForm && !session.isAdministrator() && !whoMaskEnabled.getAsBoolean()) {
      sendEndOfWho(session, arg);
      return;
    }

    if (arg != null && !isMask) {
      // 005-fix-batch-conformance FR-021 — an exact-nickname lookup bypasses invisibility
      // entirely; only the mask and no-argument forms respect +i (unchanged below).
      nicknameRegistry.lookup(arg).ifPresent(target -> sendWhoReply(session, target, "*", null));
      sendEndOfWho(session, arg);
      return;
    }

    Collection<ClientSession> candidates =
        arg == null
            ? nicknameRegistry.all()
            : nicknameRegistry.all().stream()
                .filter(s -> CaseMapping.matches(s.nickname(), arg))
                .toList();

    for (ClientSession target : candidates) {
      if (isVisibleTo(session, target)) {
        sendWhoReply(session, target, "*", null);
      }
    }
    sendEndOfWho(session, arg);
  }

  private void handleChannelForm(ClientSession session, String channelName) {
    var found = channelRegistry.lookup(channelName);
    if (found.isEmpty()
        || ChannelVisibility.isHiddenFrom(found.get(), session, extensionRegistry)) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_NOSUCHCHANNEL,
          List.of(channelName),
          "No such channel");
      return;
    }
    Channel channel = found.get();
    for (ClientSession member : channel.members()) {
      sendWhoReply(session, member, channel.name(), channel);
    }
    sendEndOfWho(session, channel.name());
  }

  private static boolean isVisibleTo(ClientSession requester, ClientSession target) {
    if (!target.userModes().contains(UserMode.INVISIBLE)) {
      return true;
    }
    if (requester.isAdministrator()) {
      return true;
    }
    return !Collections.disjoint(requester.channelMemberships(), target.channelMemberships());
  }

  private void sendWhoReply(
      ClientSession requester, ClientSession target, String channelOrStar, Channel channel) {
    String hostname =
        target == requester || requester.isAdministrator()
            ? target.realHostname()
            : PresentedIdentity.displayHostname(target, extensionRegistry);

    StringBuilder flags = new StringBuilder(target.isAway() ? "G" : "H");
    if (target.userModes().contains(UserMode.OPERATOR)) {
      flags.append('*');
    }
    if (channel != null) {
      flags.append(
          channel.statusPrefixes(
              target, requester.negotiatedCapabilities().contains(CapabilityName.MULTI_PREFIX)));
    }

    Replies.sendFitted(
        requester,
        serverName.get(),
        NumericReply.RPL_WHOREPLY,
        List.of(
            channelOrStar,
            target.ident(),
            hostname,
            serverName.get(),
            target.nickname(),
            flags.toString()),
        "0 " + target.realname());
  }

  private void sendEndOfWho(ClientSession session, String arg) {
    Replies.sendFitted(
        session,
        serverName.get(),
        NumericReply.RPL_ENDOFWHO,
        List.of(arg != null ? arg : "*"),
        "End of /WHO list");
  }
}
