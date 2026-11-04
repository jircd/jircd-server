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
package net.jircd.server;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import net.jircd.core.capability.CapabilityNegotiator;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ConnectionHandler;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.PlaintextListener;
import net.jircd.core.session.TlsListener;
import net.jircd.core.session.WhowasHistory;
import net.jircd.core.session.command.AwayCommandHandler;
import net.jircd.core.session.command.CapCommandHandler;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.core.session.command.ListCommandHandler;
import net.jircd.core.session.command.LusersCommandHandler;
import net.jircd.core.session.command.MessageCommandHandler;
import net.jircd.core.session.command.NamesCommandHandler;
import net.jircd.core.session.command.NickCommandHandler;
import net.jircd.core.session.command.PartCommandHandler;
import net.jircd.core.session.command.PingPongCommandHandler;
import net.jircd.core.session.command.QuitCommandHandler;
import net.jircd.core.session.command.TimeCommandHandler;
import net.jircd.core.session.command.TopicCommandHandler;
import net.jircd.core.session.command.UserCommandHandler;
import net.jircd.core.session.command.UserModeCommandHandler;
import net.jircd.core.session.command.VersionCommandHandler;
import net.jircd.core.session.command.WhoCommandHandler;
import net.jircd.core.session.command.WhoisCommandHandler;
import net.jircd.core.session.command.WhowasCommandHandler;
import net.jircd.protocol.Command;
import net.jircd.server.logging.LoggingSettingsApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The application entry point (composition root): loads {@link ServerConfiguration}, starts {@link
 * ExtensionRegistry}, starts the plaintext and TLS listeners, refuses to start with a specific
 * error on invalid configuration (FR-012).
 */
public final class JircdServerApplication {

    private static final Logger LOG = LoggerFactory.getLogger(JircdServerApplication.class);

    private final NicknameRegistry nicknameRegistry = new NicknameRegistry();
    private final net.jircd.core.session.UserStatistics userStatistics =
            new net.jircd.core.session.UserStatistics();
    private final ChannelRegistry channelRegistry = new ChannelRegistry();
    private final ExtensionRegistry extensionRegistry = new ExtensionRegistry();
    private final WhowasHistory whowasHistory;
    private final DisconnectCleanup disconnectCleanup;
    private final ConnectionHandler connectionHandler;
    private final Instant startedAt = Instant.now();

    private volatile ServerConfiguration configuration;
    private volatile String serverName;
    private final String serverVersion;
    private final ConfigurationReloader reloader;

    private PlaintextListener plaintextListener;
    private TlsListener tlsListener;
    private final List<Closeable> ownedListeners = new ArrayList<>();
    private final Consumer<ServerConfiguration.Logging> loggingSink;
    private final AtomicReference<Consumer<StartPhase>> startPhaseHook =
            new AtomicReference<>(phase -> {});
    private final Object loggingLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    // Set by shutdown() before it looks at anything else; read by stop(), which must not close
    // sockets under a graceful drain. Counted down when the drain has ended.
    private volatile long shutdownDeadlineNanos;
    private final CountDownLatch drainFinished = new CountDownLatch(1);

    /** Who closes the connections: decided once, atomically, by whichever gets there first. */
    private enum ClosureOwner {
        STOP,
        SHUTDOWN
    }

    private final AtomicReference<ClosureOwner> closureOwner = new AtomicReference<>();
    private final AtomicReference<Runnable> afterStopClaimsClosure =
            new AtomicReference<>(() -> {});
    private final AtomicReference<Runnable> beforeStopClosesConnections =
            new AtomicReference<>(() -> {});

    /**
     * Held by {@link #start()} and {@link #stop()} for their whole run. A lock rather than the
     * monitor, so {@link #shutdown} can give up waiting for it when its budget runs out.
     */
    private final ReentrantLock lifecycle = new ReentrantLock();

    public JircdServerApplication(Path configPath) throws ConfigurationException, IOException {
        this(configPath, socket -> {});
    }

    /**
     * Test seam for accepted-socket setup, such as deterministic backpressure. This is not a server
     * configuration setting; socket ownership stays with the application.
     */
    public JircdServerApplication(Path configPath, Consumer<Socket> acceptedSocketCustomizer)
            throws ConfigurationException, IOException {
        this(configPath, acceptedSocketCustomizer, ConnectionHandler.DEFAULT_REJECTION_DEADLINE);
    }

