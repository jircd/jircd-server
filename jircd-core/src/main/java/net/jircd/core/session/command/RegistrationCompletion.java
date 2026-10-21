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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.SupportedFeatures;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.UserMode;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * Sends the Registration Completion Burst (FR-051) exactly once, the moment a session has both a
 * claimed nickname (FR-002) and a processed {@code USER} (FR-001) — whichever of the two commands
 * arrives second triggers it, since RFC 2812 registration requires both, not either alone.
 */
public final class RegistrationCompletion {

  private final Supplier<String> serverName;
  private final String serverVersion;
  private final Instant serverStartedAt;
  private final ExtensionRegistry extensionRegistry;

  public RegistrationCompletion(
      Supplier<String> serverName,
      String serverVersion,
      Instant serverStartedAt,
      ExtensionRegistry extensionRegistry) {
    this.serverName = serverName;
    this.serverVersion = serverVersion;
    this.serverStartedAt = serverStartedAt;
    this.extensionRegistry = extensionRegistry;
  }

  /** Sends the burst and marks the session REGISTERED if it is now ready and wasn't already. */
  public void tryComplete(ClientSession session) {
    if (session.lifecycle().isRegistered()
        || session.nickname() == null
        || !session.hasProcessedUser()
        || session.isNegotiatingCapabilities()) {
      return;
    }
    if (session.lifecycle().completeRegistration()) {
      sendBurst(session);
    }
  }

  private void sendBurst(ClientSession session) {
    String server = serverName.get();
    String nick = session.nickname();

    Replies.send(
        session, server, NumericReply.RPL_WELCOME, "Welcome to the Internet Relay Network " + nick);
    Replies.send(
        session,
        server,
        NumericReply.RPL_YOURHOST,
        "Your host is " + server + ", running version " + serverVersion);
    Replies.send(
        session, server, NumericReply.RPL_CREATED, "This server was created " + serverStartedAt);

    String userModeLetters =
        userModeLetters(extensionRegistry.recognizedUserModes(UserMode.CORE_CATALOG));
    String channelModeLetters =
        channelModeLetters(extensionRegistry.recognizedChannelModes(CoreChannelModes.ALL));
    sendRaw(
        session,
        server,
        NumericReply.RPL_MYINFO,
        List.of(server, serverVersion, userModeLetters, channelModeLetters));

    sendIsupport(session, server, extensionRegistry);

    Replies.send(session, server, NumericReply.ERR_NOMOTD, "MOTD File is missing");
  }

  /**
   * Renders and sends the current {@code ISUPPORT} burst — shared by the registration burst above
   * and {@code VERSION} (002-extended-irc-commands, research.md "VERSION + ISUPPORT reuse"), so the
   * two never drift into two independently-maintained renderings of the same wire format.
   */
  public static void sendIsupport(
      ClientSession session, String server, ExtensionRegistry extensionRegistry) {
    SupportedFeatures supportedFeatures = extensionRegistry.supportedFeatures();
    List<String> isupportParams = new java.util.ArrayList<>(supportedFeatures.tokens());
    isupportParams.add("are supported by this server");
    sendRaw(session, server, NumericReply.RPL_ISUPPORT, isupportParams);
  }

  private static void sendRaw(
      ClientSession session, String server, NumericReply numeric, List<String> trailingParams) {
    List<String> params = new java.util.ArrayList<>();
    params.add(Replies.target(session));
    params.addAll(trailingParams);
    if (session.writer() != null) {
      session.writer().enqueueRaw(new Message(Map.of(), server, null, numeric.wireCode(), params));
    }
  }

  private static String userModeLetters(java.util.Collection<UserMode> modes) {
    return modes.stream()
        .map(UserMode::flag)
        .sorted()
        .map(String::valueOf)
        .reduce("", String::concat);
  }

  private static String channelModeLetters(
      java.util.Collection<net.jircd.core.session.ChannelMode> modes) {
    return modes.stream()
        .map(net.jircd.core.session.ChannelMode::flag)
        .sorted()
        .map(String::valueOf)
        .reduce("", String::concat);
  }
}
