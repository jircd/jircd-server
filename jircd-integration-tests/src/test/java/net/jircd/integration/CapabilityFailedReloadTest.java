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

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.server.SighupReloadHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #102 explicit recovery over reloads: a provider FAILED by a hook fault, and the hard dependent
 * that failed with it, stay FAILED across a REHASH or SIGHUP that changes something else. A reload
 * retries a FAILED provider only when its own {@code capabilities} entry differs from the last
 * applied configuration; {@code EXTENSION ENABLE} always retries. The probes are discovered like
 * installed modules, so the configuration file can name them.
 */
class CapabilityFailedReloadTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  /** The live instances, since discovery constructs them. */
  private static final AtomicReference<Base> BASE = new AtomicReference<>();

  private static final AtomicReference<Dependent> DEPENDENT = new AtomicReference<>();

  /** Tags messages to recipients that negotiated it; its hook throws while {@link #faulting}. */
  public static final class Base extends AbstractCapabilityExtension {
    volatile boolean faulting;
    volatile CountDownLatch stopped = new CountDownLatch(1);
    final AtomicInteger starts = new AtomicInteger();

    public Base() {
      super("reload-base");
      BASE.set(this);
    }

    @Override
    public void start(ServerContext context) {
      starts.incrementAndGet();
    }

    @Override
    public void stop() {
      stopped.countDown();
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onOutboundTags(
          Set.of("reload-base"),
          16,
          message -> {
            if (faulting) throw new IllegalStateException("fault");
            return Map.of("reload-base", "1");
          });
    }
  }

  /** Requires {@link Base}. */
  public static final class Dependent extends AbstractCapabilityExtension {
    final AtomicInteger starts = new AtomicInteger();

    public Dependent() {
      super("reload-dependent");
      DEPENDENT.set(this);
    }

    @Override
    public void start(ServerContext context) {
      starts.incrementAndGet();
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.requiresProviders("reload-base");
    }
  }

  private TestServer server;
  private ExtensionRegistry registry;

  @BeforeEach
  void resetProbes() {
    BASE.set(null);
    DEPENDENT.set(null);
  }

  private static String yaml(String base, String dependent, int topicMaxLength) {
    StringBuilder yaml = new StringBuilder(TestServer.adminEnabledYaml());
    yaml.append("topicMaxLength: ").append(topicMaxLength).append('\n');
    yaml.append("capabilities:\n");
    if (base != null) yaml.append("  reload-base: ").append(base).append('\n');
    if (dependent != null) yaml.append("  reload-dependent: ").append(dependent).append('\n');
    return yaml.toString();
  }

  private void write(String yaml) throws Exception {
    Files.writeString(server.configPath, TestServer.baseYaml() + yaml);
  }

  private static RawIrcClient admin(TestServer server) throws Exception {
    RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
    root.registerAndAwaitWelcome("root", "root");
    root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
    root.readUntil("381", TIMEOUT);
    return root;
  }

  private static RawIrcClient client(TestServer server, String nick, String caps) throws Exception {
    RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
    client.send("CAP LS 302");
    client.send("CAP REQ :" + caps);
    client.send("CAP END");
    client.registerAndAwaitWelcome(nick, nick);
    return client;
  }

  private static List<String> capLines(RawIrcClient client) throws Exception {
    return client.readThroughPong(TIMEOUT).stream().filter(l -> l.contains(" CAP ")).toList();
  }

  private static void rehash(RawIrcClient root) throws Exception {
    root.send("REHASH");
    root.readUntil("382", TIMEOUT);
    root.readThroughPong(TIMEOUT);
  }

  /** Faults the base's hook through a delivery and waits for the withdrawal of both. */
  private void failBoth(RawIrcClient alice, RawIrcClient bob) throws Exception {
    BASE.get().faulting = true;
    bob.send("PRIVMSG alice :hi");
    List<String> seen = Withdrawals.linesThrough(alice, "PRIVMSG alice");
    Withdrawals.awaitFailed(registry, "reload-base", BASE.get().stopped);
    assertThat(registry.stateOf("reload-dependent")).isEqualTo(Extension.State.FAILED);
    // The DEL may come before or after the message whose preparation faulted.
    seen.addAll(alice.readThroughPong(TIMEOUT));
    assertThat(seen.stream().filter(l -> l.contains(" CAP ")))
        .containsExactly(":test.jircd.local CAP alice DEL :reload-base reload-dependent");
    BASE.get().faulting = false;
  }

  @Test
  void unrelatedReloadsLeaveFailedProvidersFailedAndAChangedEntryRetriesOnlyThatProvider()
      throws Exception {
    try (TestServer started =
            DiscoveredProbes.start(yaml("enabled", "enabled", 390), Base.class, Dependent.class);
        RawIrcClient root = admin(started);
        RawIrcClient alice = client(started, "alice", "reload-base reload-dependent");
        RawIrcClient bob = client(started, "bob", "")) {
      server = started;
      registry = server.application.extensionRegistry();
      assertThat(registry.stateOf("reload-base")).isEqualTo(Extension.State.ENABLED);
      failBoth(alice, bob);
      int baseStarts = BASE.get().starts.get();
      int dependentStarts = DEPENDENT.get().starts.get();

      // An unrelated SIGHUP: neither entry changed.
      write(yaml("enabled", "enabled", 380));
      SighupReloadHandler.reload(server.application.reloader());
      assertThat(server.application.reloader().current().topicMaxLength()).isEqualTo(380);
      // An unrelated REHASH.
      write(yaml("enabled", "enabled", 370));
      rehash(root);
      assertThat(server.application.reloader().current().topicMaxLength()).isEqualTo(370);

      assertThat(registry.stateOf("reload-base")).isEqualTo(Extension.State.FAILED);
      assertThat(registry.stateOf("reload-dependent")).isEqualTo(Extension.State.FAILED);
      assertThat(BASE.get().starts).hasValue(baseStarts);
      assertThat(DEPENDENT.get().starts).hasValue(dependentStarts);
      assertThat(capLines(alice)).isEmpty();

      // The base's own entry changes (absent, then enabled again): that is a retry of the base
      // alone; the dependent's entry is unchanged, so it stays FAILED.
      write(yaml(null, "enabled", 370));
      rehash(root);
      assertThat(registry.stateOf("reload-base")).isEqualTo(Extension.State.FAILED);
      write(yaml("enabled", "enabled", 370));
      rehash(root);
      assertThat(registry.stateOf("reload-base")).isEqualTo(Extension.State.ENABLED);
      assertThat(BASE.get().starts).hasValue(baseStarts + 1);
      assertThat(registry.stateOf("reload-dependent")).isEqualTo(Extension.State.FAILED);
      assertThat(capLines(alice)).containsExactly(":test.jircd.local CAP alice NEW reload-base");

      // The dependent's own entry changes, through SIGHUP this time: it is retried.
      write(yaml("enabled", "disabled", 370));
      SighupReloadHandler.reload(server.application.reloader());
      write(yaml("enabled", "enabled", 370));
      SighupReloadHandler.reload(server.application.reloader());
      assertThat(registry.stateOf("reload-dependent")).isEqualTo(Extension.State.ENABLED);
      assertThat(DEPENDENT.get().starts).hasValue(dependentStarts + 1);
      assertThat(capLines(alice))
          .containsExactly(":test.jircd.local CAP alice NEW reload-dependent");
      // Nothing restores an earlier negotiation.
      alice.send("CAP LIST");
      assertThat(alice.readUntil("CAP alice LIST", TIMEOUT)).endsWith("LIST :");
    }
  }

  @Test
  void extensionEnableAlwaysRetriesAFailedProviderAndItsDependentNeedsItsOwn() throws Exception {
    try (TestServer started =
            DiscoveredProbes.start(yaml("enabled", "enabled", 390), Base.class, Dependent.class);
        RawIrcClient root = admin(started);
        RawIrcClient alice = client(started, "alice", "reload-base reload-dependent");
        RawIrcClient bob = client(started, "bob", "")) {
      server = started;
      registry = server.application.extensionRegistry();
      failBoth(alice, bob);

      root.send("EXTENSION ENABLE reload-base");
      assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("reload-base is now enabled");
      assertThat(registry.stateOf("reload-dependent")).isEqualTo(Extension.State.FAILED);
      root.send("EXTENSION ENABLE reload-dependent");
      assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("reload-dependent is now enabled");
      assertThat(capLines(alice))
          .containsExactly(
              ":test.jircd.local CAP alice NEW reload-base",
              ":test.jircd.local CAP alice NEW reload-dependent");

      // A later unrelated reload keeps them as they are now: enabled, as the file asks.
      write(yaml("enabled", "enabled", 380));
      rehash(root);
      assertThat(registry.stateOf("reload-base")).isEqualTo(Extension.State.ENABLED);
      assertThat(registry.stateOf("reload-dependent")).isEqualTo(Extension.State.ENABLED);
      assertThat(capLines(alice)).isEmpty();
    }
  }
}