    /** Test-only deadline override, kept separate from the administrator configuration. */
    JircdServerApplication(
            Path configPath, Consumer<Socket> acceptedSocketCustomizer, Duration rejectionDeadline)
            throws ConfigurationException, IOException {
        this(
                configPath,
                acceptedSocketCustomizer,
                rejectionDeadline,
                JircdServerApplication::applyLogging);
    }

    /** Test seam: where the {@code logging} section goes, to observe or delay its application. */
    JircdServerApplication(
            Path configPath,
            Consumer<Socket> acceptedSocketCustomizer,
            Duration rejectionDeadline,
            Consumer<ServerConfiguration.Logging> loggingSink)
            throws ConfigurationException, IOException {
        this.loggingSink = loggingSink;
        extensionRegistry.discover(Thread.currentThread().getContextClassLoader());

        Set<String> knownCapabilityIds =
                extensionRegistry.all().stream()
                        .filter(net.jircd.core.extension.CapabilityExtension.class::isInstance)
                        .map(net.jircd.core.extension.Extension::id)
                        .collect(java.util.stream.Collectors.toSet());
        Set<String> knownServerExtensionIds =
                extensionRegistry.all().stream()
                        .filter(e -> !(e instanceof net.jircd.core.extension.CapabilityExtension))
                        .map(net.jircd.core.extension.Extension::id)
                        .collect(java.util.stream.Collectors.toSet());
        ConfigurationLoader loader =
                new ConfigurationLoader(
                        knownCapabilityIds,
                        knownServerExtensionIds,
                        extensionRegistry::discoveredReplyBudgetCatalog);

        this.configuration = loader.load(configPath);
        loggingSink.accept(configuration.logging());

        this.serverVersion = resolveServerVersion();
        this.serverName = configuration.serverName();
        extensionRegistry.updateConfiguredLengths(
                configuration.nicknameMaxLength(),
                configuration.channelNameMaxLength(),
                configuration.topicMaxLength(),
                configuration.maxModesPerCommand());

        this.reloader =
                new ConfigurationReloader(configPath, loader, extensionRegistry, configuration);
        reloader.addReloadListener(applied -> applyCurrentLogging());
        this.whowasHistory = new WhowasHistory(() -> reloader.current().whowasHistorySize());
        this.disconnectCleanup =
                new DisconnectCleanup(
                        nicknameRegistry,
                        channelRegistry,
                        whowasHistory,
                        extensionRegistry,
                        userStatistics);

        this.connectionHandler =
                new ConnectionHandler(
                        extensionRegistry,
                        disconnectCleanup,
                        () -> serverName,
                        new ConnectionHandler.Settings(
                                () -> reloader.current().rateLimit(),
                                () -> reloader.current().keepAliveFrequencySeconds(),
                                () -> reloader.current().sendqLimitBytes(),
                                () -> reloader.current().connectionLimits(),
                                rejectionDeadline,
                                () ->
                                        Duration.ofSeconds(
                                                reloader.current().registrationTimeoutSeconds())),
                        acceptedSocketCustomizer);
        registerStory1Handlers();

        extensionRegistry.attachContext(
                new net.jircd.core.extension.ServerContext(
                        nicknameRegistry,
                        channelRegistry,
                        extensionRegistry,
                        () -> serverName,
                        reloader,
                        disconnectCleanup,
                        connectionHandler.commandRegistrar(),
                        extensionRegistry.capabilityServices()));

        enableConfiguredExtensions();
    }

    /**
     * Applies the logging of the configuration that is current <em>now</em>. Reload listeners run
     * outside the reloader's lock, so two overlapping reloads can reach this out of order; reading
     * {@code current()} inside this lock means whichever applies last still applies the most
     * recently published logging, instead of an older one it was handed.
     */
    private void applyCurrentLogging() {
        synchronized (loggingLock) {
            loggingSink.accept(reloader.current().logging());
        }
    }

    /** Applies the {@code logging} section to this process's logback (spec 016 FR-006). */
    private static void applyLogging(ServerConfiguration.Logging logging) {
        LoggingSettingsApplier applier = LoggingSettingsApplier.forThisProcess();
        if (applier != null) {
            applier.apply(logging);
        }
    }

