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
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.CaseMapping;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.protocol.Command;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code NICK} — claims/changes a nickname (FR-001/FR-002). Format ({@code 432}) and uniqueness
 * ({@code 433}) are independent, sequential checks; {@code 431} for a missing argument. Any of
 * these sent before this session has successfully claimed a nickname address {@code *} (FR-053,
 * {@link Replies}). A successful change broadcasts {@code NICK} to the changing client itself and
 * every channel it's currently a member of (005-fix-batch-conformance FR-001/FR-002) — the same
 * fan-out shape {@code JoinCommandHandler}'s {@code JOIN} notification already uses.
 */
public final class NickCommandHandler implements CommandHandler {

  private final NicknameRegistry nicknameRegistry;
  private final java.util.function.Supplier<String> serverName;
  private final java.util.function.IntSupplier nicknameMaxLength;
  private final RegistrationCompletion registrationCompletion;
  private final ExtensionRegistry extensionRegistry;

  public NickCommandHandler(
      NicknameRegistry nicknameRegistry,
      java.util.function.Supplier<String> serverName,
      java.util.function.IntSupplier nicknameMaxLength,
      RegistrationCompletion registrationCompletion,
      ExtensionRegistry extensionRegistry) {
    this.nicknameRegistry = nicknameRegistry;
    this.serverName = serverName;
    this.nicknameMaxLength = nicknameMaxLength;
    this.registrationCompletion = registrationCompletion;
    this.extensionRegistry = extensionRegistry;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty() || message.params().getFirst().isEmpty()) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_NONICKNAMEGIVEN, "No nickname given");
      return;
    }
    String requested = message.params().getFirst();

    if (!Hostmask.isValidNickname(requested, nicknameMaxLength.getAsInt())) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_ERRONEUSNICKNAME,
          requested,
          "Erroneous nickname");
      return;
    }

    if (!nicknameRegistry.claim(requested, session)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NICKNAMEINUSE,
          requested,
          "Nickname is already in use");
      return;
    }

    String previous = session.nickname();
    // A case-only (or otherwise RFC1459-equivalent) change keeps the same registry key. Releasing
    // the previous spelling in that case would remove this session's newly-confirmed claim.
    if (previous != null && !CaseMapping.fold(previous).equals(CaseMapping.fold(requested))) {
      nicknameRegistry.release(previous, session);
    }
    // The prefix on a NICK change notification is the identity recipients already know the
    // sender by — captured before setNickname mutates it below.
    boolean isChange =
        session.lifecycle().isRegistered() && previous != null && !previous.equals(requested);
    String oldHostmask =
        isChange ? PresentedIdentity.presentedForm(session, extensionRegistry) : null;

    session.setNickname(requested);
    registrationCompletion.tryComplete(session);

    if (isChange) {
      broadcastNickChange(session, oldHostmask, requested);
    }
  }

  private static void broadcastNickChange(
      ClientSession session, String oldHostmask, String requested) {
    Message notification =
        new Message(Map.of(), oldHostmask, Command.NICK, "NICK", List.of(requested));
    if (session.writer() != null) {
      session.writer().enqueueRaw(notification);
    }
    for (var channel : session.channelMemberships()) {
      for (ClientSession member : channel.members()) {
        if (member != session && member.writer() != null) {
          member.writer().enqueueRaw(notification);
        }
      }
    }
  }
}
