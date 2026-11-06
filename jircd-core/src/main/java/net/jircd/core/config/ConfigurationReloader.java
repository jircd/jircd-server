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
package net.jircd.core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.jircd.core.extension.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core, manually-triggered reload operation (research.md "Configuration reload mechanism") —
 * deliberately not automatic file-watching; it only runs when invoked by a trigger ({@code SIGHUP},
 * {@code REHASH}). On success, reconciles the result against {@link ExtensionRegistry}; on
 * validation failure, the previously-active configuration is left untouched. An Error from a
 * withdrawn provider's stop propagates after publication and cleanup of the remaining withdrawn
 * capabilities; the new configuration stays current and no ReloadOutcome is returned.
 */
public final class ConfigurationReloader {

    /**
     * A reload that validated and was applied, with each extension whose reconciliation failed and
     * why. A provider whose start fails ends FAILED without undoing the rest of the reload.
     */
    public record ReloadOutcome(ServerConfiguration configuration, Map<String, String> failures) {
        public ReloadOutcome {
            failures = Map.copyOf(failures);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationReloader.class);

    private final List<Consumer<ServerConfiguration>> listeners = new CopyOnWriteArrayList<>();
    private final Path configPath;
    private final ConfigurationLoader loader;
    private final ExtensionRegistry extensionRegistry;
    private final String runningServerName;
    private volatile ServerConfiguration current;
    private final Object delivery = new Object();
    // Guarded by delivery.
    private long publishedSequence;
    private long deliveredSequence;
    private boolean delivering;

    public ConfigurationReloader(
            Path configPath,
            ConfigurationLoader loader,
            ExtensionRegistry extensionRegistry,
            ServerConfiguration initial) {
        this.configPath = configPath;
        this.loader = loader;
        this.extensionRegistry = extensionRegistry;
        this.current = initial;
        this.runningServerName = initial.serverName();
    }

    public ServerConfiguration current() {
        return current;
    }

