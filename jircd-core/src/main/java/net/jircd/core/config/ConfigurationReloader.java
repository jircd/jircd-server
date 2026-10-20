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
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core, manually-triggered reload operation (research.md "Configuration reload mechanism") —
 * deliberately not automatic file-watching; it only runs when invoked by a trigger ({@code SIGHUP},
 * {@code REHASH}). On success, reconciles the result against {@link ExtensionRegistry}; on failure,
 * the previously-active configuration is left untouched.
 */
public final class ConfigurationReloader {

  private static final Logger LOG = LoggerFactory.getLogger(ConfigurationReloader.class);

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
   */
  public synchronized ServerConfiguration reload() throws ConfigurationException, IOException {
    ServerConfiguration reloaded;
    try (InputStream in = new FileInputStream(configPath.toFile())) {
      reloaded = loader.load(in);
    }
    ConfigurationLoader.validateReplyBudgets(
        runningServerName, reloaded.nicknameMaxLength(), reloaded.channelNameMaxLength());
    reconcile(reloaded);
    this.current = reloaded;
    extensionRegistry.updateConfiguredLengths(
        reloaded.nicknameMaxLength(),
        reloaded.channelNameMaxLength(),
        reloaded.topicMaxLength(),
        reloaded.maxModesPerCommand());
    return reloaded;
  }

  private void reconcile(ServerConfiguration configuration) {
    reconcileSection(configuration.capabilityStates());
    reconcileSection(configuration.serverExtensionStates());
  }

  private void reconcileSection(java.util.Map<String, Boolean> desiredStates) {
    for (var entry : desiredStates.entrySet()) {
      String id = entry.getKey();
      boolean desiredEnabled = entry.getValue();
      Extension.State state = extensionRegistry.stateOf(id);
      boolean currentlyEnabled = state == Extension.State.ENABLED;
      try {
        if (desiredEnabled && !currentlyEnabled) {
          extensionRegistry.enable(id);
        } else if (!desiredEnabled && currentlyEnabled) {
          extensionRegistry.disable(id);
        }
      } catch (RuntimeException e) {
        LOG.warn("Failed to reconcile extension '{}' to desired state {}", id, desiredEnabled, e);
      }
    }
  }
}
