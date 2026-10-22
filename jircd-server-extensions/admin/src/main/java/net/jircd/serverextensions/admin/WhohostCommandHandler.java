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
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code WHOHOST} — reads {@link ClientSession#realHostname()} directly, bypassing any active
 * {@code cloak} extension (FR-031/FR-032), the same real-value source of truth {@code
 * UserIdentity.presentedForm} reads before applying cloak's display transform.
 */
public final class WhohostCommandHandler implements CommandHandler {

  private final NicknameRegistry nicknameRegistry;
  private final ExtensionRegistry extensionRegistry;
  private final Supplier<String> serverName;

  public WhohostCommandHandler(
      NicknameRegistry nicknameRegistry,
      ExtensionRegistry extensionRegistry,
      Supplier<String> serverName) {
    this.nicknameRegistry = nicknameRegistry;
    this.extensionRegistry = extensionRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (AdminPrivilege.rejectUnlessAuthorized(session, extensionRegistry, serverName.get())) {
      return;
    }
    if (AdminPrivilege.rejectIfTooFewParams(
        session, serverName.get(), "WHOHOST", message.params(), 1)) {
      return;
    }
    String targetNickname = message.params().getFirst();
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
    AdminNotices.send(
        session,
        serverName.get(),
        target.get().nickname() + " is connecting from " + target.get().realHostname());
  }
}
