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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.CommandRegistrar;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reload failure paths: a pending cleanup survives a failed re-enable, a command moves from a
 * withdrawn owner to a newly enabled one within one reload, and a batch whose start escapes is
 * rolled back dependents first.
 */
class ConfigurationReloaderFailurePathTest {

  private static final String HEADER = "serverName: test.jircd.local\n";

  @TempDir Path directory;

  private final List<String> events = Collections.synchronizedList(new ArrayList<>());
  private final Map<Command, CommandHandler> handlers =
      Collections.synchronizedMap(new EnumMap<>(Command.class));
  private final ExtensionRegistry registry = new ExtensionRegistry();

  /** An escape a provider's start can throw that no reload catches. */
  private static final class StartError extends Error {
    StartError() {
      super("start escaped");
    }
  }

  /** Records start and stop; its stop, staging and start can each be made to fail. */
  private final class Probe extends AbstractCapabilityExtension {
    private final String requires;
    private final Command command;
    volatile boolean stopFails;
    volatile boolean stagingFails;
    volatile boolean startEscapes;
    volatile boolean startFails;

    Probe(String id, String requires, Command command) {
      super(id);
      this.requires = requires;
      this.command = command;
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      if (stagingFails) throw new IllegalStateException("cannot stage " + id());
      if (requires != null) registrar.requiresProviders(requires);
      if (command != null) registrar.command(command, (s, m) -> events.add("handled by " + id()));
    }

    @Override
    public void start(ServerContext context) {
      if (startEscapes) throw new StartError();
      if (startFails) throw new IllegalStateException();
      events.add("start " + id());
    }

    @Override
    public void stop() {
      events.add("stop " + id());
      if (stopFails) throw new IllegalStateException("stuck " + id());
    }
  }

  private void attachContext() {
    CommandRegistrar commands =
        new CommandRegistrar() {
          @Override
          public void register(Command command, CommandHandler handler) {
            handlers.put(command, handler);
          }

          @Override
          public boolean isRegistered(Command command) {
            return handlers.containsKey(command);
          }

          @Override
          public void unregister(Command command, CommandHandler installed) {
            handlers.remove(command, installed);
          }
        };
    registry.attachContext(
        new ServerContext(
            null,
            null,
            registry,
            () -> "test.jircd.local",
            null,
            null,
            commands,
            registry.capabilityServices()));
  }

  private Probe register(Probe probe) {
    registry.register(probe);
    return probe;
  }

  private ConfigurationReloader reloader(String yaml) throws Exception {
    ConfigurationLoader loader = new ConfigurationLoader(Set.of("a", "b", "c", "fickle"), Set.of());
    ServerConfiguration initial =
        loader.load(new ByteArrayInputStream(HEADER.getBytes(StandardCharsets.UTF_8)));
    Path file = directory.resolve("reload.yaml");
    Files.writeString(file, HEADER + yaml);
    return new ConfigurationReloader(file, loader, registry, initial);
  }