    private void enableConfiguredExtensions() {
        configuration
                .capabilityStates()
                .forEach(
                        (id, enabled) -> {
                            if (enabled) {
                                extensionRegistry.enable(id);
                            }
                        });
        configuration
                .serverExtensionStates()
                .forEach(
                        (id, enabled) -> {
                            if (enabled) {
                                extensionRegistry.enable(id);
                            }
                        });
    }

    private void registerStory1Handlers() {
        var motd =
                new net.jircd.core.session.command.MotdService(
                        () -> serverName, () -> reloader.current().motdPath());
        var registrationCompletion =
                new net.jircd.core.session.command.RegistrationCompletion(
                        () -> serverName,
                        serverVersion,
                        startedAt,
                        extensionRegistry,
                        userStatistics,
                        motd);
        connectionHandler.registerHandler(
                Command.PASS,
                new net.jircd.core.session.command.PassCommandHandler(() -> serverName));
        connectionHandler.registerHandler(
                Command.NICK,
                new NickCommandHandler(
                        nicknameRegistry,
                        () -> serverName,
                        () -> reloader.current().nicknameMaxLength(),
                        registrationCompletion,
                        extensionRegistry));
        connectionHandler.registerHandler(
                Command.USER, new UserCommandHandler(() -> serverName, registrationCompletion));
        connectionHandler.registerHandler(
                Command.JOIN,
                new JoinCommandHandler(
                        channelRegistry,
                        extensionRegistry,
                        () -> serverName,
                        () -> reloader.current().channelNameMaxLength()));
        connectionHandler.registerHandler(
                Command.PART,
                new PartCommandHandler(channelRegistry, extensionRegistry, () -> serverName));
        connectionHandler.registerHandler(
                Command.PRIVMSG,
                new MessageCommandHandler(
                        channelRegistry,
                        nicknameRegistry,
                        extensionRegistry,
                        () -> serverName,
                        false));
        connectionHandler.registerHandler(
                Command.NOTICE,
                new MessageCommandHandler(
                        channelRegistry,
                        nicknameRegistry,
                        extensionRegistry,
                        () -> serverName,
                        true));
        connectionHandler.registerHandler(Command.QUIT, new QuitCommandHandler(disconnectCleanup));
        connectionHandler.registerHandler(
                Command.TOPIC,
                new TopicCommandHandler(
                        channelRegistry,
                        extensionRegistry,
                        () -> serverName,
                        () -> reloader.current().topicMaxLength()));
        connectionHandler.registerHandler(
                Command.NAMES,
                new NamesCommandHandler(channelRegistry, extensionRegistry, () -> serverName));
        connectionHandler.registerHandler(
                Command.LIST,
                new ListCommandHandler(channelRegistry, extensionRegistry, () -> serverName));
        connectionHandler.registerHandler(
                Command.WHOIS,
                new WhoisCommandHandler(nicknameRegistry, extensionRegistry, () -> serverName));
        connectionHandler.registerHandler(
                Command.WHO,
                new WhoCommandHandler(
                        channelRegistry,
                        nicknameRegistry,
                        extensionRegistry,
                        () -> serverName,
                        () -> reloader.current().whoMaskEnabled()));
        connectionHandler.registerHandler(
                Command.PING, PingPongCommandHandler.ping(() -> serverName));
        connectionHandler.registerHandler(Command.PONG, PingPongCommandHandler.pong());
        connectionHandler.registerHandler(
                Command.CAP,
                new CapCommandHandler(
                        new CapabilityNegotiator(extensionRegistry),
                        () -> serverName,
                        registrationCompletion));
        registerModerationHandlers();
        registerExtendedCommandHandlers(motd);
    }

