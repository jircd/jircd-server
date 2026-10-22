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

/**
 * {@code MOTD} — the message of the day, as served at registration. A server name other than this
 * one is {@code 402}.
 */
public final class MotdCommandHandler implements CommandHandler {

  private final MotdService motd;
  private final Supplier<String> serverName;

  public MotdCommandHandler(MotdService motd, Supplier<String> serverName) {
    this.motd = motd;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    if (!message.params().isEmpty()
        && !LocalServerTarget.accepts(session, serverName, message.params().getFirst())) {
      return;
    }
    motd.send(session);
  }
}
