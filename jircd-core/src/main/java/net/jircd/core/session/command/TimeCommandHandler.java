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

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code TIME} — replies with the server's current local time (002-extended-irc-commands FR-002).
 */
public final class TimeCommandHandler implements CommandHandler {

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss zzz", Locale.ROOT);

  private final Supplier<String> serverName;

  public TimeCommandHandler(Supplier<String> serverName) {
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String server = serverName.get();
    Replies.sendFitted(
        session,
        server,
        NumericReply.RPL_TIME,
        List.of(server),
        FORMAT.format(ZonedDateTime.now()));
  }
}
