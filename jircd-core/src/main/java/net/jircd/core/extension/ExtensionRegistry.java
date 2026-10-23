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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import net.jircd.core.capability.Capability;
import net.jircd.core.config.HostmaskReplyBudget;
import net.jircd.core.extension.capability.CapabilityDescriptor;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.CapabilityRuntime;
import net.jircd.core.extension.capability.CapabilityServices;
import net.jircd.core.extension.capability.FaultReporter;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.extension.capability.ReplyBudgetCatalog;
import net.jircd.core.extension.capability.ReplyLimits;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.core.session.ChannelMode;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.UserMode;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import net.jircd.protocol.NumericReply;
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
  private volatile ServerContext serverContext;

  private volatile SupportedFeatures supportedFeatures = SupportedFeatures.defaults();

  private final CapabilityRuntime capabilityRuntime = new CapabilityRuntime();
  private final HookDispatcher hookDispatcher = new HookDispatcher(FaultReporter.logging());

  /**
   * Serializes enable and disable. It is not the registry monitor and no message path takes it, so
   * provider callbacks (start, register, stop) run while it is held without blocking delivery or
   * any registry read, and two lifecycle changes still never interleave.
   */
  private final ReentrantLock lifecycle = new ReentrantLock();

  /**
   * Commands each provider installed in core, so disable can remove exactly those. A record whose
   * removal failed stays here until a retry removes it.
   */
  private final Map<String, List<InstalledCommand>> installedCommands = new ConcurrentHashMap<>();

  /**
   * The exact handler instance a provider installed, so removal never touches a replacement. The
   * handler itself runs only while the current snapshot contains its provider.
   */
  private record InstalledCommand(Command command, CommandHandler handler) {}

  /** The currently published capability hooks; read-only, lifecycle stays with this registry. */
  public RuntimeSnapshot capabilitySnapshot() {
    return capabilityRuntime.snapshot();
  }

  /** The handle {@link CapabilityExtension}s receive through {@link ServerContext}. */
  public CapabilityServices capabilityServices() {
    return new CapabilityServices(capabilityRuntime::snapshot);
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
   * {@link ServerContext} itself references this registry, so the two are constructed independently
   * and wired together here rather than via constructor injection.
   */
  public void attachContext(ServerContext serverContext) {
    this.serverContext = serverContext;
    refreshReplyLimits();
  }

  /** Slot bounds are evaluated against the validated configuration, as startup and enable do. */
  private void refreshReplyLimits() {
    ServerContext context = serverContext;
    SupportedFeatures features = supportedFeatures;
    int serverBytes =
        context == null
            ? ReplyLimits.REFERENCE.serverBytes()
            : MessageSerializer.utf8Bytes(context.serverName().get());
    capabilityRuntime.setLimits(
        ReplyLimits.of(serverBytes, features.nicknameMaxLength(), features.channelNameMaxLength()));
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
    Entry existing = byId.putIfAbsent(extension.id(), new Entry(extension));
    if (existing != null && existing.extension != extension) {
      throw new IllegalArgumentException("Duplicate extension id: " + extension.id());
    }
  }

  /**
   * Finds every {@link CapabilityExtension}/{@link ServerExtension} on {@code classLoader} via
   * {@link ServiceLoader}.
   */
  public void discover(ClassLoader classLoader) {
    ServiceLoader.load(CapabilityExtension.class, classLoader).forEach(this::registerDiscovered);
    ServiceLoader.load(ServerExtension.class, classLoader).forEach(this::registerDiscovered);
  }

  /** A duplicate id is reported and skipped; it must not hide the extensions after it. */
  private void registerDiscovered(Extension extension) {
    try {
      register(extension);
    } catch (IllegalArgumentException e) {
      LOG.warn(
          "Ignoring discovered extension {}: {}", extension.getClass().getName(), e.getMessage());
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
   * change or a configuration reload: it would queue behind (or deadlock with) the lock they hold.
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
    // A cleanup a failed disable left unfinished (leftover handlers, a stop that failed, the
    // extension-point claim) is completed before the provider starts afresh.
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
        // Whatever failed, including an Error: a start that threw may have acquired resources
        // first, so stop runs (and must tolerate that) and the provider is recorded as failed.
        boolean stopFailed = stopQuietly(entry.extension);
        entry.state = Extension.State.FAILED;
        entry.cleanupPending = stopFailed || installedCommands.containsKey(id);
        // A claim is only kept while cleanup is unfinished.
        if (!entry.cleanupPending && point != null) extensionPointOwners.remove(point, id);
      }
    }
  }

  /**
   * Stages and validates a provider's hooks, installs its commands, and only then publishes the
   * hooks in one atomic step, so no reader ever sees hooks of an enable that fails; on any failure
   * nothing is left published or registered, apart from handlers core could not remove, which are
   * kept for retry and refuse to run.
   */
  private void publish(CapabilityExtension capability) {
    CapabilityDescriptor descriptor = stage(capability);
    RuntimeSnapshot next = capabilityRuntime.snapshot().with(descriptor);
    validateReplyBudget(next);
    List<HookRegistration> commands = commandsOf(descriptor);
    requireUnownedCommands(commands);
    List<InstalledCommand> done = new ArrayList<>();
    boolean published = false;
    try {
      installCommands(commands, done);
      capabilityRuntime.publish(descriptor);
      published = true;
    } finally {
      String id = descriptor.providerId();
      if (!done.isEmpty()) installedCommands.put(id, List.copyOf(done));
      if (!published) {
        // Any failure, including an Error, leaves neither a published hook nor a runnable handler.
        capabilityRuntime.withdraw(id);
        Throwable removal = removeInstalled(id);
        if (removal != null) LOG.warn("Rolling back commands of '{}' failed", id, removal);
      }
    }
  }

  private static List<HookRegistration> commandsOf(CapabilityDescriptor descriptor) {
    return descriptor.registrations().stream()
        .filter(registration -> registration.kind() == HookKind.COMMAND)
        .toList();
  }

  /** A provider may add commands but never replace one core or another extension owns. */
  private void requireUnownedCommands(List<HookRegistration> commands) {
    if (serverContext == null) return;
    for (HookRegistration registration : commands) {
      if (serverContext.commandRegistrar().isRegistered(registration.command())) {
        throw new IllegalArgumentException(
            "Command " + registration.command() + " is already handled and cannot be replaced");
      }
    }
  }

  private void installCommands(List<HookRegistration> commands, List<InstalledCommand> done) {
    if (serverContext == null) return;
    for (HookRegistration registration : commands) {
      // A command runs only while the published snapshot has its provider, so commands and hooks
      // become available, and stop being available, in the same atomic step.
      String providerId = registration.providerId();
      CommandHandler handler =
          hookDispatcher.guardedCommand(
              registration,
              () -> capabilityRuntime.snapshot().descriptor(providerId).isPresent(),
              this::refuseWithdrawn);
      serverContext.commandRegistrar().register(registration.command(), handler);
      done.add(new InstalledCommand(registration.command(), handler));
    }
  }

  /** A handler core could not remove answers as an unknown command once its provider is gone. */
  private void refuseWithdrawn(ClientSession session, Message message) {
    String command = message.rawCommand() != null ? message.rawCommand() : message.command().name();
    Replies.sendFitted(
        session,
        serverContext.serverName().get(),
        NumericReply.ERR_UNKNOWNCOMMAND,
        List.of(command),
        "Unknown command");
  }

  /**
   * Attempts every removal of the provider's handlers. A record whose removal failed stays for a
   * retry; the first failure is returned with the later distinct ones suppressed.
   */
  private Throwable removeInstalled(String id) {
    List<InstalledCommand> records = installedCommands.get(id);
    if (records == null) return null;
    Throwable first = null;
    List<InstalledCommand> remaining = new ArrayList<>();
    for (InstalledCommand installed : records) {
      try {
        serverContext.commandRegistrar().unregister(installed.command(), installed.handler());
      } catch (RuntimeException | LinkageError e) {
        first = accumulate(first, e);
        remaining.add(installed);
      }
    }
    if (remaining.isEmpty()) {
      installedCommands.remove(id);
    } else {
      installedCommands.put(id, List.copyOf(remaining));
    }
    return first;
  }

  /**
   * Keeps the first failure and suppresses later distinct ones (a repeat is not self-suppressed).
   */
  private static Throwable accumulate(Throwable first, Throwable next) {
    if (first == null) return next;
    if (first != next) first.addSuppressed(next);
    return first;
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

  /** The same bound checker the configuration loader uses, against the actual active slots. */
  private void validateReplyBudget(RuntimeSnapshot active) {
    ServerContext context = serverContext;
    if (context == null) return;
    SupportedFeatures features = supportedFeatures;
    HostmaskReplyBudget.requireFits(
        net.jircd.protocol.MessageSerializer.utf8Bytes(context.serverName().get()),
        features.nicknameMaxLength(),
        features.channelNameMaxLength(),
        ReplyBudgetCatalog.legacyCompatible().plus(active.replyBudgetCatalog()));
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

  /**
   * Disables the named extension. Cleanup completes fully or the provider is recorded FAILED:
   * withdrawn from every new capture, with any handler core could not remove refusing to run. A
   * FAILED provider whose cleanup is unfinished keeps its {@code extensionPoint} claim, so no other
   * provider can take the point while its resources may still be live; calling {@code disable}
   * again retries the cleanup, and the claim is released when that succeeds. Provider callbacks run
   * under the lifecycle lock only, never the registry monitor.
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
   * Withdraws the provider (which may refuse while a published dependent needs it), removes its
   * handlers and stops it. A clean run leaves it DISABLED and releases its extension-point claim; a
   * failure leaves it FAILED, withdrawn, with the claim and the unfinished work kept for a retry.
   * Returns the failure instead of throwing so callers decide.
   */
  private Throwable cleanUp(Entry entry) {
    String id = entry.extension.id();
    // Withdrawal comes first and may refuse (a published dependent); stop then runs outside any
    // snapshot, so callbacks already holding an older one finish against it.
    capabilityRuntime.withdraw(id);
    Throwable failure = removeInstalled(id);
    try {
      entry.extension.stop();
    } catch (RuntimeException | LinkageError e) {
      // Already withdrawn: it stays withdrawn, recorded as failed rather than half-enabled.
      failure = accumulate(failure, e);
    }
    entry.state = failure == null ? Extension.State.DISABLED : Extension.State.FAILED;
    entry.cleanupPending = failure != null;
    String point = extensionPoint(entry.extension);
    if (point != null && failure == null) {
      extensionPointOwners.remove(point, id);
    }
    return failure;
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

  /** The capability list currently offered — live, never cached (FR-007/FR-035). */
  public Collection<Capability> offeredCapabilities() {
    return enabled().stream()
        .filter(CapabilityExtension.class::isInstance)
        .map(e -> ((CapabilityExtension) e).providedCapability())
        .toList();
  }

  public SupportedFeatures supportedFeatures() {
    return supportedFeatures;
  }

  /** Invoked by the config load/reload path whenever configured lengths change (FR-056/FR-064). */
  public void updateConfiguredLengths(
      int nicknameMaxLength, int channelNameMaxLength, int topicMaxLength, int maxModesPerCommand) {
    supportedFeatures =
        supportedFeatures.withConfiguredLengths(
            nicknameMaxLength, channelNameMaxLength, topicMaxLength, maxModesPerCommand);
    refreshReplyLimits();
  }
}
