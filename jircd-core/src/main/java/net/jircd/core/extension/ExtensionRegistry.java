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
package net.jircd.core.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import net.jircd.core.capability.Capability;
import net.jircd.core.config.HostmaskReplyBudget;
import net.jircd.core.extension.capability.CapabilityControl;
import net.jircd.core.extension.capability.CapabilityDescriptor;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.CapabilityRuntime;
import net.jircd.core.extension.capability.CapabilityServices;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.extension.capability.ReplyBudgetCatalog;
import net.jircd.core.extension.capability.ReplyLimits;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.core.session.ChannelMode;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CommittedEvents;
import net.jircd.core.session.UserMode;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.CapabilityNegotiationGrammar;
import net.jircd.protocol.Command;
import net.jircd.protocol.MessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers, tracks, and hot-toggles every {@link CapabilityExtension} and {@link ServerExtension}
 * (FR-011). At most one {@code ENABLED} extension may claim a given {@code extensionPoint} at a
 * time (FR-012, research.md "Extension-point ownership").
 */
public final class ExtensionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(ExtensionRegistry.class);

    /**
     * Thrown when enabling an extension would conflict with another already-enabled extension's
     * claim.
     */
    public static final class ExtensionPointConflictException extends RuntimeException {
        public ExtensionPointConflictException(String message) {
            super(message);
        }
    }

    private static final class Entry {
        final Extension extension;
        volatile Extension.State state = Extension.State.DISABLED;

        /** A FAILED provider whose cleanup did not finish; a later {@code disable} retries it. */
        volatile boolean cleanupPending;

        Entry(Extension extension) {
            this.extension = extension;
        }
    }

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();
    private final Map<String, String> extensionPointOwners = new ConcurrentHashMap<>();
    private final AtomicReference<Set<String>> knownCapabilityNames =
            new AtomicReference<>(Set.of());
    private volatile ServerContext serverContext;

    private volatile SupportedFeatures supportedFeatures = SupportedFeatures.defaults();

    private final CapabilityRuntime capabilityRuntime = new CapabilityRuntime();
    private final CapabilityFaults faults;
    private final HookDispatcher hookDispatcher;
    private final CommittedEvents committedEvents = new CommittedEvents(this);

    /**
     * Serializes enable and disable. It is not the registry monitor and no message path takes it,
     * so provider callbacks (start, register, stop) run while it is held without blocking delivery
     * or any registry read, and two lifecycle changes still never interleave. It is taken before
     * the capability runtime's administrative mutex, never inside it; the publication itself runs
     * under that short mutex, and provider callbacks never do.
     */
    private final ReentrantLock lifecycle = new ReentrantLock();

    /** Withdraws faulting providers on a virtual thread of their own, never the faulting one. */
    public ExtensionRegistry() {
        this(onOwnThread(Thread.ofVirtual().name("capability-fault-withdrawal").factory()));
    }

    private static Executor onOwnThread(ThreadFactory threads) {
        return task -> threads.newThread(task).start();
    }

    /**
     * @param faultWithdrawals runs the FAILED withdrawal a provider's runtime hook fault starts; it
     *     must not run the task on the faulting thread in production, which may hold no lock but is
     *     some client's delivery
     */
    public ExtensionRegistry(Executor faultWithdrawals) {
        this.faults =
                new CapabilityFaults(
                        faultWithdrawals,
                        id -> capabilityRuntime.snapshot().descriptor(id).orElse(null),
                        this::failProvider);
        this.hookDispatcher = new HookDispatcher(faults);
    }

    /**
     * The handler for {@code command} if a published provider owns it, resolved from one read of
     * the current snapshot: dispatch sees a provider and its commands together, before or after a
     * publication, never a mix. Provider commands are never installed in core's command table, so a
     * publication alone moves them; work that resolved a handler before a change may still run it
     * afterwards, like any other captured work. Faults are contained and reported.
     */
    public Optional<CommandHandler> capabilityCommand(Command command) {
        return capabilityRuntime.snapshot().command(command).map(hookDispatcher::guardedCommand);
    }

    /** The currently published capability hooks; read-only, lifecycle stays with this registry. */
    public RuntimeSnapshot capabilitySnapshot() {
        return capabilityRuntime.snapshot();
    }

    /**
     * The capability runtime's administrative mutex, shared by CAP state, live-session tracking,
     * output capture and group admission. Publication itself goes only through this registry's
     * lifecycle, so the runtime is not exposed.
     */
    public CapabilityControl capabilityControl() {
        return capabilityRuntime.control();
    }

    /**
     * Tracks {@code sessions}, every accepted connection, as the sessions a publication prunes and
     * notifies. Their owner inserts, removes and attaches writers under {@link #capabilityControl}.
     */
    public void attachLiveSessions(Collection<ClientSession> sessions) {
        capabilityRuntime.attachLiveSessions(sessions);
    }

    /**
     * Receives the stage timings of every later capability publication that changes the offered
     * set: mutex wait, snapshot swap and all-session prune, and notification enqueue. For
     * measurement.
     */
    public void observeCapabilityPublications(
            Consumer<CapabilityRuntime.PublicationTiming> observer) {
        capabilityRuntime.observePublications(observer);
    }

    /** The handle {@link CapabilityExtension}s receive through {@link ServerContext}. */
    public CapabilityServices capabilityServices() {
        return new CapabilityServices(capabilityRuntime::snapshot);
    }

    /** Publishes committed state transitions to the providers that listen for them. */
    public CommittedEvents committedEvents() {
        return committedEvents;
    }

    /** The containing dispatcher every capability hook call goes through. */
    public HookDispatcher hookDispatcher() {
        return hookDispatcher;
    }

    /**
     * The reply decorations of every discovered provider that stages validly, enabled or not, so a
     * later toggle cannot invalidate a configuration accepted now. Providers that fail to stage are
     * left out here and are rejected when enabled.
     */
    public ReplyBudgetCatalog discoveredReplyBudgetCatalog() {
        List<CapabilityDescriptor> descriptors = new ArrayList<>();
        for (Entry entry : byId.values()) {
            if (entry.extension instanceof CapabilityExtension capability) {
                try {
                    descriptors.add(stage(capability));
                } catch (RuntimeException | LinkageError _) {
                    // Reported with the provider's id when it is enabled.
                }
            }
        }
        return ReplyBudgetCatalog.fromDescriptors(descriptors);
    }

    private static CapabilityDescriptor stage(CapabilityExtension capability) {
        CapabilityRegistrar registrar =
                new CapabilityRegistrar(capability.id(), capability.providedCapability().name());
        try {
            capability.register(registrar);
            return registrar.build();
        } finally {
            registrar.close();
        }
    }

    /**
     * {@link ServerContext} itself references this registry, so the two are constructed
     * independently and wired together here rather than via constructor injection.
     */
    public void attachContext(ServerContext serverContext) {
        this.serverContext = serverContext;
        capabilityRuntime.attachServerName(serverContext.serverName());
        refreshReplyLimits();
    }

    /** Slot bounds are evaluated against the validated configuration, as startup and enable do. */
    private void refreshReplyLimits() {
        capabilityRuntime.setLimits(replyLimits(supportedFeatures));
    }

    private ReplyLimits replyLimits(SupportedFeatures features) {
        ServerContext context = serverContext;
        int serverBytes =
                context == null
                        ? ReplyLimits.REFERENCE.serverBytes()
                        : MessageSerializer.utf8Bytes(context.serverName().get());
        return ReplyLimits.of(
                serverBytes, features.nicknameMaxLength(), features.channelNameMaxLength());
    }

    /**
     * Registers an already-instantiated extension (used by discovery and by tests).
     *
     * @throws IllegalArgumentException if the extension contributes a LIST channel mode; mask-list
     *     storage and handling are currently defined only for core modes
     */
    public void register(Extension extension) {
        if (extension instanceof ServerExtension serverExtension) {
            for (ChannelMode mode : serverExtension.contributedChannelModes()) {
                if (mode.kind() == ChannelMode.Kind.LIST) {
                    throw new IllegalArgumentException(
                            "Extension '"
                                    + extension.id()
                                    + "' cannot contribute LIST channel mode: "
                                    + mode.flag());
                }
            }
        }
        // Resolved before the entry is inserted, so a provider that cannot name its capability
        // leaves nothing behind and can register again once fixed.
        String name = capabilityName(extension);
        Entry existing = byId.putIfAbsent(extension.id(), new Entry(extension));
        if (existing != null && existing.extension != extension) {
            throw new IllegalArgumentException("Duplicate extension id: " + extension.id());
        }
        if (existing == null && name != null) {
            knownCapabilityNames.updateAndGet(
                    names -> {
                        Set<String> grown = new HashSet<>(names);
                        grown.add(name);
                        return Set.copyOf(grown);
                    });
        }
    }

    /** A capability provider's offered name, or {@code null} for a server extension. */
    private static String capabilityName(Extension extension) {
        if (!(extension instanceof CapabilityExtension capability)) return null;
        try {
            return capability.providedCapability().name();
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalArgumentException(
                    "Extension '" + extension.id() + "' cannot name its capability: " + describe(e),
                    e);
        }
    }

    /**
     * The capability names every discovered provider offers, enabled or not, recorded once at
     * registration so CAP can tell a known name from an unknown one without calling a provider.
     */
    public Set<String> knownCapabilityNames() {
        return knownCapabilityNames.get();
    }

    /**
     * Finds every {@link CapabilityExtension}/{@link ServerExtension} on {@code classLoader} via
     * {@link ServiceLoader}.
     */
    public void discover(ClassLoader classLoader) {
        ServiceLoader.load(CapabilityExtension.class, classLoader)
                .forEach(this::registerDiscovered);
        ServiceLoader.load(ServerExtension.class, classLoader).forEach(this::registerDiscovered);
    }

    /** A duplicate id is reported and skipped; it must not hide the extensions after it. */
    private void registerDiscovered(Extension extension) {
        try {
            register(extension);
        } catch (IllegalArgumentException e) {
            LOG.warn(
                    "Ignoring discovered extension {}: {}",
                    extension.getClass().getName(),
                    e.getMessage());
        }
    }

    public Extension.State stateOf(String id) {
        Entry entry = byId.get(id);
        return entry == null ? null : entry.state;
    }

    public Optional<Extension> find(String id) {
        Entry entry = byId.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.extension);
    }

    public Collection<Extension> all() {
        return byId.values().stream().map(e -> e.extension).toList();
    }

    public Collection<Extension> enabled() {
        return byId.values().stream()
                .filter(e -> e.state == Extension.State.ENABLED)
                .map(e -> e.extension)
                .toList();
    }

    /**
     * Enables the named extension, rejecting a conflicting {@code extensionPoint} claim (FR-012).
     * Provider callbacks run under the lifecycle lock only, never the registry monitor.
     */
    public void enable(String id) {
        requireNoLifecycleCallback();
        lifecycle.lock();
        try {
            enableLocked(id);
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Provider callbacks run with the lifecycle lock held, so they must not start another lifecycle
     * change or a configuration reload: it would queue behind (or deadlock with) the lock they
     * hold.
     *
     * @throws IllegalStateException if the calling thread is inside a lifecycle callback
     */
    public void requireNoLifecycleCallback() {
        if (lifecycle.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "Extension lifecycle changes and reloads cannot be requested from a provider lifecycle"
                            + " callback (start, stop, register)");
        }
    }

    private void enableLocked(String id) {
        Entry entry = byId.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown extension id: " + id);
        }
        if (entry.state == Extension.State.ENABLED) {
            return; // idempotent
        }
        String point = extensionPoint(entry.extension);
        if (point != null) {
            String existingOwner = extensionPointOwners.get(point);
            if (existingOwner != null && !existingOwner.equals(id)) {
                throw new ExtensionPointConflictException(
                        "Extension point '"
                                + point
                                + "' already claimed by '"
                                + existingOwner
                                + "', cannot enable '"
                                + id
                                + "'");
            }
        }
        // A cleanup a failed disable left unfinished (a stop that failed, the extension-point
        // claim) is completed before the provider starts afresh.
        if (entry.cleanupPending) throwIfPresent(cleanUp(entry));
        boolean enabled = false;
        try {
            entry.extension.start(serverContext);
            if (entry.extension instanceof CapabilityExtension capability) publish(capability);
            entry.state = Extension.State.ENABLED;
            entry.cleanupPending = false;
            if (point != null) {
                extensionPointOwners.put(point, id);
            }
            enabled = true;
        } finally {
            if (!enabled) {
                // Whatever failed, including an Error: a start that threw may have acquired
                // resources first, so stop runs (and must tolerate that) and the provider is
                // recorded as failed.
                boolean stopFailed = stopQuietly(entry.extension);
                entry.state = Extension.State.FAILED;
                entry.cleanupPending = stopFailed;
                // A claim is only kept while cleanup is unfinished.
                if (!entry.cleanupPending && point != null) extensionPointOwners.remove(point, id);
            }
        }
    }

    /**
     * Stages and validates a provider's hooks and commands, then publishes them in one atomic step:
     * its commands are part of the snapshot, so no reader ever sees hooks or commands of an enable
     * that fails, and nothing outside the snapshot has to be installed or rolled back.
     */
    private void publish(CapabilityExtension capability) {
        CapabilityDescriptor descriptor = stage(capability);
        RuntimeSnapshot next = capabilityRuntime.snapshot().with(descriptor);
        validateReplyBudget(next, supportedFeatures);
        requireUnownedCommands(commandsOf(descriptor));
        capabilityRuntime.publish(descriptor);
    }

    private static List<HookRegistration> commandsOf(CapabilityDescriptor descriptor) {
        return descriptor.registrations().stream()
                .filter(registration -> registration.kind() == HookKind.COMMAND)
                .toList();
    }

    /**
     * A provider may add commands but never replace one core or a server extension owns. A command
     * another published provider owns is the snapshot validation's to judge (it may be moving to
     * this provider in the same reload), so it does not count here even though the registrar
     * reports it as registered.
     */
    private void requireUnownedCommands(List<HookRegistration> commands) {
        if (serverContext == null) return;
        RuntimeSnapshot current = capabilityRuntime.snapshot();
        for (HookRegistration registration : commands) {
            Command command = registration.command();
            if (serverContext.commandRegistrar().isRegistered(command)
                    && current.command(command).isEmpty()) {
                throw new IllegalArgumentException(
                        "Command " + command + " is already handled and cannot be replaced");
            }
        }
    }

    private static void throwIfPresent(Throwable failure) {
        switch (failure) {
            case null -> {
                // Every cleanup step succeeded: nothing to report.
            }
            case RuntimeException e -> throw e;
            case LinkageError e -> throw e;
            default -> throw new IllegalStateException(failure);
        }
    }

    /**
     * The same bound checker the configuration loader uses, against the actual active slots, and
     * the one-line {@code CAP LS} an older client must get: core cap-notify plus every offer,
     * addressed to a nickname of the configured maximum length. An offer set that cannot fit is
     * rejected rather than truncated.
     */
    private void validateReplyBudget(RuntimeSnapshot active, SupportedFeatures features) {
        ServerContext context = serverContext;
        if (context == null) return;
        String server = context.serverName().get();
        HostmaskReplyBudget.requireFits(
                net.jircd.protocol.MessageSerializer.utf8Bytes(server),
                features.nicknameMaxLength(),
                features.channelNameMaxLength(),
                active.replyBudgetCatalog());
        List<String> offers = new ArrayList<>(active.offeredNames());
        offers.add(CapabilityName.CAP_NOTIFY);
        if (!CapabilityNegotiationGrammar.fitsOneLine(
                server, "x".repeat(features.nicknameMaxLength()), "LS", offers)) {
            throw new IllegalArgumentException(
                    "The offered capabilities would not fit one CAP LS line for a pre-302 client");
        }
    }

    /** Whether {@code stop} failed; the failure is logged, not thrown. */
    private static boolean stopQuietly(Extension extension) {
        try {
            extension.stop();
            return false;
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Extension '{}' failed to stop after a failed start", extension.id(), e);
            return true;
        }
    }

    /** A desired configuration that is invalid as a whole; nothing was changed. */
    public static final class InvalidDesiredStateException extends IllegalArgumentException {
        public InvalidDesiredStateException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The extension states and configured lengths a validated configuration asks for.
     *
     * @param changedCapabilities the capability ids whose entry differs from the configuration last
     *     applied (its value changed, or it was added or removed). Only these count as an explicit
     *     request to retry a FAILED provider; a FAILED provider whose entry is unchanged stays
     *     FAILED
     */
    public record DesiredState(
            Map<String, Boolean> capabilities,
            Map<String, Boolean> serverExtensions,
            int nicknameMaxLength,
            int channelNameMaxLength,
            int topicMaxLength,
            int maxModesPerCommand,
            Set<String> changedCapabilities) {
        public DesiredState {
            capabilities = Map.copyOf(capabilities);
            serverExtensions = Map.copyOf(serverExtensions);
            changedCapabilities = Set.copyOf(changedCapabilities);
        }

        /**
         * A first application, with no earlier configuration: every listed entry counts as changed.
         */
        public DesiredState(
                Map<String, Boolean> capabilities,
                Map<String, Boolean> serverExtensions,
                int nicknameMaxLength,
                int channelNameMaxLength,
                int topicMaxLength,
                int maxModesPerCommand) {
            this(
                    capabilities,
                    serverExtensions,
                    nicknameMaxLength,
                    channelNameMaxLength,
                    topicMaxLength,
                    maxModesPerCommand,
                    capabilities.keySet());
        }
    }

    /**
     * Applies a whole desired configuration as one lifecycle operation: the lifecycle lock is held
     * throughout, so no {@code EXTENSION} toggle interleaves. The candidate provider graph is
     * validated first (hard dependencies present and acyclic, offers, owned keys, slots and
     * commands unique, no command core or a server extension handles, reply budgets at the new
     * lengths); if it is invalid nothing changes and no provider is started or stopped. Providers
     * to enable are then started, prerequisites first, outside the administrative mutex. One
     * publication then swaps in the new snapshot with the new reply limits, prunes every session,
     * queues {@code DEL}/{@code NEW}, and runs {@code publishConfiguration} in the same critical
     * section. Withdrawn providers are stopped afterwards, each before what it requires. A provider
     * whose start fails ends {@code FAILED} alone (with any dependent in the same reload); the rest
     * still applies. A provider already FAILED is restarted only if its entry is among {@link
     * DesiredState#changedCapabilities}. Server extensions are reconciled one by one inside the
     * same lifecycle hold.
     *
     * @return each extension that could not reach its desired state, with why
     * @throws InvalidDesiredStateException if the desired provider graph is invalid; nothing
     *     changed
     */
    public Map<String, String> applyDesiredState(
            DesiredState desired, Runnable publishConfiguration) {
        requireNoLifecycleCallback();
        lifecycle.lock();
        try {
            Map<String, String> failures = new LinkedHashMap<>();
            applyCapabilities(desired, publishConfiguration, failures);
            reconcileServerExtensions(desired.serverExtensions(), failures);
            return failures;
        } finally {
            lifecycle.unlock();
        }
    }

    /** What a valid reload will enable and withdraw, decided before anything changes. */
    private record CapabilityPlan(
            List<CapabilityDescriptor> toStart,
            Map<String, Throwable> stagingFailures,
            List<String> withdrawn,
            SupportedFeatures features) {}

    /** The working state of one reload's capability batch. */
    private static final class Batch {
        final Map<String, String> failures;
        final Set<String> failed = new HashSet<>();
        final List<CapabilityDescriptor> started = new ArrayList<>();

        Batch(Map<String, String> failures) {
            this.failures = failures;
        }

        void fail(String id, String why) {
            failures.put(id, why);
            failed.add(id);
        }
    }

    private void applyCapabilities(
            DesiredState desired, Runnable publishConfiguration, Map<String, String> failures) {
        CapabilityPlan plan = planCapabilities(desired);
        Batch batch = new Batch(failures);
        plan.stagingFailures()
                .forEach(
                        (id, cause) -> {
                            markFailed(byId.get(id), false);
                            batch.fail(id, describe(cause));
                        });
        boolean published = false;
        try {
            for (CapabilityDescriptor descriptor : plan.toStart()) {
                if (startForBatch(descriptor, batch)) batch.started.add(descriptor);
            }
            publishBatch(plan, batch, publishConfiguration);
            published = true;
        } finally {
            if (!published) {
                // Something escaped: nothing was published, so nothing started here may stay
                // running. Dependents are rolled back before the providers they require.
                for (CapabilityDescriptor descriptor : batch.started.reversed()) {
                    rollBackStart(byId.get(descriptor.providerId()));
                }
            }
        }
        finishWithdrawn(plan.withdrawn(), desired, failures);
    }

    /**
     * Stops the withdrawn providers, each before what it requires, then retries the cleanup of any
     * FAILED provider desired disabled whose cleanup was unfinished before this reload. A provider
     * this reload already stopped is not stopped again: its failure is reported, and the next
     * disable or reload retries it.
     */
    private void finishWithdrawn(
            List<String> withdrawn, DesiredState desired, Map<String, String> failures) {
        for (String id : withdrawn) {
            Throwable failure = finish(byId.get(id));
            if (failure != null) failures.put(id, describe(failure));
        }
        for (var entry : desired.capabilities().entrySet()) {
            Entry pending = byId.get(entry.getKey());
            if (Boolean.FALSE.equals(entry.getValue())
                    && pending != null
                    && pending.cleanupPending
                    && !withdrawn.contains(entry.getKey())) {
                Throwable failure = finish(pending);
                if (failure != null) failures.put(entry.getKey(), describe(failure));
            }
        }
    }

    /**
     * Stages every provider to enable and validates the whole candidate graph against the current
     * snapshot. A provider whose own staging throws is a provider fault, not an invalid
     * configuration: it, and any staged provider requiring it, end FAILED and are left out.
     */
    private CapabilityPlan planCapabilities(DesiredState desired) {
        RuntimeSnapshot current = capabilityRuntime.snapshot();
        SupportedFeatures features =
                supportedFeatures.withConfiguredLengths(
                        desired.nicknameMaxLength(),
                        desired.channelNameMaxLength(),
                        desired.topicMaxLength(),
                        desired.maxModesPerCommand());
        Map<String, CapabilityDescriptor> staged = new TreeMap<>();
        Map<String, Throwable> stagingFailures = new TreeMap<>();
        Set<String> withdrawRoots = new TreeSet<>();
        for (var entry : desired.capabilities().entrySet()) {
            String id = entry.getKey();
            if (isUnrequestedRetry(id, entry.getValue(), desired)) continue;
            classify(id, entry.getValue(), staged, stagingFailures, withdrawRoots);
        }
        List<String> withdrawn = current.withdrawalOrder(withdrawRoots);
        requireNoneDesiredEnabled(withdrawn, desired);
        dropDependentsOf(stagingFailures, staged);
        validateCandidate(current, withdrawn, staged.values(), features);
        return new CapabilityPlan(
                prerequisitesFirst(staged.values()), stagingFailures, withdrawn, features);
    }

    /**
     * Whether a reload would restart a FAILED provider that nobody asked to retry: it is desired
     * enabled, but its entry is the same as in the configuration last applied. Such a provider, a
     * hard dependent included, stays FAILED; only a changed entry or {@code EXTENSION ENABLE}
     * retries it.
     */
    private boolean isUnrequestedRetry(String id, Boolean desiredEnabled, DesiredState desired) {
        Entry known = byId.get(id);
        return Boolean.TRUE.equals(desiredEnabled)
                && known != null
                && known.state == Extension.State.FAILED
                && !desired.changedCapabilities().contains(id);
    }

    /** Sorts one desired capability into the withdrawals, the stagings or the staging failures. */
    private void classify(
            String id,
            Boolean desiredEnabled,
            Map<String, CapabilityDescriptor> staged,
            Map<String, Throwable> stagingFailures,
            Set<String> withdrawRoots) {
        Entry known = byId.get(id);
        if (known == null || !(known.extension instanceof CapabilityExtension capability)) return;
        if (Boolean.FALSE.equals(desiredEnabled)) {
            withdrawRoots.add(id);
        } else if (known.state != Extension.State.ENABLED) {
            try {
                staged.put(id, stage(capability));
            } catch (RuntimeException | LinkageError e) {
                stagingFailures.put(id, e);
            }
        }
    }

    /** A provider desired enabled must not be pulled out by a disable's dependent cascade. */
    private static void requireNoneDesiredEnabled(List<String> withdrawn, DesiredState desired) {
        for (String id : withdrawn) {
            if (Boolean.TRUE.equals(desired.capabilities().get(id))) {
                throw new InvalidDesiredStateException(
                        "Capability '"
                                + id
                                + "' is desired enabled but requires a provider this configuration disables",
                        null);
            }
        }
    }

    /**
     * The published providers minus the withdrawn, plus the staged, must form a valid snapshot, and
     * no staged provider may claim a command core or a server extension owns. No server extension
     * starts or registers a command while the lifecycle lock is held for this reload's capability
     * batch, so the check still holds when the batch is published.
     */
    private void validateCandidate(
            RuntimeSnapshot current,
            List<String> withdrawn,
            Collection<CapabilityDescriptor> staged,
            SupportedFeatures features) {
        List<CapabilityDescriptor> candidate = new ArrayList<>();
        for (CapabilityDescriptor descriptor : current.descriptors()) {
            if (!withdrawn.contains(descriptor.providerId())) candidate.add(descriptor);
        }
        candidate.addAll(staged);
        try {
            validateReplyBudget(RuntimeSnapshot.of(candidate), features);
            for (CapabilityDescriptor descriptor : staged)
                requireUnownedCommands(commandsOf(descriptor));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new InvalidDesiredStateException(
                    "Invalid capability configuration: " + e.getMessage(), e);
        }
    }

    /** Moves every staged provider that transitively requires a failed one into the failures. */
    private static void dropDependentsOf(
            Map<String, Throwable> failures, Map<String, CapabilityDescriptor> staged) {
        boolean grew = true;
        while (grew) {
            grew = false;
            for (CapabilityDescriptor descriptor : List.copyOf(staged.values())) {
                String missing =
                        descriptor.requiresProviders().stream()
                                .filter(failures::containsKey)
                                .findFirst()
                                .orElse(null);
                if (missing != null) {
                    staged.remove(descriptor.providerId());
                    failures.put(
                            descriptor.providerId(),
                            new IllegalStateException("requires '" + missing + "', which failed"));
                    grew = true;
                }
            }
        }
    }

    /** {@code descriptors} ordered so each comes after the providers it requires among them. */
    private static List<CapabilityDescriptor> prerequisitesFirst(
            Collection<CapabilityDescriptor> descriptors) {
        Map<String, CapabilityDescriptor> byProvider = new TreeMap<>();
        descriptors.forEach(d -> byProvider.put(d.providerId(), d));
        List<CapabilityDescriptor> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String id : byProvider.keySet())
            placePrerequisitesFirst(id, byProvider, placed, ordered);
        return ordered;
    }

    private static void placePrerequisitesFirst(
            String id,
            Map<String, CapabilityDescriptor> byProvider,
            Set<String> placed,
            List<CapabilityDescriptor> ordered) {
        if (!placed.add(id)) return;
        CapabilityDescriptor descriptor = byProvider.get(id);
        for (String required : new TreeSet<>(descriptor.requiresProviders())) {
            if (byProvider.containsKey(required)) {
                placePrerequisitesFirst(required, byProvider, placed, ordered);
            }
        }
        ordered.add(descriptor);
    }

    /**
     * Starts one provider of a batch, outside the administrative mutex. Its commands need no
     * installation: they were validated with the candidate and are part of the descriptor the batch
     * publishes, so a command a withdrawn provider owned moves to its new owner in that one
     * publication. Returns whether it is ready to publish; if its start fails it is stopped and
     * FAILED, and nothing about it is visible. A provider that never starts is never stopped here.
     */
    private boolean startForBatch(CapabilityDescriptor descriptor, Batch batch) {
        String id = descriptor.providerId();
        Entry entry = byId.get(id);
        if (!mayStart(descriptor, entry, batch)) return false;
        boolean ready = false;
        try {
            entry.extension.start(serverContext);
            ready = true;
        } catch (RuntimeException | LinkageError e) {
            batch.failures.put(id, describe(e));
        } finally {
            if (!ready) {
                batch.failed.add(id);
                rollBackStart(entry);
            }
        }
        return ready;
    }

    /**
     * Whether a provider of the batch may start: none of its prerequisites failed in this batch,
     * and any cleanup an earlier failure left is finished first. Otherwise it is recorded as
     * failed.
     */
    private boolean mayStart(CapabilityDescriptor descriptor, Entry entry, Batch batch) {
        String id = descriptor.providerId();
        String missing =
                descriptor.requiresProviders().stream()
                        .filter(batch.failed::contains)
                        .findFirst()
                        .orElse(null);
        if (missing != null) {
            markFailed(entry, false);
            batch.fail(id, "requires '" + missing + "', which failed");
            return false;
        }
        if (entry.cleanupPending) {
            Throwable leftover = cleanUp(entry);
            if (leftover != null) {
                batch.fail(id, describe(leftover));
                return false;
            }
        }
        return true;
    }

    /** Undoes a start that will not be published: stopped and recorded FAILED. */
    private void rollBackStart(Entry entry) {
        markFailed(entry, stopQuietly(entry.extension));
    }

    /**
     * Records {@code entry} FAILED. A cleanup already owed (an earlier stop that failed) stays owed
     * until a cleanup actually succeeds; this never clears it.
     */
    private static void markFailed(Entry entry, boolean stopFailed) {
        entry.state = Extension.State.FAILED;
        if (stopFailed) entry.cleanupPending = true;
    }

    /**
     * The reload's one publication. The lifecycle lock has been held since the candidate graph was
     * validated, so the providers it publishes are the ones validated; if it were refused anyway,
     * the caller rolls back every start and nothing is published. Commands move with the snapshot.
     */
    private void publishBatch(CapabilityPlan plan, Batch batch, Runnable publishConfiguration) {
        ReplyLimits limits = replyLimits(plan.features());
        // The callback runs first: if it throws, the lengths stay as they were and nothing is
        // published.
        Runnable alongside =
                () -> {
                    publishConfiguration.run();
                    supportedFeatures = plan.features();
                };
        Set<String> withdrawn = Set.copyOf(plan.withdrawn());
        List<CapabilityDescriptor> started = List.copyOf(batch.started);
        capabilityRuntime.publish(
                current -> withChanges(current, withdrawn, started).withLimits(limits), alongside);
        for (CapabilityDescriptor descriptor : started) {
            Entry entry = byId.get(descriptor.providerId());
            entry.state = Extension.State.ENABLED;
            entry.cleanupPending = false;
        }
    }

    private static RuntimeSnapshot withChanges(
            RuntimeSnapshot current, Set<String> withdrawn, List<CapabilityDescriptor> added) {
        List<CapabilityDescriptor> next = new ArrayList<>();
        for (CapabilityDescriptor descriptor : current.descriptors()) {
            if (!withdrawn.contains(descriptor.providerId())) next.add(descriptor);
        }
        next.addAll(added);
        return RuntimeSnapshot.of(next);
    }

    private void reconcileServerExtensions(
            Map<String, Boolean> desiredStates, Map<String, String> failures) {
        for (var entry : new TreeMap<>(desiredStates).entrySet()) {
            String id = entry.getKey();
            try {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    enableLocked(id);
                } else {
                    // Idempotent; for a FAILED provider it retries the cleanup that is still
                    // pending.
                    disableLocked(id);
                }
            } catch (RuntimeException | LinkageError e) {
                LOG.warn(
                        "Failed to reconcile extension '{}' to desired state {}",
                        id,
                        entry.getValue(),
                        e);
                failures.put(id, describe(e));
            }
        }
    }

    private static String describe(Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    /**
     * Disables the named extension. Cleanup completes fully or the provider is recorded FAILED:
     * withdrawn from every new capture, its commands included (they are part of the snapshot). A
     * FAILED provider whose cleanup is unfinished keeps its {@code extensionPoint} claim, so no
     * other provider can take the point while its resources may still be live; calling {@code
     * disable} again retries the cleanup, and the claim is released when that succeeds. Provider
     * callbacks run under the lifecycle lock only, never the registry monitor.
     */
    public void disable(String id) {
        requireNoLifecycleCallback();
        lifecycle.lock();
        try {
            disableLocked(id);
        } finally {
            lifecycle.unlock();
        }
    }

    private void disableLocked(String id) {
        Entry entry = byId.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown extension id: " + id);
        }
        boolean retry = entry.state == Extension.State.FAILED && entry.cleanupPending;
        if (entry.state != Extension.State.ENABLED && !retry) {
            return;
        }
        throwIfPresent(cleanUp(entry));
    }

    /**
     * Withdraws the provider together with every published provider that requires it, in one
     * publication that also prunes their names from every live session and queues {@code DEL}; then
     * stops them, dependents first, outside the administrative mutex. Their commands left with the
     * snapshot. A clean run leaves the provider DISABLED and releases its extension-point claim; a
     * failure leaves it FAILED, withdrawn, with the claim and the unfinished work kept for a retry.
     * A withdrawn dependent is finished the same way and keeps its own outcome. Returns the failure
     * instead of throwing so callers decide: the provider's own, or else the first dependent's, so
     * a dependent left FAILED is never reported as a clean disable. Every other failure is attached
     * as suppressed.
     */
    private Throwable cleanUp(Entry entry) {
        String id = entry.extension.id();
        List<Throwable> dependentFailures = new ArrayList<>();
        // Withdrawal comes first; stop then runs outside any snapshot, so callbacks already holding
        // an older one finish against it.
        for (String dependent : capabilityRuntime.withdraw(id)) {
            Entry other = byId.get(dependent);
            if (dependent.equals(id) || other == null) continue;
            Throwable dependentFailure = finish(other);
            if (dependentFailure != null) {
                dependentFailures.add(
                        new IllegalStateException(
                                "'"
                                        + dependent
                                        + "' failed to stop after its prerequisite '"
                                        + id
                                        + "' was withdrawn: "
                                        + describe(dependentFailure),
                                dependentFailure));
            }
        }
        Throwable failure = finish(entry);
        if (failure == null && !dependentFailures.isEmpty())
            failure = dependentFailures.removeFirst();
        if (failure != null) dependentFailures.forEach(failure::addSuppressed);
        return failure;
    }

    /** Stops an already withdrawn provider and records the outcome. */
    private Throwable finish(Entry entry) {
        String id = entry.extension.id();
        Throwable failure = null;
        try {
            entry.extension.stop();
        } catch (RuntimeException | LinkageError e) {
            // Already withdrawn: it stays withdrawn, recorded as failed rather than half-enabled.
            failure = e;
        }
        entry.state = failure == null ? Extension.State.DISABLED : Extension.State.FAILED;
        entry.cleanupPending = failure != null;
        String point = extensionPoint(entry.extension);
        if (point != null && failure == null) {
            extensionPointOwners.remove(point, id);
        }
        return failure;
    }

    /**
     * Withdraws a provider whose runtime hook faulted, together with every published provider that
     * requires it, through the same publication a disable uses: one snapshot swap, the all-session
     * prune and one {@code DEL}. Each is then stopped, dependents first, outside the administrative
     * mutex and without waiting for callbacks still running against an older snapshot; every one
     * ends FAILED, and one whose stop throws keeps that cleanup owed to a later disable. Nothing
     * restarts automatically. A provider no longer enabled when this runs (disabled, or already
     * failed by an earlier incident) is left as it is.
     */
    private void failProvider(String id, CapabilityFaults.Fault fault) {
        lifecycle.lock();
        try {
            Entry entry = byId.get(id);
            Set<String> withdrawn =
                    entry != null && entry.state == Extension.State.ENABLED
                            ? capabilityRuntime.withdraw(id)
                            : Set.of();
            if (withdrawn.isEmpty()) {
                faults.dropped(id, fault);
                return;
            }
            List<String> dependents = new ArrayList<>(withdrawn);
            dependents.remove(id);
            faults.failed(id, fault, dependents);
            stopAfterFault(List.copyOf(withdrawn), 0);
        } finally {
            lifecycle.unlock();
        }
    }

    /**
     * Stops {@code withdrawn} from {@code from} on, in order, and records each FAILED. An {@link
     * Error} from a stop is not a provider failure and still propagates, but only after that
     * provider is recorded FAILED with its cleanup owed and every later one has been stopped too.
     */
    private void stopAfterFault(List<String> withdrawn, int from) {
        if (from == withdrawn.size()) return;
        Entry entry = byId.get(withdrawn.get(from));
        boolean stopped = false;
        try {
            entry.extension.stop();
            stopped = true;
        } catch (RuntimeException | LinkageError e) {
            faults.stopFailed(entry.extension.id(), e);
        } finally {
            markFailed(entry, !stopped);
            stopAfterFault(withdrawn, from + 1);
        }
    }

    private static String extensionPoint(Extension extension) {
        return extension instanceof ServerExtension serverExtension
                ? serverExtension.extensionPoint()
                : null;
    }

    /**
     * Currently-recognized channel-mode flags: core's plus every currently-`ENABLED` extension's
     * (FR-043).
     */
    public Collection<ChannelMode> recognizedChannelModes(Collection<ChannelMode> coreModes) {
        java.util.List<ChannelMode> result = new java.util.ArrayList<>(coreModes);
        for (Extension extension : enabled()) {
            if (extension instanceof ServerExtension serverExtension) {
                result.addAll(serverExtension.contributedChannelModes());
            }
        }
        return result;
    }

    /**
     * Currently-recognized user-mode flags: core's plus every currently-`ENABLED` extension's
     * (FR-044).
     */
    public Collection<UserMode> recognizedUserModes(Collection<UserMode> coreModes) {
        java.util.List<UserMode> result = new java.util.ArrayList<>(coreModes);
        for (Extension extension : enabled()) {
            if (extension instanceof ServerExtension serverExtension) {
                result.addAll(serverExtension.contributedUserModes());
            }
        }
        return result;
    }

    /**
     * The provider capabilities currently offered: the published snapshot's, live, never cached
     * (FR-007/FR-035). Core cap-notify is not a provider and is not in it.
     */
    public Collection<Capability> offeredCapabilities() {
        return capabilityRuntime.snapshot().offeredNames().stream().map(Capability::new).toList();
    }

    public SupportedFeatures supportedFeatures() {
        return supportedFeatures;
    }

    /**
     * Invoked by the config load/reload path whenever configured lengths change (FR-056/FR-064).
     */
    public void updateConfiguredLengths(
            int nicknameMaxLength,
            int channelNameMaxLength,
            int topicMaxLength,
            int maxModesPerCommand) {
        supportedFeatures =
                supportedFeatures.withConfiguredLengths(
                        nicknameMaxLength,
                        channelNameMaxLength,
                        topicMaxLength,
                        maxModesPerCommand);
        refreshReplyLimits();
    }
}