    /**
     * VERSION/TIME/LUSERS/AWAY/WHOWAS (002-extended-irc-commands), plus MOTD, ISON, ADMIN, STATS
     * and HELP/HELPOP.
     */
    private void registerExtendedCommandHandlers(net.jircd.core.session.command.MotdService motd) {
        connectionHandler.registerHandler(
                Command.MOTD,
                new net.jircd.core.session.command.MotdCommandHandler(motd, () -> serverName));
        connectionHandler.registerHandler(
                Command.ISON,
                new net.jircd.core.session.command.IsonCommandHandler(
                        nicknameRegistry, () -> serverName));
        connectionHandler.registerHandler(
                Command.ADMIN,
                new net.jircd.core.session.command.AdminCommandHandler(
                        () -> serverName, () -> reloader.current().adminContact()));
        connectionHandler.registerHandler(
                Command.STATS,
                new net.jircd.core.session.command.StatsCommandHandler(
                        () -> serverName, startedAt, java.time.Clock.systemUTC()));
        var help = new net.jircd.core.session.command.HelpCommandHandler(() -> serverName);
        connectionHandler.registerHandler(Command.HELP, help);
        connectionHandler.registerHandler(Command.HELPOP, help);
        connectionHandler.registerHandler(
                Command.VERSION,
                new VersionCommandHandler(() -> serverName, serverVersion, extensionRegistry));
        connectionHandler.registerHandler(Command.TIME, new TimeCommandHandler(() -> serverName));
        connectionHandler.registerHandler(
                Command.USERHOST,
                new net.jircd.core.session.command.UserhostCommandHandler(
                        nicknameRegistry, extensionRegistry, () -> serverName));
        connectionHandler.registerHandler(
                Command.INFO,
                new net.jircd.core.session.command.InfoCommandHandler(
                        () -> serverName, serverVersion));
        connectionHandler.registerHandler(
                Command.LUSERS,
                new LusersCommandHandler(
                        nicknameRegistry,
                        channelRegistry,
                        userStatistics,
                        connectionHandler::unregisteredConnectionCount,
                        () -> serverName));
        connectionHandler.registerHandler(
                Command.AWAY, new AwayCommandHandler(() -> serverName, extensionRegistry));
        connectionHandler.registerHandler(
                Command.WHOWAS, new WhowasCommandHandler(whowasHistory, () -> serverName));
    }

    /** {@code KICK}/channel-{@code MODE}/{@code INVITE} (Story 5, FR-013/FR-014/FR-064/FR-065). */
    private void registerModerationHandlers() {
        connectionHandler.registerHandler(
                Command.KICK,
                new net.jircd.core.session.command.KickCommandHandler(
                        channelRegistry, extensionRegistry, () -> serverName));
        connectionHandler.registerHandler(
                Command.INVITE,
                new net.jircd.core.session.command.InviteCommandHandler(
                        channelRegistry,
                        nicknameRegistry,
                        extensionRegistry,
                        () -> serverName,
                        () -> reloader.current().channelNameMaxLength()));

        var userModeHandler = new UserModeCommandHandler(() -> serverName);
        var channelModeHandler =
                new net.jircd.core.session.command.ModeCommandHandler(
                        channelRegistry,
                        nicknameRegistry,
                        extensionRegistry,
                        () -> serverName,
                        () -> reloader.current().maxModesPerCommand());
        connectionHandler.registerHandler(
                Command.MODE,
                (session, message) -> {
                    if (!message.params().isEmpty()
                            && message.params().getFirst().startsWith("#")) {
                        channelModeHandler.handle(session, message);
                    } else {
                        userModeHandler.handle(session, message);
                    }
                });
    }

    public void start() throws IOException, GeneralSecurityException {
        lifecycle.lock();
        try {
            startLocked();
        } finally {
            lifecycle.unlock();
        }
    }

    private void startLocked() throws IOException, GeneralSecurityException {
        if (shuttingDown.get()) {
            // A TERM or INT arrived while this was waiting its turn; do not bring listeners up.
            return;
        }
        startPhaseHook.get().accept(StartPhase.CHECKED_FOR_SHUTDOWN);
        if (!connectionHandler.startAccepting()) {
            // Shutdown began after the check above and closed admission for good.
            return;
        }
        startPhaseHook.get().accept(StartPhase.ACCEPTING);
        // No TLS entry in the zero-config default (004-fix-tls-certificate, research.md "The
        // zero-config default listener list") — a TLS listener with no certificate configured now
        // refuses to start, so defaulting to one here would break the simplest possible quickstart.
        List<ServerConfiguration.Listener> listeners =
                configuration.listeners().isEmpty()
                        ? List.of(new ServerConfiguration.Listener(6667, false))
                        : configuration.listeners();
        int previousListenerCount = ownedListeners.size();
        // These aliases remain owned by ownedListeners; rollback must not close them.
        @SuppressWarnings("PMD.CloseResource")
        PlaintextListener previousPlaintextListener = plaintextListener;
        @SuppressWarnings("PMD.CloseResource")
        TlsListener previousTlsListener = tlsListener;
        try {
            startListeners(listeners);
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            rollbackStart(failure, previousListenerCount);
            plaintextListener = previousPlaintextListener;
            tlsListener = previousTlsListener;
            throw failure;
        }
        if (shuttingDown.get()) {
            // Shutdown began while the listeners were coming up. It cannot close them while this
            // holds the lifecycle, so close the ones this start opened before returning.
            closeListenersOpenedByThisStart(previousListenerCount);
            plaintextListener = previousPlaintextListener;
            tlsListener = previousTlsListener;
        }
    }

