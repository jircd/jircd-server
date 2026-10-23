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
package net.jircd.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.CommandRegistrar;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;
import org.junit.jupiter.api.Test;

/** The registrar over the real command table removes only the handler instance it installed. */
class ConnectionHandlerCommandTableTest {

  private static ConnectionHandler handler() {
    ExtensionRegistry extensions = new ExtensionRegistry();
    return new ConnectionHandler(
        extensions,
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            extensions),
        () -> "test.local",
        new ConnectionHandler.Settings(
            () -> new ServerConfiguration.RateLimit(100, 100),
            () -> 60,
            () -> 524288,
            () -> new ServerConfiguration.ConnectionLimits(10, 10)),
        socket -> {},
        task -> Thread.ofVirtual().unstarted(task));
  }

  @Test
  void unregisterRemovesOnlyTheInstalledHandlerNotALaterReplacement() {
    ConnectionHandler table = handler();
    CommandRegistrar registrar = table.commandRegistrar();
    CommandHandler provider = (session, message) -> {};
    CommandHandler admin = (session, message) -> {};
    registrar.register(Command.OPER, provider);
    assertThat(registrar.isRegistered(Command.OPER)).isTrue();
    registrar.register(Command.OPER, admin); // admin enabled later takes OPER over
    registrar.unregister(Command.OPER, provider); // the provider is disabled
    assertThat(registrar.isRegistered(Command.OPER)).isTrue();
    registrar.unregister(Command.OPER, admin);
    assertThat(registrar.isRegistered(Command.OPER)).isFalse();
  }
}
