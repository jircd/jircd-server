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
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.UserMode;
import net.jircd.core.session.UserStatistics;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code LUSERS} — {@code 251}, {@code 252}, {@code 253}, {@code 254}, {@code 255}, {@code 265} and
 * {@code 266}, in the order modern.ircdocs.horse lists them (002-extended-irc-commands FR-003;
 * operator count and {@code RPL_LUSERME} by 006-complete-core-protocol FR-011/FR-012). Users are
 * the {@code REGISTERED} sessions only; {@code RPL_LUSERUNKNOWN} counts the connections that are
 * not registered yet and is omitted when there are none. This is a single server, so the local and
 * global figures are the same.
 */
public final class LusersCommandHandler implements CommandHandler {

  private final NicknameRegistry nicknameRegistry;
  private final ChannelRegistry channelRegistry;
  private final UserStatistics userStatistics;
  private final IntSupplier unregisteredConnections;
  private final Supplier<String> serverName;

  public LusersCommandHandler(
      NicknameRegistry nicknameRegistry,
      ChannelRegistry channelRegistry,
      UserStatistics userStatistics,
      IntSupplier unregisteredConnections,
      Supplier<String> serverName) {
    this.nicknameRegistry = nicknameRegistry;
    this.channelRegistry = channelRegistry;
    this.userStatistics = userStatistics;
    this.unregisteredConnections = unregisteredConnections;
    this.serverName = serverName;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String server = serverName.get();
    List<ClientSession> users =
        nicknameRegistry.all().stream().filter(s -> s.lifecycle().isRegistered()).toList();
    int clientCount = users.size();
    long invisibleCount =
        users.stream().filter(s -> s.userModes().contains(UserMode.INVISIBLE)).count();
    long operatorCount =
        users.stream().filter(s -> s.userModes().contains(UserMode.OPERATOR)).count();
    int channelCount = channelRegistry.all().size();
    int unknownCount = unregisteredConnections.getAsInt();
    String current = String.valueOf(clientCount);
    String max = String.valueOf(Math.max(userStatistics.peak(), clientCount));
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSERCLIENT,
        "There are " + clientCount + " users and " + invisibleCount + " invisible on 1 servers");
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSEROP,
        String.valueOf(operatorCount),
        "operator(s) online");
    if (unknownCount > 0) {
      Replies.send(
          session,
          server,
          NumericReply.RPL_LUSERUNKNOWN,
          String.valueOf(unknownCount),
          "unknown connection(s)");
    }
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSERCHANNELS,
        String.valueOf(channelCount),
        "channels formed");
    Replies.send(
        session,
        server,
        NumericReply.RPL_LUSERME,
        "I have " + clientCount + " clients and 1 servers");
    Replies.send(
        session,
        server,
        NumericReply.RPL_LOCALUSERS,
        current,
        max,
        "Current local users " + current + ", max " + max);
    Replies.send(
        session,
        server,
        NumericReply.RPL_GLOBALUSERS,
        current,
        max,
        "Current global users " + current + ", max " + max);
  }
}
