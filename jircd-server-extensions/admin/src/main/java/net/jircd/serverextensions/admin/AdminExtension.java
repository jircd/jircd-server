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
package net.jircd.serverextensions.admin;

import java.time.Clock;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.protocol.Command;

/**
 * Registers the eight {@code OPER}/{@code EXTENSION}/{@code REHASH}/{@code WHOHOST}/{@code
 * SAJOIN}/{@code SAMODE}/{@code KILL}/{@code WALLOPS} handlers with {@code jircd-core}'s command
 * dispatch when enabled (research.md "Administrator channel override", "OPER failed-attempt
 * lockout"). Handlers stay registered even after this extension is later disabled — there's no
 * unregister mechanism — {@link AdminPrivilege} is what makes {@code EXTENSION DISABLE admin}'s
 * self-lockout work instead (contracts/irc-protocol-commands.md "Self-lockout").
 */
public final class AdminExtension implements ServerExtension {

  public static final String ID = "admin";

  private final OperAttemptTracker attempts = new OperAttemptTracker(Clock.systemUTC(), 10_000);

  @Override
  public String id() {
    return ID;
  }

  @Override
  public void start(ServerContext context) {
    var registrar = context.commandRegistrar();
    var extensionRegistry = context.extensionRegistry();
    var serverName = context.serverName();

    registrar.register(
        Command.OPER,
        new OperCommandHandler(
            serverName, context.configurationReloader(), context.disconnectCleanup(), attempts));
    registrar.register(
        Command.EXTENSION, new ExtensionCommandHandler(extensionRegistry, serverName));
    registrar.register(
        Command.REHASH,
        new RehashCommandHandler(context.configurationReloader(), extensionRegistry, serverName));
    registrar.register(
        Command.WHOHOST,
        new WhohostCommandHandler(context.nicknameRegistry(), extensionRegistry, serverName));
    registrar.register(
        Command.SAJOIN,
        new SajoinCommandHandler(
            context.channelRegistry(),
            extensionRegistry,
            serverName,
            () -> context.configurationReloader().current().channelNameMaxLength()));
    registrar.register(
        Command.SAMODE,
        new SamodeCommandHandler(context.channelRegistry(), extensionRegistry, serverName));
    registrar.register(
        Command.KILL,
        new KillCommandHandler(
            context.nicknameRegistry(),
            extensionRegistry,
            serverName,
            context.disconnectCleanup()));
    registrar.register(
        Command.WALLOPS,
        new WallopsCommandHandler(context.nicknameRegistry(), extensionRegistry, serverName));
  }

  @Override
  public void stop() {}
}
