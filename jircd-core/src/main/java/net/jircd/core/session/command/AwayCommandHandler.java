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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code AWAY} — sets or clears the sender's away status (002-extended-irc-commands
 * FR-004/FR-005/FR-006). A reason given while already away replaces the previous one in place,
 * confirmed the same way as setting it for the first time; no parameter clears it. Every successful
 * change also broadcasts an {@code AWAY} notice to channel-mates who negotiated the {@code
 * away-notify} capability (011-away-notify-batch FR-001 through FR-006).
 */
public final class AwayCommandHandler implements CommandHandler {

  private final Supplier<String> serverName;
  private final ExtensionRegistry extensionRegistry;

  public AwayCommandHandler(Supplier<String> serverName, ExtensionRegistry extensionRegistry) {
    this.serverName = serverName;
    this.extensionRegistry = extensionRegistry;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String server = serverName.get();
    // 005-fix-batch-conformance FR-022 — an empty trailing argument clears away status the same
    // way a fully-absent one already does, rather than setting an empty-string away reason.
    if (message.params().isEmpty() || message.params().getFirst().isEmpty()) {
      session.setAwayReason(null);
      Replies.send(
          session, server, NumericReply.RPL_UNAWAY, "You are no longer marked as being away");
      broadcastAwayChange(session, null);
      return;
    }
    String reason = message.params().getFirst();
    session.setAwayReason(reason);
    Replies.send(session, server, NumericReply.RPL_NOWAWAY, "You have been marked as being away");
    broadcastAwayChange(session, reason);
  }

  /**
   * Fans out to every channel-mate opted into {@code away-notify}, deduplicated to one notice per
   * recipient regardless of how many channels they share with {@code session} (FR-004) — unlike
   * {@code NickCommandHandler.broadcastNickChange}'s simpler per-channel loop, which was never
   * required to dedupe.
   */
  private void broadcastAwayChange(ClientSession session, String reason) {
    Set<ClientSession> recipients = new LinkedHashSet<>();
    for (var channel : session.channelMemberships()) {
      for (ClientSession member : channel.members()) {
        if (member != session
            && member.negotiatedCapabilities().contains(CapabilityName.AWAY_NOTIFY)) {
          recipients.add(member);
        }
      }
    }
    if (recipients.isEmpty()) {
      return;
    }
    String prefix = PresentedIdentity.presentedForm(session, extensionRegistry);
    List<String> params = reason != null ? List.of(reason) : List.of();
    Message notice = new Message(Map.of(), prefix, Command.AWAY, "AWAY", params);
    for (ClientSession recipient : recipients) {
      if (recipient.writer() != null) {
        recipient.writer().enqueueRaw(notice);
      }
    }
  }
}
