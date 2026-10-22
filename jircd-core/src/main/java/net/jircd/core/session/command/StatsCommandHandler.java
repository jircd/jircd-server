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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code STATS} — {@code u} reports the uptime ({@code 242}); every query, whatever its letter,
 * ends with {@code 219}. The other letters report connection, command and configuration details
 * this server does not expose, so they only get the end marker. Uptime is measured with an
 * injectable {@link Clock} from the instant the server started.
 */
public final class StatsCommandHandler implements CommandHandler {

  private final Supplier<String> serverName;
  private final Instant startedAt;
  private final Clock clock;

  public StatsCommandHandler(Supplier<String> serverName, Instant startedAt, Clock clock) {
    this.serverName = serverName;
    this.startedAt = startedAt;
    this.clock = clock;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String server = serverName.get();
    if (message.params().isEmpty() || message.params().getFirst().isEmpty()) {
      Replies.sendFitted(
          session,
          server,
          NumericReply.ERR_NEEDMOREPARAMS,
          List.of("STATS"),
          "Not enough parameters");
      return;
    }
    if (message.params().size() > 1
        && !LocalServerTarget.accepts(session, serverName, message.params().get(1))) {
      return;
    }
    String letter = new String(Character.toChars(message.params().getFirst().codePointAt(0)));
    if (letter.equals("u")) {
      Duration up = Duration.between(startedAt, clock.instant());
      Replies.sendFitted(
          session,
          server,
          NumericReply.RPL_STATSUPTIME,
          List.of(),
          String.format(
              Locale.ROOT,
              "Server Up %d days %d:%02d:%02d",
              up.toDays(),
              up.toHoursPart(),
              up.toMinutesPart(),
              up.toSecondsPart()));
    }
    Replies.sendFitted(
        session, server, NumericReply.RPL_ENDOFSTATS, List.of(letter), "End of /STATS report");
  }
}