    private void closeListenersOpenedByThisStart(int previousListenerCount) {
        try {
            closeListeners(
                    ownedListeners.subList(previousListenerCount, ownedListeners.size()),
                    System.nanoTime() + Duration.ofSeconds(3).toNanos());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Closing listeners opened during shutdown failed: {}", e.getMessage());
        }
    }

    /** Points inside {@link #start()} a test can pause at (see {@link #onStartPhaseForTesting}). */
    enum StartPhase {
        /** Just after the check that shutdown has not begun. */
        CHECKED_FOR_SHUTDOWN,
        /** Accepting is on; the listeners are about to be opened. */
        ACCEPTING
    }

    /** Test seam: runs at each {@link StartPhase}, on the thread running {@link #start()}. */
    void onStartPhaseForTesting(Consumer<StartPhase> hook) {
        startPhaseHook.set(hook);
    }

    /** Test seam: how many listeners this application currently owns (open). */
    int ownedListenerCountForTesting() {
        lifecycle.lock();
        try {
            return ownedListeners.size();
        } finally {
            lifecycle.unlock();
        }
    }

    private void startListeners(List<ServerConfiguration.Listener> listeners)
            throws IOException, GeneralSecurityException {
        for (ServerConfiguration.Listener listener : listeners) {
            if (listener.tls()) {
                tlsListener = new TlsListener(listener, connectionHandler);
                ownedListeners.add(tlsListener);
                tlsListener.start();
                LOG.info("TLS listener started on port {}", listener.port());
            } else {
                plaintextListener = new PlaintextListener(listener, connectionHandler);
                ownedListeners.add(plaintextListener);
                plaintextListener.start();
                LOG.info("Plaintext listener started on port {}", listener.port());
            }
        }
    }

    /** Closes listeners and connections from a failed start; cleanup failures are suppressed. */
    private void rollbackStart(Exception failure, int previousListenerCount) {
        try {
            closeListeners(
                    ownedListeners.subList(previousListenerCount, ownedListeners.size()),
                    System.nanoTime() + Duration.ofSeconds(3).toNanos());
        } catch (IOException | RuntimeException cleanupFailure) {
            suppressUnlessSame(failure, cleanupFailure);
        }
        if (previousListenerCount == 0) {
            // Nothing else was serving, so every connection belongs to this failed attempt.
            try {
                connectionHandler.closeConnections(Duration.ofSeconds(3));
            } catch (IOException | RuntimeException cleanupFailure) {
                suppressUnlessSame(failure, cleanupFailure);
            }
        }
    }

