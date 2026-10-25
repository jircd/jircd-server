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

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import net.jircd.core.extension.ExtensionRegistry;

/**
 * The core, manually-triggered reload operation (research.md "Configuration reload mechanism") —
 * deliberately not automatic file-watching; it only runs when invoked by a trigger ({@code SIGHUP},
 * {@code REHASH}). On success, reconciles the result against {@link ExtensionRegistry}; on failure,
 * the previously-active configuration is left untouched.
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

  private final Path configPath;
  private final ConfigurationLoader loader;
  private final ExtensionRegistry extensionRegistry;
  private final String runningServerName;
  private volatile ServerConfiguration current;

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

  public Path configPath() {
    return configPath;
  }

  /**
   * Re-reads and re-validates the configuration file, reconciling extension state on success.
   * Serialized, so validation, reconciliation, publication of {@link #current()} and the advertised
   * lengths happen as one ordered operation per reload. Listener settings (including backlog) are
   * validated and published here, but sockets are only bound at application startup; applying
   * listener changes requires a server restart.
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
    // Checked before the reload monitor is taken, so a callback never queues behind a reload that
    // is itself waiting for the lifecycle lock the callback holds.
    extensionRegistry.requireNoLifecycleCallback();
    return reloadSerialized();
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
  private synchronized ReloadOutcome reloadSerialized() throws ConfigurationException, IOException {
    ServerConfiguration reloaded;
    try (InputStream in = new FileInputStream(configPath.toFile())) {
      reloaded = loader.load(in);
    }
    loader.checkReplyBudgets(
        runningServerName, reloaded.nicknameMaxLength(), reloaded.channelNameMaxLength());
    ExtensionRegistry.DesiredState desired =
        new ExtensionRegistry.DesiredState(
            reloaded.capabilityStates(),
            reloaded.serverExtensionStates(),
            reloaded.nicknameMaxLength(),
            reloaded.channelNameMaxLength(),
            reloaded.topicMaxLength(),
            reloaded.maxModesPerCommand());
    Map<String, String> failures;
    try {
      failures = extensionRegistry.applyDesiredState(desired, () -> this.current = reloaded);
    } catch (ExtensionRegistry.InvalidDesiredStateException e) {
      throw new ConfigurationException(e.getMessage());
    }
    return new ReloadOutcome(reloaded, failures);
  }
}
