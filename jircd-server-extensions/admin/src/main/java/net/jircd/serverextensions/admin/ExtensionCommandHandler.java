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
import java.util.Locale;
import java.util.function.Supplier;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code EXTENSION <ENABLE|DISABLE> <extension-id>} — the in-band equivalent of the
 * configuration-file/{@code SIGHUP} toggle path (FR-011, FR-032), taking effect immediately and
 * touching no file (contracts/server-configuration.md "Path equivalence"). An id that was never
 * discovered — including {@code moderation}/{@code capability-negotiation}, which are core
 * mechanisms, never toggleable extensions (FR-035/FR-036) — is rejected the same way, since {@link
 * ExtensionRegistry#find} simply doesn't know either of them.
 */
public final class ExtensionCommandHandler implements CommandHandler {

  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public ExtensionCommandHandler(ExtensionRegistry extensionRegistry, Supplier<String> serverName) {
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (!AdminPrivilege.isAuthorized(session, extensionRegistry)) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_NOPRIVILEGES,
          List.of(),
          "Permission Denied- You're not an IRC operator");
      return;
    }
    if (message.params().size() < 2) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          List.of("EXTENSION"),
          "Not enough parameters");
      return;
    }
    String action = message.params().getFirst().toUpperCase(Locale.ROOT);
    String extensionId = message.params().get(1);

    if (extensionRegistry.find(extensionId).isEmpty()) {
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNCOMMAND,
          List.of(extensionId),
          "No such extension");
      return;
    }

    try {
      switch (action) {
        case "ENABLE" -> {
          extensionRegistry.enable(extensionId);
          reportOutcome(session, extensionId);
        }
        case "DISABLE" -> {
          extensionRegistry.disable(extensionId);
          reportOutcome(session, extensionId);
        }
        default ->
            Replies.sendFitted(
                session,
                serverName.get(),
                NumericReply.ERR_UNKNOWNCOMMAND,
                List.of("EXTENSION"),
                "Unknown action — use ENABLE or DISABLE");
      }
    } catch (RuntimeException e) {
      AdminNotices.send(
          session, serverName.get(), "Failed to toggle " + extensionId + ": " + e.getMessage());
    }
  }

  /**
   * Reports the state the toggle actually left, not the one requested. A repeated toggle is a no-op
   * that reports the unchanged state and sends no capability notification.
   */
  private void reportOutcome(ClientSession session, String extensionId) {
    Extension.State state = extensionRegistry.stateOf(extensionId);
    // Known here: the id was found before the toggle, and registrations are never removed.
    String outcome = state == null ? "unknown" : state.name().toLowerCase(Locale.ROOT);
    AdminNotices.send(session, serverName.get(), extensionId + " is now " + outcome);
  }
}