    private static void suppressUnlessSame(Exception failure, Exception cleanupFailure) {
        if (failure != cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /** The graceful-shutdown budget: inside a container runtime's default 10-second grace. */
    public static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(8);

    /** The {@code ERROR} reason clients see when the server shuts down (spec 016 FR-009). */
    public static final String SHUTDOWN_REASON = "Server shutting down";

    /**
     * Graceful shutdown on TERM or INT (spec 016 FR-009): stops accepting, closes the listeners,
     * then tells every connected client {@code ERROR :Closing Link: <host> (<reason>)} and lets its
     * queued lines drain, all within {@link #SHUTDOWN_BUDGET}. Connections still draining at the
     * budget are closed anyway and a WARN says how many. Only the first call does anything; before
     * {@link #start()} it returns at once.
     */
    public void shutdown(String reason) {
        shutdown(reason, SHUTDOWN_BUDGET);
    }

    /**
     * As {@link #shutdown(String)}, with a chosen budget (tests).
     *
     * @return how many connections were closed before they finished draining
     */
    int shutdown(String reason, Duration budget) {
        if (!shuttingDown.compareAndSet(false, true)) {
            return 0;
        }
        // The budget runs from the signal, not from when the lifecycle lock is free: a start() that
        // is slow must not push the shutdown past what the container runtime will wait for.
        long deadline = System.nanoTime() + budget.toNanos();
        shutdownDeadlineNanos = deadline;
        // Irreversible: a start() already past its own shutdown check cannot reopen admission.
        connectionHandler.closeAdmission();
        if (!closureOwner.compareAndSet(null, ClosureOwner.SHUTDOWN)) {
            // stop() got to the connections first and closes them (and the listeners) itself.
            LOG.info("stop() is already closing the connections; shutdown leaves them to it");
            return 0;
        }
        // Normally free. If a slow start() holds it, connections are drained first and the
        // listeners are closed afterwards, with whatever budget is left.
        boolean locked = lifecycle.tryLock();
        try {
            boolean listenersClosed = true;
            if (locked) {
                listenersClosed = closeListenersForShutdown(deadline);
            }
            int forced;
            try {
                forced = connectionHandler.drain(reason, remainingShutdownTime(deadline));
            } finally {
                drainFinished.countDown();
            }
            if (!locked) {
                locked = acquireLifecycle(deadline);
                if (locked) {
                    listenersClosed = closeListenersForShutdown(deadline);
                } else {
                    listenersClosed = false;
                    LOG.warn(
                            "start() still held the lifecycle when the shutdown budget ran out;"
                                    + " not waiting to close the listeners");
                }
            }
            if (forced > 0) {
                LOG.warn(
                        "Shutdown budget of {} ms ran out; force-closed {} connection(s) that"
                                + " had not finished sending",
                        budget.toMillis(),
                        forced);
            }
            if (listenersClosed) {
                LOG.info("jircd stopped");
            } else {
                // Not retried here: the process exits right after a signal-driven shutdown. A
                // caller that keeps running can call stop(), which retries the retained listeners.
                LOG.warn("jircd shut down but listener cleanup was incomplete; stop() retries it");
            }
            return forced;
        } finally {
            if (locked) lifecycle.unlock();
        }
    }

    private boolean closeListenersForShutdown(long deadline) {
        try {
            closeListeners(ownedListeners, deadline);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Closing the listeners during shutdown failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean acquireLifecycle(long deadline) {
        try {
            return lifecycle.tryLock(
                    remainingShutdownTime(deadline).toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Test seam: lets a test hold the lifecycle as a slow {@link #start()} would. */
    ReentrantLock lifecycleLockForTesting() {
        return lifecycle;
    }

    /**
     * Stops listeners and accepted connections within one 30-second budget. Connection cleanup is
     * attempted even when a listener fails; additional failures are suppressed on the first
     * failure.
     */
    public void stop() throws IOException {
        lifecycle.lock();
        try {
            stopLocked();
        } finally {
            lifecycle.unlock();
        }
    }

    private void stopLocked() throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        connectionHandler.stopAccepting();
        Exception failure = null;
        try {
            closeListeners(ownedListeners, deadline);
        } catch (IOException | RuntimeException listenerFailure) {
            failure = listenerFailure;
        }
        beforeStopClosesConnections.get().run();
        // One atomic decision, so there is no gap between checking for a shutdown and closing.
        ClosureOwner prior = closureOwner.compareAndExchange(null, ClosureOwner.STOP);
        if (prior == ClosureOwner.SHUTDOWN) {
            awaitGracefulDrain();
        }
        try {
            afterStopClaimsClosure.get().run();
            connectionHandler.closeConnections(remainingShutdownTime(deadline));
        } catch (IOException | RuntimeException connectionFailure) {
            if (failure == null) {
                failure = connectionFailure;
            } else if (failure != connectionFailure) {
                failure.addSuppressed(connectionFailure);
            }
        } finally {
            // Ownership lasts only as long as this stop(): the application can be started again,
            // and a later shutdown must then drain the new connections.
            if (prior == null) closureOwner.compareAndSet(ClosureOwner.STOP, null);
        }
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
    }

    /**
     * While a graceful shutdown is draining, the connections are its to close: closing them here
     * would cut off the final {@code ERROR} it is delivering. Waits, bounded by that shutdown's own
     * deadline, then goes on (a drain that overran has force-closed everything anyway). Only called
     * once shutdown has won the closure, which also means its deadline is recorded.
     */
    private void awaitGracefulDrain() {
        // Interrupts do not shorten the wait: proceeding early would close sockets under the drain.
        // The flag is restored afterwards so the caller still sees it.
        boolean interrupted = false;
        try {
            while (true) {
                long left = shutdownDeadlineNanos - System.nanoTime();
                if (left <= 0) {
                    // A drain that finished (even long ago) is not worth a warning: only one still
                    // running past its deadline means stop() goes on under it.
                    if (drainFinished.getCount() != 0) {
                        LOG.warn("stop() went on while the graceful shutdown was still draining");
                    }
                    return;
                }
                try {
                    if (drainFinished.await(left, TimeUnit.NANOSECONDS)) return;
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Test seam: whether the deadline of the (started) graceful shutdown has passed. */
    boolean shutdownDeadlinePassedForTesting() {
        return shutdownDeadlineNanos - System.nanoTime() <= 0;
    }

    /** Test seam: runs in {@link #stop()} just before it closes the connections. */
    void beforeStopClosesConnectionsForTesting(Runnable hook) {
        beforeStopClosesConnections.set(hook);
    }

    /** Test seam: runs in {@link #stop()} once it owns the connections, before it closes them. */
    void afterStopClaimsClosureForTesting(Runnable hook) {
        afterStopClaimsClosure.set(hook);
    }

    /** Test seam: the listeners shutdown and stop close; a test adds one that fails to close. */
    List<Closeable> ownedListenersForTesting() {
        return ownedListeners;
    }

    private static Duration remainingShutdownTime(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }

    // Successful closes relinquish ownership; failed resources remain available for a retry.
    @SuppressWarnings("PMD.CloseResource")
    private static void closeListeners(List<Closeable> listeners, long deadline)
            throws IOException {
        List<Exception> failures = new ArrayList<>();
        var iterator = listeners.iterator();
        while (iterator.hasNext()) {
            Closeable listener = iterator.next();
            try {
                switch (listener) {
                    case PlaintextListener plaintext ->
                            plaintext.close(remainingShutdownTime(deadline));
                    case TlsListener tls -> tls.close(remainingShutdownTime(deadline));
                    default -> listener.close();
                }
                iterator.remove();
            } catch (IOException | RuntimeException closeFailure) {
                failures.add(closeFailure);
            }
        }
        if (failures.isEmpty()) {
            return;
        }
        Exception primary =
                failures.stream()
                        .filter(IOException.class::isInstance)
                        .findFirst()
                        .orElse(failures.getFirst());
        for (Exception failure : failures) {
            if (failure != primary) {
                primary.addSuppressed(failure);
            }
        }
        if (primary instanceof IOException ioFailure) {
            throw ioFailure;
        }
        throw (RuntimeException) primary;
    }

    public ConfigurationReloader reloader() {
        return reloader;
    }

    public int plaintextPort() {
        return plaintextListener.boundPort();
    }

    public int tlsPort() {
        return tlsListener.boundPort();
    }

    /** Test seam for exercising acceptance and connection-drain lifecycle independently. */
    ConnectionHandler connectionHandlerForTesting() {
        return connectionHandler;
    }

    public ExtensionRegistry extensionRegistry() {
        return extensionRegistry;
    }

    public NicknameRegistry nicknameRegistry() {
        return nicknameRegistry;
    }

    public ChannelRegistry channelRegistry() {
        return channelRegistry;
    }

    public String serverName() {
        return serverName;
    }

    private String resolveServerVersion() throws IOException {
        try (InputStream in =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("net/jircd/server/version.properties")) {
            if (in == null) {
                throw new IOException("net/jircd/server/version.properties resource is missing");
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank()) {
                throw new IOException("net/jircd/server/version.properties has no 'version' entry");
            }
            return version;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Path configPath = Path.of(args.length > 0 ? args[0] : "jircd.yaml");
        // Before anything else, so TERM/INT during startup also exits cleanly (spec 016 FR-009).
        ShutdownSignalHandler shutdownSignals = ShutdownSignalHandler.install();
        JircdServerApplication application;
        try {
            application = new JircdServerApplication(configPath);
            shutdownSignals.attach(application);
            application.start();
        } catch (ConfigurationException e) {
            LOG.error("Refusing to start: {}", e.messageNaming(configPath));
            System.exit(1);
            return;
        } catch (IOException | GeneralSecurityException e) {
            LOG.error("Refusing to start: {}", e.getMessage());
            System.exit(1);
            return;
        }
        SighupReloadHandler.install(application);
        LOG.info("jircd started as {}", application.serverName());
        Thread.currentThread().join();
    }
}
