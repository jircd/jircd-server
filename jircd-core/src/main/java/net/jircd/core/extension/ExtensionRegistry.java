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

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import net.jircd.core.capability.Capability;
import net.jircd.core.session.ChannelMode;
import net.jircd.core.session.UserMode;
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

    Entry(Extension extension) {
      this.extension = extension;
    }
  }

  private final Map<String, Entry> byId = new ConcurrentHashMap<>();
  private final Map<String, String> extensionPointOwners = new ConcurrentHashMap<>();
  private volatile ServerContext serverContext;

  private volatile SupportedFeatures supportedFeatures = SupportedFeatures.defaults();

  /**
   * {@link ServerContext} itself references this registry, so the two are constructed independently
   * and wired together here rather than via constructor injection.
   */
  public void attachContext(ServerContext serverContext) {
    this.serverContext = serverContext;
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
    byId.putIfAbsent(extension.id(), new Entry(extension));
  }

  /**
   * Finds every {@link CapabilityExtension}/{@link ServerExtension} on {@code classLoader} via
   * {@link ServiceLoader}.
   */
  public void discover(ClassLoader classLoader) {
    ServiceLoader.load(CapabilityExtension.class, classLoader).forEach(this::register);
    ServiceLoader.load(ServerExtension.class, classLoader).forEach(this::register);
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

  /** Enables the named extension, rejecting a conflicting {@code extensionPoint} claim (FR-012). */
  public synchronized void enable(String id) {
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
    try {
      entry.extension.start(serverContext);
      entry.state = Extension.State.ENABLED;
      if (point != null) {
        extensionPointOwners.put(point, id);
      }
    } catch (RuntimeException e) {
      entry.state = Extension.State.FAILED;
      LOG.warn("Extension '{}' failed to start", id, e);
      throw e;
    }
  }

  /** Disables the named extension, releasing any {@code extensionPoint} it held. */
  public synchronized void disable(String id) {
    Entry entry = byId.get(id);
    if (entry == null) {
      throw new IllegalArgumentException("Unknown extension id: " + id);
    }
    if (entry.state != Extension.State.ENABLED) {
      return;
    }
    entry.extension.stop();
    entry.state = Extension.State.DISABLED;
    String point = extensionPoint(entry.extension);
    if (point != null) {
      extensionPointOwners.remove(point, id);
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
  }
}