    /**
     * Calls {@code listener} with each configuration a reload applied, after it has been published
     * as {@link #current()}, whichever path reloaded it (SIGHUP or {@code REHASH}). Listeners run
     * outside the reload's lock, in registration order; one that throws is logged and neither
     * undoes the reload nor stops the others. A rejected reload calls none.
     *
     * <p>Delivery is ordered: one thread delivers at a time and always delivers the latest
     * published configuration, so a listener never ends on an older configuration than {@link
     * #current()}. A reload that publishes while another thread (or one of its own listeners'
     * nested reload) is delivering returns without delivering itself, and the delivering thread
     * moves on to the newer configuration, so the older one is skipped for the listeners not yet
     * called. Listeners may therefore run on another thread than the reload's caller.
     *
     * <p>A listener that throws an {@link Error} is logged, and the remaining listeners still run
     * for that configuration. The first such Error is rethrown by the reload call that delivered it
     * (possibly on another thread than the one whose reload published the configuration), after
     * delivery has finished; if that reload was itself failing, the listener Error is added as
     * suppressed on the in-flight failure instead of replacing it. The reload stays applied.
     */
    public void addReloadListener(Consumer<ServerConfiguration> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public Path configPath() {
        return configPath;
    }

    /**
     * Re-reads and re-validates the configuration file, reconciling extension state on success.
     * Serialized, so validation, reconciliation, publication of {@link #current()} and the
     * advertised lengths happen as one ordered operation per reload. Listener settings (including
     * backlog) are validated and published here, but sockets are only bound at application startup;
     * applying listener changes requires a server restart.
     *
     * <p>Reload listeners run after publication, outside the lock. A listener {@link Error} is
     * logged and does not stop the other listeners; the call that delivered it rethrows it once
     * delivery is done (see {@link #addReloadListener}), which may be a different thread's reload
     * than the one that published the configuration. If this reload fails first, the listener Error
     * is suppressed onto that failure.
     *
     * @throws IllegalStateException if called from inside a provider lifecycle callback ({@code
     *     start}, {@code stop}, {@code register}), which would wait on the lifecycle lock its own
     *     thread holds
     */
    public ServerConfiguration reload() throws ConfigurationException, IOException {
        return reloadWithOutcome().configuration();
    }

    /**
     * As {@link #reload()}, also reporting each extension whose reconciliation failed.
     *
     * @throws IllegalStateException if called from inside a provider lifecycle callback
     */
    public ReloadOutcome reloadWithOutcome() throws ConfigurationException, IOException {
        // Checked before the reload monitor is taken, so a callback never queues behind a reload
        // that is itself waiting for the lifecycle lock the callback holds.
        extensionRegistry.requireNoLifecycleCallback();
        boolean[] published = new boolean[1];
        ReloadOutcome outcome;
        try {
            outcome = reloadSerialized(published);
        } catch (Throwable failure) { // NOSONAR java:S1181 - the in-flight failure may be an Error
            // Also when cleanup of withdrawn providers threw after publication: current() has
            // changed, so listeners (logging) must hear about it. The cleanup failure stays the
            // propagated one; a listener Error is attached to it. It is caught here because the
            // failure to attach it to can only be reached by catching it.
            if (published[0]) {
                Error notification = deliverLatest();
                if (notification != null && notification != failure) {
                    failure.addSuppressed(notification);
                }
            }
            throw failure;
        }
        Error notification = deliverLatest();
        if (notification != null) throw notification;
        return outcome;
    }

    /**
     * Delivers the latest published configuration unless another thread is already delivering, in
     * which case that thread picks it up. A listener {@link Error} does not stop delivery: the
     * remaining listeners and any newer configuration are still serviced.
     *
     * @return the first listener Error (later ones suppressed on it), for the caller to rethrow or
     *     attach; null if none
     */
    private Error deliverLatest() {
        synchronized (delivery) {
            if (delivering) return null; // the delivering thread picks up the newer configuration
            delivering = true;
        }
        Error failure = null;
        boolean finished = false;
        try {
            while (true) {
                long sequence;
                ServerConfiguration applied;
                synchronized (delivery) {
                    if (publishedSequence == deliveredSequence) {
                        delivering = false;
                        finished = true;
                        break;
                    }
                    sequence = publishedSequence;
                    deliveredSequence = sequence;
                    applied = current;
                }
                failure = combine(failure, notifyListeners(applied, sequence));
            }
        } finally {
            // Only unfinished if the bookkeeping itself failed: never leave delivery claimed.
            if (!finished) {
                synchronized (delivery) {
                    delivering = false;
                }
            }
        }
        return failure;
    }

    /** {@code later} is suppressed on {@code first} unless it is the same object. */
    private static Error combine(Error first, Error later) {
        if (later == null) return first;
        if (first == null) return later;
        if (first != later) first.addSuppressed(later);
        return first;
    }

    /**
     * @return the first {@link Error} a listener threw, if any; the others still ran
     */
    private Error notifyListeners(ServerConfiguration applied, long sequence) {
        Error failure = null;
        for (Consumer<ServerConfiguration> listener : listeners) {
            synchronized (delivery) {
                if (publishedSequence != sequence) break; // newer one is delivered next
            }
            try {
                listener.accept(applied);
            } catch (RuntimeException e) {
                LOG.warn("A reload listener failed; the reload itself stands", e);
            } catch (Error e) { // NOSONAR java:S1181 - delivery must go on after a listener Error
                LOG.warn("A reload listener failed with an Error; delivery continues", e);
                failure = combine(failure, e);
            }
        }
        return failure;
    }

    /**
     * The whole desired configuration is validated before anything changes: the file, the reply
     * budgets, and the candidate provider graph (hard dependencies present and acyclic, offers and
     * owned slots unique). A validation failure changes no configuration, negotiation or
     * notification. A valid reload is then one lifecycle operation that no {@code EXTENSION} toggle
     * interleaves with: providers to enable start first, prerequisites first, then a single
     * publication swaps the capability snapshot together with the new reply limits, the advertised
     * lengths and {@link #current()}, and only then are withdrawn providers stopped. Enable never
     * restores an earlier negotiation.
     */
    private synchronized ReloadOutcome reloadSerialized(boolean[] published)
            throws ConfigurationException, IOException {
        ServerConfiguration reloaded;
        reloaded = loader.load(configPath);
        loader.checkReplyBudgets(
                runningServerName, reloaded.nicknameMaxLength(), reloaded.channelNameMaxLength());
        ExtensionRegistry.DesiredState desired =
                new ExtensionRegistry.DesiredState(
                        reloaded.capabilityStates(),
                        reloaded.serverExtensionStates(),
                        reloaded.nicknameMaxLength(),
                        reloaded.channelNameMaxLength(),
                        reloaded.topicMaxLength(),
                        reloaded.maxModesPerCommand(),
                        changedEntries(current.capabilityStates(), reloaded.capabilityStates()));
        Map<String, String> failures;
        try {
            failures =
                    extensionRegistry.applyDesiredState(
                            desired,
                            () -> {
                                synchronized (delivery) {
                                    this.current = reloaded;
                                    publishedSequence++;
                                }
                                published[0] = true;
                            });
        } catch (ExtensionRegistry.InvalidDesiredStateException e) {
            throw new ConfigurationException(e.getMessage());
        }
        return new ReloadOutcome(reloaded, failures);
    }

    /**
     * The ids whose entry differs between the last applied and the reloaded section: a changed
     * value, an added entry or a removed one. Only these may retry a FAILED provider.
     */
    private static Set<String> changedEntries(
            Map<String, Boolean> applied, Map<String, Boolean> reloaded) {
        Set<String> changed = new HashSet<>();
        for (Map.Entry<String, Boolean> entry : applied.entrySet()) {
            if (!Objects.equals(entry.getValue(), reloaded.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        for (String id : reloaded.keySet()) {
            if (!applied.containsKey(id)) changed.add(id);
        }
        return changed;
    }
}
