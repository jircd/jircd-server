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
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code ISON} — which of the given nicknames are online (RFC 2812 §4.9). Lookup is case-folded and
 * the reply carries each nickname as its owner presents it; nickname holders that have not finished
 * registering are not online. The list normally fits one {@code 303}; an unusually long one is
 * split across several by the shared list sender.
 */
public final class IsonCommandHandler implements CommandHandler {

  private final NicknameRegistry nicknameRegistry;
  private final Supplier<String> serverName;

  public IsonCommandHandler(NicknameRegistry nicknameRegistry, Supplier<String> serverName) {
    this.nicknameRegistry = nicknameRegistry;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty() || message.params().getFirst().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "ISON",
          "Not enough parameters");
      return;
    }
    List<String> online = new ArrayList<>();
    for (String param : message.params()) {
      for (String nickname : param.split(" ")) {
        if (nickname.isEmpty()) {
          continue;
        }
        nicknameRegistry
            .lookup(nickname)
            .filter(target -> target.lifecycle().isRegistered())
            .map(ClientSession::nickname)
            .filter(presented -> !online.contains(presented))
            .ifPresent(online::add);
      }
    }
    Replies.sendListed(session, serverName.get(), NumericReply.RPL_ISON, List.of(), online);
  }
}
