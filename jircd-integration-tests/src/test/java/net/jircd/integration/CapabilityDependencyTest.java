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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/**
 * #102 hard dependencies over real sockets. Invalid graphs and missing prerequisites change
 * nothing; a prerequisite's fault withdraws its dependents as FAILED in the same publication and
 * one {@code DEL}; only explicit enables bring them back, restoring no negotiation; a callback
 * captured before a re-enable that faults later is the provider's and withdraws it again; batch's
 * message-tags prerequisite stays soft; a provider's own synchronization keeps a captured callback
 * safe while stop releases what it uses.
 */
class CapabilityDependencyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long WAIT_SECONDS = 5;

    /** A provider requiring {@code requires} (if any); its member-status hook can fault. */
    private static class Node extends AbstractCapabilityExtension {
        private final String[] requires;
        volatile boolean faulting;
        final CountDownLatch stopped = new CountDownLatch(1);

        Node(String id, String... requires) {
            super(id);
            this.requires = requires;
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            if (requires.length > 0) registrar.requiresProviders(requires);
            registrar.onOutboundTags(
                    Set.of(id()),
                    16,
                    message -> {
                        if (faulting) throw new IllegalStateException("fault");
                        return Map.of(id(), "1");
                    });
        }

        @Override
        public void stop() {
            stopped.countDown();
        }
    }

    private static TestServer start() throws Exception {
        return TestServer.start(TestServer.adminEnabledYaml());
    }

    private static RawIrcClient admin(TestServer server) throws Exception {
        RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
        root.registerAndAwaitWelcome("root", "root");
        root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
        root.readUntil("381", TIMEOUT);
        return root;
    }

    private static RawIrcClient client(TestServer server, String nick, String caps)
            throws Exception {
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
        client.send("CAP LS 302");
        if (!caps.isEmpty()) client.send("CAP REQ :" + caps);
        client.send("CAP END");
        client.registerAndAwaitWelcome(nick, nick);
        return client;
    }

    private static void register(ExtensionRegistry registry, Extension... extensions) {
        for (Extension extension : extensions) registry.register(extension);
    }

    private static List<String> capLines(RawIrcClient client) throws Exception {
        return client.readThroughPong(TIMEOUT).stream().filter(l -> l.contains(" CAP ")).toList();
    }

    private static String ls(RawIrcClient client, String nick) throws Exception {
        client.send("CAP LS 302");
        return client.readUntil("CAP " + nick + " LS", TIMEOUT);
    }

    @Test
    void enablingADependentWithoutItsPrerequisiteIsRejectedAndOffersNothing() throws Exception {
        try (TestServer server = start();
                RawIrcClient root = admin(server);
                RawIrcClient alice = client(server, "alice", "")) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            register(registry, new Node("base"), new Node("dependent", "base"));
            String before = ls(alice, "alice");

            root.send("EXTENSION ENABLE dependent");
            assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("Failed to toggle dependent");

            assertThat(registry.stateOf("base")).isEqualTo(Extension.State.DISABLED);
            assertThat(registry.capabilitySnapshot().providerIds()).doesNotContain("dependent");
            assertThat(capLines(alice)).isEmpty();
            assertThat(ls(alice, "alice")).isEqualTo(before);
        }
    }

    /** Requires {@code right}; discovered, so the configuration file can name it. */
    public static final class Left extends AbstractCapabilityExtension {
        public Left() {
            super("left");
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.requiresProviders("right");
        }
    }

    /** Requires {@code left}. */
    public static final class Right extends AbstractCapabilityExtension {
        public Right() {
            super("right");
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.requiresProviders("left");
        }
    }

    /** Requires nothing. */
    public static final class Other extends AbstractCapabilityExtension {
        public Other() {
            super("other");
        }
    }

    @Test
    void aReloadWithACyclicGraphIsRejectedAndChangesNothing() throws Exception {
        String yaml = TestServer.adminEnabledYaml() + "capabilities:\n  other: enabled\n";
        try (TestServer server =
                        DiscoveredProbes.start(yaml, Left.class, Right.class, Other.class);
                RawIrcClient root = admin(server);
                RawIrcClient alice = client(server, "alice", "")) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            assertThat(registry.stateOf("other")).isEqualTo(Extension.State.ENABLED);
            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + TestServer.adminEnabledYaml()
                            + "capabilities:\n  left: enabled\n  right: enabled\n  other: disabled\n");

            root.send("REHASH");
            // Rejected for the cycle itself, not for an unknown id.
            assertThat(root.readUntil("NOTICE", TIMEOUT))
                    .contains("Rehash failed")
                    .contains("cyclic provider dependency");

            assertThat(registry.stateOf("other")).isEqualTo(Extension.State.ENABLED);
            assertThat(registry.stateOf("left")).isEqualTo(Extension.State.DISABLED);
            assertThat(registry.stateOf("right")).isEqualTo(Extension.State.DISABLED);
            assertThat(capLines(alice)).isEmpty();
        }
    }

    @Test
    void aPrerequisitesFaultWithdrawsItsDependentsAsFailedInOneDelAndOnlyExplicitEnablesRestore()
            throws Exception {
        try (TestServer server = start();
                RawIrcClient root = admin(server)) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            Node base = new Node("base");
            register(registry, base, new Node("dependent", "base"), new Node("grand", "dependent"));
            registry.enable("base");
            registry.enable("dependent");
            registry.enable("grand");
            try (RawIrcClient alice = client(server, "alice", "base dependent grand");
                    RawIrcClient bob = client(server, "bob", "")) {
                base.faulting = true;
                bob.send("PRIVMSG alice :hi");
                List<String> seen = Withdrawals.linesThrough(alice, "PRIVMSG alice");
                assertThat(seen.getLast()).doesNotContain("base=1").contains("dependent=1");
                // Dependents stop first, so base's stop is the last.
                Withdrawals.awaitFailed(registry, "base", base.stopped);

                assertThat(registry.stateOf("dependent")).isEqualTo(Extension.State.FAILED);
                assertThat(registry.stateOf("grand")).isEqualTo(Extension.State.FAILED);
                // The DEL may come before or after the message whose preparation faulted.
                seen.addAll(alice.readThroughPong(TIMEOUT));
                assertThat(seen.stream().filter(l -> l.contains(" CAP ")))
                        .containsExactly(":test.jircd.local CAP alice DEL :base dependent grand");
                alice.send("CAP LIST");
                assertThat(alice.readUntil("CAP alice LIST", TIMEOUT)).endsWith("LIST :");

                // Re-enabling the prerequisite does not bring its dependents back.
                base.faulting = false;
                root.send("EXTENSION ENABLE base");
                assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("base is now enabled");
                assertThat(capLines(alice)).containsExactly(":test.jircd.local CAP alice NEW base");
                assertThat(registry.stateOf("dependent")).isEqualTo(Extension.State.FAILED);

                root.send("EXTENSION ENABLE dependent");
                assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("dependent is now enabled");
                assertThat(capLines(alice))
                        .containsExactly(":test.jircd.local CAP alice NEW dependent");
                assertThat(registry.stateOf("grand")).isEqualTo(Extension.State.FAILED);
                alice.send("CAP LIST");
                assertThat(alice.readUntil("CAP alice LIST", TIMEOUT)).endsWith("LIST :");
            }
        }
    }

    /** A status hook whose first call once armed blocks until released, then faults if told to. */
    private static final class Holding extends AbstractCapabilityExtension {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean held = new AtomicBoolean();
        volatile boolean armed;
        volatile boolean faulting;
        // Stopped by the administrative disable, then by the fault's withdrawal.
        final CountDownLatch stopped = new CountDownLatch(2);

        Holding() {
            super("holding");
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.onMemberStatus(
                    limits -> 2,
                    member -> {
                        if (armed && held.compareAndSet(false, true)) {
                            entered.countDown();
                            try {
                                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                            } catch (InterruptedException _) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        if (faulting) throw new IllegalStateException("late");
                        return String.join("", member.heldStatuses());
                    });
        }

        @Override
        public void stop() {
            stopped.countDown();
        }
    }

    @Test
    void aLateFaultFromACallbackCapturedBeforeAReEnableWithdrawsTheReEnabledProvider()
            throws Exception {
        String yaml = TestServer.adminEnabledYaml() + "capabilities:\n  multi-prefix: disabled\n";
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient root = admin(server)) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            Holding holding = new Holding();
            register(registry, holding);
            registry.enable("holding");
            try (RawIrcClient alice = client(server, "alice", "holding")) {
                alice.send("JOIN #room");
                alice.readUntil("366", TIMEOUT);
                alice.send("MODE #room +v alice");
                alice.readThroughPong(TIMEOUT);

                holding.armed = true;
                alice.send("NAMES #room"); // captured now, held inside the hook
                assertThat(holding.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                root.send("EXTENSION DISABLE holding");
                assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("holding is now disabled");
                root.send("EXTENSION ENABLE holding");
                assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("holding is now enabled");

                holding.faulting = true;
                holding.release.countDown();
                // The late result falls back to the highest status; the fault is the provider's.
                List<String> seen = new ArrayList<>();
                String line;
                do {
                    line = alice.readUntil(" ", TIMEOUT);
                    seen.add(line);
                } while (!line.contains(" 353 "));
                assertThat(line).endsWith(" @alice");
                Withdrawals.awaitFailed(registry, "holding", holding.stopped);
                seen.addAll(alice.readThroughPong(TIMEOUT));
                assertThat(seen.stream().filter(l -> l.contains(" CAP ")))
                        .containsExactly(
                                ":test.jircd.local CAP alice DEL holding",
                                ":test.jircd.local CAP alice NEW holding",
                                ":test.jircd.local CAP alice DEL holding");
            }
        }
    }

    @Test
    void batchStaysOfferedAndNegotiatedWhenMessageTagsIsWithdrawnAndGroupsGoPlain()
            throws Exception {
        String yaml = "capabilities:\n  batch: enabled\n  message-tags: enabled\n";
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient alice = client(server, "alice", "batch message-tags")) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            registry.disable("message-tags");

            assertThat(registry.stateOf("batch")).isEqualTo(Extension.State.ENABLED);
            assertThat(capLines(alice))
                    .containsExactly(":test.jircd.local CAP alice DEL message-tags");
            assertThat(ls(alice, "alice")).contains("batch");
            server.application
                    .nicknameRegistry()
                    .lookup("alice")
                    .orElseThrow()
                    .writer()
                    .enqueueGroup(
                            "probe",
                            List.of(),
                            List.of(
                                    new Message(
                                            Map.of(),
                                            "bot!bot@host",
                                            Command.PRIVMSG,
                                            "PRIVMSG",
                                            List.of("#probe", "plain"))));
            assertThat(
                            alice.readThroughPong(TIMEOUT).stream()
                                    .filter(
                                            l ->
                                                    l.contains("PRIVMSG #probe")
                                                            || l.contains("BATCH")))
                    .containsExactly(":bot!bot@host PRIVMSG #probe plain");
        }
    }

    /**
     * Holds a buffer between start and stop and guards it with its own lock, as the SPI asks of a
     * provider whose captured callbacks can overlap stop.
     */
    private static final class Guarded extends AbstractCapabilityExtension {
        private final Object lock = new Object();
        private StringBuilder buffer;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Throwable> callbackFailure = new AtomicReference<>();

        Guarded() {
            super("guarded");
        }

        @Override
        public void start(ServerContext context) {
            synchronized (lock) {
                buffer = new StringBuilder("g");
            }
        }

        @Override
        public void stop() {
            synchronized (lock) {
                buffer = null;
            }
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.onOutboundTags(
                    Set.of("guarded"),
                    16,
                    message -> {
                        entered.countDown();
                        try {
                            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                        try {
                            synchronized (lock) {
                                return Map.of(
                                        "guarded", buffer == null ? "stopped" : buffer.toString());
                            }
                        } catch (RuntimeException e) {
                            callbackFailure.set(e);
                            throw e;
                        }
                    });
        }
    }

    @Test
    void aProvidersOwnLockKeepsACapturedCallbackSafeWhileStopReleasesItsResource()
            throws Exception {
        try (TestServer server = start();
                RawIrcClient root = admin(server)) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            Guarded guarded = new Guarded();
            register(registry, guarded);
            registry.enable("guarded");
            try (RawIrcClient alice = client(server, "alice", "guarded");
                    RawIrcClient bob = client(server, "bob", "")) {
                bob.send("PRIVMSG alice :held");
                assertThat(guarded.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

                // The disable does not wait for the callback that is still running.
                root.send("EXTENSION DISABLE guarded");
                assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("guarded is now disabled");
                guarded.release.countDown();

                assertThat(alice.readUntil("PRIVMSG alice", TIMEOUT))
                        .startsWith("@guarded=stopped ");
                assertThat(guarded.callbackFailure.get()).isNull();
                assertThat(registry.stateOf("guarded")).isEqualTo(Extension.State.DISABLED);
            }
        }
    }
}
