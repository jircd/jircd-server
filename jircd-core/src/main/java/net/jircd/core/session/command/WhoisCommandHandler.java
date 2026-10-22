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
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PresentedIdentity;
import net.jircd.core.session.UserMode;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code WHOIS} — nickname/ident/hostname/real-name lookup, the sender's own session if no target
 * is given (FR-037). The hostname follows FR-038's three-tier resolution, reusing {@link
 * PresentedIdentity}'s existing computation rather than a new, independent one (research.md "Cloak
 * extension boundary"): real for a self-lookup or an administrator requester, otherwise the same
 * presented value the target's message hostmask already shows this requester. Core protocol
 * behavior, never an optional extension.
 */
public final class WhoisCommandHandler implements CommandHandler {

  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public WhoisCommandHandler(
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    ClientSession target;
    if (message.params().isEmpty()) {
      target = session;
    } else {
      // The optional RFC 2812 two-parameter form is WHOIS <target-server> <nickname> — the
      // nickname is always the last parameter regardless of whether one or two were given. The
      // leading server-name parameter, if present, is accepted but not used to route anywhere
      // (this server has no federation to route to, FR-021).
      String targetNickname = message.params().getLast();
      var found = nicknameRegistry.lookup(targetNickname);
      if (found.isEmpty()) {
        Replies.send(
            session,
            serverName.get(),
            NumericReply.ERR_NOSUCHNICK,
            targetNickname,
            "No such nick/channel");
        // 005-fix-batch-conformance FR-020 — RFC requires WHOIS's reply sequence to always
        // close with 318, success or not; this path used to skip it.
        Replies.send(
            session,
            serverName.get(),
            NumericReply.RPL_ENDOFWHOIS,
            targetNickname,
            "End of /WHOIS list");
        return;
      }
      target = found.get();
    }

    String hostname =
        target == session || session.isAdministrator()
            ? target.realHostname()
            : PresentedIdentity.displayHostname(target, extensionRegistry);

    Replies.sendFitted(
        session,
        serverName.get(),
        NumericReply.RPL_WHOISUSER,
        List.of(target.nickname(), target.ident(), hostname, "*"),
        target.realname());

    Replies.send(
        session,
        serverName.get(),
        NumericReply.RPL_WHOISSERVER,
        target.nickname(),
        serverName.get(),
        "jircd IRC server");

    if (target.isAway()) {
      Replies.send(
          session, serverName.get(), NumericReply.RPL_AWAY, target.nickname(), target.awayReason());
    }

    if (target.userModes().contains(UserMode.OPERATOR)) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.RPL_WHOISOPERATOR,
          target.nickname(),
          "is an IRC operator");
    }

    Replies.send(
        session,
        serverName.get(),
        NumericReply.RPL_ENDOFWHOIS,
        target.nickname(),
        "End of /WHOIS list");
  }
}
