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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.WhowasHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtensionRegistryTest {

  private static class FakeServerExtension implements ServerExtension {
    private final String id;
    private final String extensionPoint;
    boolean started;
    boolean stopped;

    FakeServerExtension(String id, String extensionPoint) {
      this.id = id;
      this.extensionPoint = extensionPoint;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String extensionPoint() {
      return extensionPoint;
    }

    @Override
    public void start(ServerContext context) {
      started = true;
    }

    @Override
    public void stop() {
      stopped = true;
    }
  }

  private ExtensionRegistry newRegistry() {
    ExtensionRegistry registry = new ExtensionRegistry();
    NicknameRegistry nicknameRegistry = new NicknameRegistry();
    ChannelRegistry channelRegistry = new ChannelRegistry();
    ServerConfiguration configuration =
        new ServerConfiguration(
            Map.of(),
            Map.of(),
            List.of(),
            new ServerConfiguration.RateLimit(10, 1.0),
            List.of(),
            "test.jircd.local",
            ServerConfiguration.DEFAULT_NICKNAME_MAX_LENGTH,
            ServerConfiguration.DEFAULT_CHANNEL_NAME_MAX_LENGTH,
            ServerConfiguration.DEFAULT_TOPIC_MAX_LENGTH,
            true,
            ServerConfiguration.DEFAULT_MAX_MODES_PER_COMMAND,
            ServerConfiguration.DEFAULT_OPER_FAILURE_THRESHOLD,
            ServerConfiguration.DEFAULT_WHOWAS_HISTORY_SIZE,
            ServerConfiguration.DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS,
            ServerConfiguration.DEFAULT_SENDQ_LIMIT_BYTES,
            ServerConfiguration.DEFAULT_MAX_CONNECTIONS,
            ServerConfiguration.DEFAULT_MAX_CONNECTIONS_PER_IP,
            ServerConfiguration.DEFAULT_IPV6_CONNECTION_LIMIT_PREFIX,
            List.of());
    registry.attachContext(
        new ServerContext(
            nicknameRegistry,
            channelRegistry,
            registry,
            () -> "test.jircd.local",
            new ConfigurationReloader(
                Path.of("unused.yaml"),
                new ConfigurationLoader(Set.of(), Set.of()),
                registry,
                configuration),
            new DisconnectCleanup(
                nicknameRegistry, channelRegistry, new WhowasHistory(() -> 100), registry),
            (command, handler) -> {}));
    return registry;
  }

  @Test
  void invalidSendqReloadKeepsPreviousConfiguration(@TempDir Path directory) throws Exception {
    ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of());
    ServerConfiguration initial =
        loader.load(
            new ByteArrayInputStream("sendqLimitBytes: 32768".getBytes(StandardCharsets.UTF_8)));
    Path configPath = directory.resolve("reload.yaml");
    ConfigurationReloader reloader =
        new ConfigurationReloader(configPath, loader, newRegistry(), initial);
    Files.writeString(configPath, "sendqLimitBytes: 65536");
    ServerConfiguration valid = reloader.reload();
    assertThat(valid.sendqLimitBytes()).isEqualTo(65536);
    Files.writeString(configPath, "sendqLimitBytes: 16383");

    assertThatThrownBy(reloader::reload)
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("sendqLimitBytes");
    assertThat(reloader.current()).isSameAs(valid);
    assertThat(reloader.current().sendqLimitBytes()).isEqualTo(65536);
  }

  @Test
  void enableDisableLifecycle() {
    ExtensionRegistry registry = newRegistry();
    FakeServerExtension extension = new FakeServerExtension("cloak", "hostname-display");
    registry.register(extension);

    assertThat(registry.stateOf("cloak")).isEqualTo(Extension.State.DISABLED);
    registry.enable("cloak");
    assertThat(extension.started).isTrue();
    assertThat(registry.stateOf("cloak")).isEqualTo(Extension.State.ENABLED);

    registry.disable("cloak");
    assertThat(extension.stopped).isTrue();
    assertThat(registry.stateOf("cloak")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void failedStartTransitionsToFailedWithoutAffectingOtherExtensions() {
    ExtensionRegistry registry = newRegistry();
    FakeServerExtension healthy = new FakeServerExtension("admin", null);
    FakeServerExtension broken =
        new FakeServerExtension("broken", null) {
          @Override
          public void start(ServerContext context) {
            throw new RuntimeException("boom");
          }
        };
    registry.register(healthy);
    registry.register(broken);

    registry.enable("admin");
    assertThatThrownBy(() -> registry.enable("broken")).isInstanceOf(RuntimeException.class);

    assertThat(registry.stateOf("broken")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("admin")).isEqualTo(Extension.State.ENABLED);
  }

  @Test
  void conflictingExtensionPointClaimIsRejected() {
    ExtensionRegistry registry = newRegistry();
    FakeServerExtension first = new FakeServerExtension("cloak-a", "hostname-display");
    FakeServerExtension second = new FakeServerExtension("cloak-b", "hostname-display");
    registry.register(first);
    registry.register(second);

    registry.enable("cloak-a");
    assertThatThrownBy(() -> registry.enable("cloak-b"))
        .isInstanceOf(ExtensionRegistry.ExtensionPointConflictException.class);
    assertThat(registry.stateOf("cloak-b")).isEqualTo(Extension.State.DISABLED);

    registry.disable("cloak-a");
    registry.enable("cloak-b"); // now free to claim
    assertThat(registry.stateOf("cloak-b")).isEqualTo(Extension.State.ENABLED);
  }
}
