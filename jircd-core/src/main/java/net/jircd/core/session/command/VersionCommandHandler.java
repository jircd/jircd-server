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
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code VERSION} — replies with the server's name/version, then a fresh {@code ISUPPORT} burst
 * (002-extended-irc-commands Clarifications, 2026-08-19), reusing the exact rendering the
 * registration completion burst already uses rather than a second, independent implementation
 * (research.md "VERSION + ISUPPORT reuse").
 */
public final class VersionCommandHandler implements CommandHandler {

  private final Supplier<String> serverName;
  private final String serverVersion;
  private final ExtensionRegistry extensionRegistry;

  public VersionCommandHandler(
      Supplier<String> serverName, String serverVersion, ExtensionRegistry extensionRegistry) {
    this.serverName = serverName;
    this.serverVersion = serverVersion;
    this.extensionRegistry = extensionRegistry;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    String server = serverName.get();
    Replies.sendFitted(
        session,
        server,
        NumericReply.RPL_VERSION,
        List.of(serverVersion, server),
        "jircd IRC server");
    RegistrationCompletion.sendIsupport(session, server, extensionRegistry);
  }
}