  @Test
  void aFailedReEnableKeepsTheCleanupAnEarlierFailedStopLeftForTheNextDisable() throws Exception {
    Probe fickle = register(new Probe("fickle", null, null));
    registry.enable("fickle");
    fickle.stopFails = true;
    assertThatThrownBy(() -> registry.disable("fickle")).hasMessage("stuck fickle");
    fickle.stopFails = false;
    fickle.stagingFails = true;

    assertThat(reloader("capabilities: {fickle: enabled}\n").reloadWithOutcome().failures())
        .containsOnlyKeys("fickle");
    assertThat(registry.stateOf("fickle")).isEqualTo(Extension.State.FAILED);

    // The cleanup the failed stop left is still owed: this disable must finish it.
    events.clear();
    registry.disable("fickle");
    assertThat(events).containsExactly("stop fickle");
    assertThat(registry.stateOf("fickle")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void aReloadMovesACommandFromAWithdrawnOwnerToANewlyEnabledOne() throws Exception {
    attachContext();
    register(new Probe("a", null, Command.TAGMSG));
    register(new Probe("b", null, Command.TAGMSG));
    registry.enable("a");

    ConfigurationReloader.ReloadOutcome outcome =
        reloader("capabilities: {a: disabled, b: enabled}\n").reloadWithOutcome();

    assertThat(outcome.failures()).isEmpty();
    assertThat(registry.stateOf("a")).isEqualTo(Extension.State.DISABLED);
    assertThat(registry.stateOf("b")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("b");
    ClientSession session = new ClientSession("c1", "host", RateLimitBucket.withDefaults());
    registry
        .capabilityCommand(Command.TAGMSG)
        .orElseThrow()
        .handle(session, Message.of(Command.TAGMSG, "#c"));
    assertThat(events).contains("handled by b").doesNotContain("handled by a");
  }

  @Test
  void aStartThatEscapesRollsTheBatchBackDependentsFirst() throws Exception {
    register(new Probe("a", null, null));
    register(new Probe("b", "a", null));
    register(new Probe("c", null, null)).startEscapes = true;

    ConfigurationReloader reloader =
        reloader("capabilities: {a: enabled, b: enabled, c: enabled}\n");
    assertThatThrownBy(reloader::reload).isInstanceOf(StartError.class);

    assertThat(events).containsExactly("start a", "start b", "stop c", "stop b", "stop a");
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    assertThat(registry.stateOf("a")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("b")).isEqualTo(Extension.State.FAILED);
  }

  @Test
  void aWithdrawnProviderWhoseStopFailsIsReportedAndStaysWithdrawn() throws Exception {
    Probe a = register(new Probe("a", null, null));
    registry.enable("a");
    a.stopFails = true;

    assertThat(reloader("capabilities: {a: disabled}\n").reloadWithOutcome().failures())
        .containsEntry("a", "stuck a");
    assertThat(registry.stateOf("a")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void aRetriedCleanupThatFailsAgainIsReported() throws Exception {
    Probe a = register(new Probe("a", null, null));
    registry.enable("a");
    a.stopFails = true;
    assertThatThrownBy(() -> registry.disable("a")).hasMessage("stuck a");

    assertThat(reloader("capabilities: {a: disabled}\n").reloadWithOutcome().failures())
        .containsEntry("a", "stuck a");
    assertThat(registry.stateOf("a")).isEqualTo(Extension.State.FAILED);
  }

  @Test
  void aReEnableWhoseLeftoverCleanupFailsAgainIsReportedAndNeverStarts() throws Exception {
    Probe a = register(new Probe("a", null, null));
    registry.enable("a");
    a.stopFails = true;
    assertThatThrownBy(() -> registry.disable("a")).hasMessage("stuck a");
    events.clear();

    assertThat(reloader("capabilities: {a: enabled}\n").reloadWithOutcome().failures())
        .containsEntry("a", "stuck a");
    assertThat(events).containsExactly("stop a");
    assertThat(registry.stateOf("a")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void aStartFailureWithoutAMessageIsNamedByTypeAndAFailingStopKeepsTheCleanupOwed()
      throws Exception {
    Probe c = register(new Probe("c", null, null));
    c.startFails = true;
    c.stopFails = true;

    assertThat(reloader("capabilities: {c: enabled}\n").reloadWithOutcome().failures())
        .containsEntry("c", "IllegalStateException");
    assertThat(registry.stateOf("c")).isEqualTo(Extension.State.FAILED);

    // The rollback's stop failed, so the next disable still owes and runs a stop.
    c.stopFails = false;
    events.clear();
    registry.disable("c");
    assertThat(events).containsExactly("stop c");
    assertThat(registry.stateOf("c")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void aDependentWhoseStopFailsWhenItsPrerequisiteIsDisabledEndsFailedAlone() {
    register(new Probe("a", null, null));
    Probe b = register(new Probe("b", "a", null));
    registry.enable("a");
    registry.enable("b");
    b.stopFails = true;

    registry.disable("a");

    assertThat(registry.stateOf("a")).isEqualTo(Extension.State.DISABLED);
    assertThat(registry.stateOf("b")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }
}
