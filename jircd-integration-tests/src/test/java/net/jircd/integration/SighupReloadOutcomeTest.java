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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.server.SighupReloadHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A SIGHUP reload reports what it could not apply, as REHASH does, instead of claiming success. */
class SighupReloadOutcomeTest {

  private static final String HEADER = "serverName: test.jircd.local\n";

  @TempDir Path directory;

  /** A provider whose start always fails. */
  private static final class Broken extends AbstractCapabilityExtension {
    Broken() {
      super("broken");
    }

    @Override
    public void start(ServerContext context) {
      throw new IllegalStateException("cannot start broken");
    }
  }

  private ConfigurationReloader reloader(ExtensionRegistry registry, String yaml) throws Exception {
    ConfigurationLoader loader = new ConfigurationLoader(Set.of("broken"), Set.of());
    ServerConfiguration initial =
        loader.load(new ByteArrayInputStream(HEADER.getBytes(StandardCharsets.UTF_8)));
    Path file = directory.resolve("reload.yaml");
    Files.writeString(file, HEADER + yaml);
    return new ConfigurationReloader(file, loader, registry, initial);
  }

  @Test
  void aProviderThatEndsFailedIsLoggedAndTheReloadIsNotReportedAsClean() throws Exception {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(new Broken());
    ConfigurationReloader reloader = reloader(registry, "capabilities: {broken: enabled}\n");

    try (LogCapture logs = new LogCapture(SighupReloadHandler.class)) {
      SighupReloadHandler.reload(reloader);

      assertThat(registry.stateOf("broken")).isEqualTo(Extension.State.FAILED);
      assertThat(logs.messages())
          .anySatisfy(
              message -> assertThat(message).contains("broken").contains("cannot start broken"))
          .noneMatch("Configuration reloaded via SIGHUP"::equals);
    }
  }

  @Test
  void aCleanReloadIsReportedAsSuch() throws Exception {
    ExtensionRegistry registry = new ExtensionRegistry();
    ConfigurationReloader reloader = reloader(registry, "");

    try (LogCapture logs = new LogCapture(SighupReloadHandler.class)) {
      SighupReloadHandler.reload(reloader);

      assertThat(logs.messages()).containsExactly("Configuration reloaded via SIGHUP");
    }
  }
}
