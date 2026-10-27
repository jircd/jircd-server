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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.CommandRegistrar;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;
import org.junit.jupiter.api.Test;

/**
 * The registrar over the real command table removes only the handler instance it installed, and its
 * ownership checks cover both command sources: core's table and the published providers'.
 */
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

    /** Two distinct handlers that consider themselves equal, as a value-like handler might. */
    private static final class EqualHandler implements CommandHandler {
        @Override
        public void handle(ClientSession session, net.jircd.protocol.Message message) {
            // Not invoked.
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualHandler;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @Test
    void unregisterComparesTheInstalledHandlerByIdentityNotEquality() {
        CommandRegistrar registrar = handler().commandRegistrar();
        CommandHandler provider = new EqualHandler();
        CommandHandler replacement = new EqualHandler();
        registrar.register(Command.OPER, provider);
        registrar.register(Command.OPER, replacement);
        registrar.unregister(Command.OPER, provider);
        assertThat(registrar.isRegistered(Command.OPER)).isTrue();
        registrar.unregister(Command.OPER, replacement);
        assertThat(registrar.isRegistered(Command.OPER)).isFalse();
    }

    /** Owns TAGMSG as a capability provider. */
    private static final class TagmsgOwner extends AbstractCapabilityExtension {
        private final List<String> handled;

        TagmsgOwner(List<String> handled) {
            super("owner");
            this.handled = handled;
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.command(Command.TAGMSG, (session, message) -> handled.add("provider"));
        }
    }

    @Test
    void aCommandAPublishedProviderOwnsIsRegisteredAndCannotBeClaimedByALaterServerExtension() {
        ExtensionRegistry extensions = new ExtensionRegistry();
        ConnectionHandler table =
                new ConnectionHandler(
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
        CommandRegistrar registrar = table.commandRegistrar();
        extensions.attachContext(
                new ServerContext(
                        null,
                        null,
                        extensions,
                        () -> "test.local",
                        null,
                        null,
                        registrar,
                        extensions.capabilityServices()));
        List<String> handled = new ArrayList<>();
        extensions.register(new TagmsgOwner(handled));
        extensions.enable("owner");

        assertThat(registrar.isRegistered(Command.TAGMSG)).isTrue();
        CommandHandler serverExtension = (session, message) -> handled.add("server extension");
        assertThatThrownBy(() -> registrar.register(Command.TAGMSG, serverExtension))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TAGMSG");

        ClientSession client = new ClientSession("c1", "host", RateLimitBucket.withDefaults());
        client.setNickname("client");
        assertThat(client.lifecycle().completeRegistration()).isTrue();
        // Lowercase on the wire: dispatch folds it to the same command the checks use.
        table.processLine(client, "tagmsg #c".getBytes(StandardCharsets.UTF_8));
        assertThat(handled).containsExactly("provider");

        extensions.disable("owner");
        assertThat(registrar.isRegistered(Command.TAGMSG)).isFalse();
        registrar.register(Command.TAGMSG, serverExtension);
        assertThat(registrar.isRegistered(Command.TAGMSG)).isTrue();
    }
}
