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

import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;

/** {@code MOTD} — the message of the day, as served at registration. */
public final class MotdCommandHandler implements CommandHandler {

  private final MotdService motd;

  public MotdCommandHandler(MotdService motd) {
    this.motd = motd;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    motd.send(session);
  }
}
