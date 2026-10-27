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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.CommandRegistrar;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;
import org.junit.jupiter.api.Test;

/**
 * Moving provider commands from one provider to another in a reload, through the real dispatch: a
 * command that exists before and after is never answered as unknown, and nothing that can fail
 * while the move is prepared leaves the new owner half visible.
 */
class CommandHandoverDispatchTest {

    private static final int FLIPS = 300;

    /** An escape no reload catches. */
    private static final class Escape extends Error {
        Escape() {
            super("escaped");
        }
    }

    private final List<String> handledBy = Collections.synchronizedList(new ArrayList<>());
    private final ExtensionRegistry registry = new ExtensionRegistry();
    private final ConnectionHandler connections =
            new ConnectionHandler(
                    registry,
                    new DisconnectCleanup(
                            new NicknameRegistry(),
                            new ChannelRegistry(),
                            new WhowasHistory(() -> 100),
                            registry),
                    () -> "test.local",
                    new ConnectionHandler.Settings(
                            () -> new ServerConfiguration.RateLimit(100, 100),
                            () -> 60,
                            () -> 524288,
                            () -> new ServerConfiguration.ConnectionLimits(10, 10)),
                    socket -> {},
                    task -> Thread.ofVirtual().unstarted(task));

    /** Core's command table, failing the n-th registration once armed. */
    private final AtomicInteger registrations = new AtomicInteger();

    private volatile Supplier<Throwable> failSecondRegistration;

    /** Owns TAGMSG and WALLOPS; its own staging can fail at the second command. */
    private final class Owner extends AbstractCapabilityExtension {
        volatile Supplier<Throwable> failSecondCommand;

        Owner(String id) {
            super(id);
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.command(Command.TAGMSG, (session, message) -> handledBy.add(id()));
            Supplier<Throwable> failure = failSecondCommand;
            if (failure != null) sneakyThrow(failure.get());
            registrar.command(Command.WALLOPS, (session, message) -> handledBy.add(id()));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T {
        throw (T) failure;
    }

    private Owner a;
    private Owner b;

    private void setUp() {
        CommandRegistrar table = connections.commandRegistrar();
        CommandRegistrar failing =
                new CommandRegistrar() {
                    @Override
                    public void register(Command command, CommandHandler handler) {
                        Supplier<Throwable> failure = failSecondRegistration;
                        if (failure != null && registrations.incrementAndGet() == 2) {
                            sneakyThrow(failure.get());
                        }
                        table.register(command, handler);
                    }

                    @Override
                    public boolean isRegistered(Command command) {
                        return table.isRegistered(command);
                    }

                    @Override
                    public void unregister(Command command, CommandHandler installed) {
                        table.unregister(command, installed);
                    }
                };
        registry.attachContext(
                new ServerContext(
                        null,
                        null,
                        registry,
                        () -> "test.local",
                        null,
                        null,
                        failing,
                        registry.capabilityServices()));
        a = new Owner("a");
        b = new Owner("b");
        registry.register(a);
        registry.register(b);
        registry.enable("a");
    }

    private static ExtensionRegistry.DesiredState handOver(boolean toB) {
        return new ExtensionRegistry.DesiredState(
                Map.of("a", !toB, "b", toB), Map.of(), 9, 50, 390, 6);
    }

    private ClientSession client() {
        ClientSession client =
                new ClientSession(
                        "c1",
                        "host.example",
                        new RateLimitBucket(1_000_000, 1_000_000, Clock.systemUTC()));
        client.setNickname("client");
        assertThat(client.lifecycle().completeRegistration()).isTrue();
        return client;
    }

    /** Dispatches each line through the real path and returns what the client was sent. */
    private String dispatch(ClientSession client, String... lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(client, out, TagRenderer.NONE)) {
            client.attachWriter(writer);
            for (String line : lines) {
                connections.processLine(client, line.getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void dispatchDuringRepeatedHandOversNeverAnswersUnknownCommand() throws Exception {
        setUp();
        ClientSession client = client();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicInteger swaps = new AtomicInteger();
        int dispatched = 0;
        try (SessionWriter writer = new SessionWriter(client, out, TagRenderer.NONE)) {
            client.attachWriter(writer);
            // A worker failure is rethrown by get() below; a hung worker cannot hang the loop.
            CompletableFuture<Void> flips =
                    CompletableFuture.runAsync(
                            () -> {
                                for (int i = 0; i < FLIPS; i++) {
                                    registry.applyDesiredState(handOver(i % 2 == 0), () -> {});
                                    swaps.incrementAndGet();
                                }
                            });
            byte[] line = "TAGMSG #c".getBytes(StandardCharsets.UTF_8);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!flips.isDone() && System.nanoTime() < deadline) {
                connections.processLine(client, line);
                dispatched++;
            }
            flips.get(30, TimeUnit.SECONDS);
        }
        assertThat(swaps).hasValue(FLIPS);
        assertThat(dispatched).isPositive();
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain(" 421 ");
        assertThat(handledBy).hasSize(dispatched);
    }

    @Test
    void aRuntimeExceptionFromCoresTableOnTheSecondMovedCommandCannotHalfEnableTheNewOwner() {
        setUp();
        failSecondRegistration = () -> new IllegalStateException("table refused");

        assertThat(registry.applyDesiredState(handOver(true), () -> {})).isEmpty();

        assertThat(registry.stateOf("b")).isEqualTo(Extension.State.ENABLED);
        assertThat(dispatch(client(), "TAGMSG #c", "WALLOPS :hi")).doesNotContain(" 421 ");
        assertThat(handledBy).containsExactly("b", "b");
    }

    @Test
    void anErrorFromCoresTableOnTheSecondMovedCommandCannotHalfEnableTheNewOwner() {
        setUp();
        failSecondRegistration = Escape::new;

        assertThat(registry.applyDesiredState(handOver(true), () -> {})).isEmpty();

        assertThat(registry.stateOf("b")).isEqualTo(Extension.State.ENABLED);
        assertThat(dispatch(client(), "TAGMSG #c", "WALLOPS :hi")).doesNotContain(" 421 ");
        assertThat(handledBy).containsExactly("b", "b");
    }

    @Test
    void aRuntimeExceptionStagingTheSecondCommandFailsTheNewOwnerWithNothingOfItVisible() {
        setUp();
        b.failSecondCommand = () -> new IllegalStateException("cannot stage WALLOPS");

        Map<String, String> failures = registry.applyDesiredState(handOver(true), () -> {});

        assertThat(failures).containsOnlyKeys("b");
        assertThat(registry.stateOf("b")).isEqualTo(Extension.State.FAILED);
        assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
        // a is withdrawn for its own reason (desired disabled); b never became visible.
        assertThat(dispatch(client(), "TAGMSG #c", "WALLOPS :hi")).contains(" 421 ");
        assertThat(handledBy).isEmpty();
    }

    @Test
    void anErrorStagingTheSecondCommandChangesNothingAndTheOldOwnerKeepsItsCommands() {
        setUp();
        b.failSecondCommand = Escape::new;

        ExtensionRegistry.DesiredState desired = handOver(true);
        assertThatThrownBy(() -> registry.applyDesiredState(desired, () -> {}))
                .isInstanceOf(Escape.class);

        assertThat(registry.stateOf("a")).isEqualTo(Extension.State.ENABLED);
        assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("a");
        assertThat(dispatch(client(), "TAGMSG #c", "WALLOPS :hi")).doesNotContain(" 421 ");
        assertThat(handledBy).containsExactly("a", "a");
    }
}
