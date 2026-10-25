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
package net.jircd.serverextensions.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.jircd.core.config.ConfigurationLoader;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code EXTENSION} and {@code REHASH} report what a lifecycle change actually did: a provider that
 * fails to start is reported to the administrator, and a valid reload still applies the rest.
 */
class LifecycleOutcomeReportingTest {

  private static final String HEADER = "serverName: test.jircd.local\n";

  @TempDir Path directory;

  /** Stands in for the admin extension, whose enabled state authorizes the commands. */
  private static final class Simple implements ServerExtension {
    private final String id;
    private final boolean failsToStart;

    Simple(String id, boolean failsToStart) {
      this.id = id;
      this.failsToStart = failsToStart;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public void start(ServerContext context) {
      if (failsToStart) throw new IllegalStateException("no backing store");
    }

    @Override
    public void stop() {
      // Holds no resources.
    }
  }

  private final ExtensionRegistry registry = new ExtensionRegistry();

  private ClientSession administrator() {
    registry.register(new Simple(AdminExtension.ID, false));
    registry.register(new Simple("broken", true));
    registry.register(new Simple("good", false));
    registry.enable(AdminExtension.ID);
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    session.setNickname("root");
    session.grantAdministratorPrivilege();
    return session;
  }

  private static List<String> run(CommandHandler handler, ClientSession session, String line)
      throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
      session.attachWriter(writer);
      handler.handle(session, MessageParser.parse(line));
    }
    String text = out.toString(StandardCharsets.UTF_8);
    return text.isEmpty() ? List.of() : List.of(text.split("\r\n"));
  }

  @Test
  void rehashReportsAProviderThatFailedToStartAndAppliesTheRest() throws Exception {
    ClientSession root = administrator();
    ConfigurationLoader loader =
        new ConfigurationLoader(Set.of(), Set.of(AdminExtension.ID, "broken", "good"));
    Path file = directory.resolve("reload.yaml");
    Files.writeString(file, HEADER + "server-extensions: {broken: enabled, good: enabled}\n");
    ConfigurationReloader reloader =
        new ConfigurationReloader(
            file,
            loader,
            registry,
            loader.load(new ByteArrayInputStream(HEADER.getBytes(StandardCharsets.UTF_8))));

    List<String> lines =
        run(new RehashCommandHandler(reloader, registry, () -> "irc.example"), root, "REHASH");

    assertThat(lines).hasSize(2);
    assertThat(lines.getFirst()).contains(" 382 ");
    assertThat(lines.getLast())
        .isEqualTo(":irc.example NOTICE root :Rehash could not apply broken: no backing store");
    assertThat(registry.stateOf("good")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.stateOf("broken")).isEqualTo(Extension.State.FAILED);
  }

  @Test
  void extensionReportsTheStateTheToggleActuallyLeft() throws Exception {
    ClientSession root = administrator();
    ExtensionCommandHandler handler = new ExtensionCommandHandler(registry, () -> "irc.example");

    assertThat(run(handler, root, "EXTENSION ENABLE broken"))
        .containsExactly(":irc.example NOTICE root :Failed to toggle broken: no backing store");
    // Nothing was left to clean up, so the disable is a no-op: it says FAILED, not "disabled".
    assertThat(run(handler, root, "EXTENSION DISABLE broken"))
        .containsExactly(":irc.example NOTICE root :broken is now failed");
    assertThat(run(handler, root, "EXTENSION ENABLE good"))
        .containsExactly(":irc.example NOTICE root :good is now enabled");
    assertThat(run(handler, root, "EXTENSION ENABLE good"))
        .containsExactly(":irc.example NOTICE root :good is now enabled");
  }
}
