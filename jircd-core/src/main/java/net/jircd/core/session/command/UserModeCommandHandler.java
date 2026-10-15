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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.jircd.core.session.CaseMapping;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.UserMode;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.ModeChange;
import net.jircd.protocol.ModeStringParser;
import net.jircd.protocol.NumericReply;

/**
 * {@code MODE} (user form) — self-only (FR-044): a session may only query or change its own user
 * modes, never another session's. {@code operator} can only be acquired via {@code OPER} (Story 6),
 * never set directly here.
 */
public final class UserModeCommandHandler implements CommandHandler {

  private final Supplier<String> serverName;

  public UserModeCommandHandler(Supplier<String> serverName) {
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "MODE",
          "Not enough parameters");
      return;
    }
    String target = message.params().getFirst();
    if (session.nickname() == null
        || !CaseMapping.fold(session.nickname()).equals(CaseMapping.fold(target))) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_USERSDONTMATCH,
          "Cannot change mode for other users");
      return;
    }

    if (message.params().size() < 2) {
      Replies.send(session, serverName.get(), NumericReply.RPL_UMODEIS, formatModes(session));
      return;
    }

    String[] modeArgs = message.params().subList(1, message.params().size()).toArray(new String[0]);
    List<ModeChange> changes = ModeStringParser.parse(modeArgs);
    List<ModeChange> applied = new ArrayList<>();

    for (ModeChange change : changes) {
      UserMode mode = findByFlag(change.flag());
      if (mode == null) {
        Replies.send(
            session, serverName.get(), NumericReply.ERR_UMODEUNKNOWNFLAG, "Unknown MODE flag");
        break;
      }
      if (change.sign() == '+') {
        if (session.userModes().contains(mode)) {
          applied.add(change); // already set — harmless no-op
          continue;
        }
        if (!mode.clientSettable()) {
          Replies.send(
              session,
              serverName.get(),
              NumericReply.ERR_NOPRIVILEGES,
              "Permission Denied- You're not an IRC operator");
          break;
        }
        session.userModes().add(mode);
        applied.add(change);
      } else {
        if (mode == UserMode.OPERATOR) {
          session.revokeAdministratorPrivilege();
        } else {
          session.userModes().remove(mode);
        }
        applied.add(change);
      }
    }

    if (!applied.isEmpty() && session.writer() != null) {
      session
          .writer()
          .enqueueRaw(
              new Message(
                  Map.of(),
                  serverName.get(),
                  Command.MODE,
                  "MODE",
                  List.of(session.nickname(), ModeEcho.format(applied))));
    }
  }

  private static UserMode findByFlag(char flag) {
    for (UserMode mode : UserMode.CORE_CATALOG) {
      if (mode.flag() == flag) {
        return mode;
      }
    }
    return null;
  }

  private static String formatModes(ClientSession session) {
    StringBuilder sb = new StringBuilder("+");
    session.userModes().stream().map(UserMode::flag).sorted().forEach(sb::append);
    return sb.toString();
  }
}
