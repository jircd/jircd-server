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

import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code PASS} — the connection password a client may send before {@code NICK}/{@code USER}. The
 * server has no connection password, so it is accepted and ignored (never stored or logged); after
 * registration it is {@code 462}.
 */
public final class PassCommandHandler implements CommandHandler {

  private final Supplier<String> serverName;

  public PassCommandHandler(Supplier<String> serverName) {
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (message.params().isEmpty()) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_NEEDMOREPARAMS,
          "PASS",
          "Not enough parameters");
    } else if (session.lifecycle().isRegistered()) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_ALREADYREGISTRED, "You may not reregister");
    }
  }
}
