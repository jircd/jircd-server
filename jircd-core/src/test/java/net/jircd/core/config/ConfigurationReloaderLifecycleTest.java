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
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A REHASH validates the whole candidate provider graph before changing anything, enables
 * prerequisites before their dependents, applies a valid reload as one publication while holding
 * the lifecycle lock, and reports a provider that failed to start without undoing the rest.
 */
class ConfigurationReloaderLifecycleTest {

  private static final String HEADER = "serverName: test.jircd.local\n";

  @TempDir Path directory;

  private final List<String> started = Collections.synchronizedList(new ArrayList<>());

  private class Offer extends AbstractCapabilityExtension {
    private final String requires;
    private final boolean failsToStart;

    Offer(String id, String requires, boolean failsToStart) {
      super(id);
      this.requires = requires;
      this.failsToStart = failsToStart;
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      if (requires != null) registrar.requiresProviders(requires);
    }

    @Override
    public void start(ServerContext context) {
      if (failsToStart) throw new IllegalStateException("cannot start " + id());
      started.add(id());
    }
  }

  /** Fails while staging its registrations: a provider fault, not an invalid configuration. */
  private final class Unstageable extends Offer {
    Unstageable() {
      super("unstageable", null, false);
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      throw new IllegalStateException("cannot stage");
    }
  }

  /** Its first stop fails, leaving a cleanup for a later disable or reload to finish. */
  private final class Sticky extends Offer {
    private boolean failedOnce;

    Sticky() {
      super("sticky", null, false);
    }

    @Override
    public void stop() {
      if (!failedOnce) {
        failedOnce = true;
        throw new IllegalStateException("stuck");
      }
    }
  }

  /** Holds its start open until released, so a test can look at the state mid-reload. */
  private final class Slow extends Offer {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    Slow() {
      super("slow", null, false);
    }

    @Override
    public void start(ServerContext context) {
      entered.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
      super.start(context);
    }
  }

  private ConfigurationReloader reloader(ExtensionRegistry extensions, String yaml)
      throws Exception {
    ConfigurationLoader loader =
        new ConfigurationLoader(
            Set.of(
                "base",
                "dependent",
                "broken",
                "cyc-a",
                "cyc-b",
                "other",
                "slow",
                "leaning",
                "unstageable",
                "propped",
                "sticky"),
            Set.of());
    ServerConfiguration initial =
        loader.load(new ByteArrayInputStream(HEADER.getBytes(StandardCharsets.UTF_8)));
    Path file = directory.resolve("reload.yaml");
    Files.writeString(file, HEADER + yaml);
    return new ConfigurationReloader(file, loader, extensions, initial);
  }

  private ExtensionRegistry registry() {
    ExtensionRegistry extensions = new ExtensionRegistry();
    // Registered dependent-first, so only dependency ordering can start base before it.
    extensions.register(new Offer("dependent", "base", false));
    extensions.register(new Offer("base", null, false));
    extensions.register(new Offer("broken", null, true));
    return extensions;
  }

  @Test
  void enablesPrerequisitesBeforeTheirDependents() throws Exception {
    ExtensionRegistry extensions = registry();
    ConfigurationReloader reloader =
        reloader(extensions, "capabilities: {dependent: enabled, base: enabled}\n");

    ConfigurationReloader.ReloadOutcome outcome = reloader.reloadWithOutcome();

    assertThat(outcome.failures()).isEmpty();
    assertThat(started).containsExactly("base", "dependent");
    assertThat(extensions.stateOf("dependent")).isEqualTo(Extension.State.ENABLED);
  }

  @Test
  void aDependentWhosePrerequisiteWouldBeDisabledIsInvalidAndChangesNothing() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.enable("base");
    ConfigurationReloader reloader =
        reloader(
            extensions,
            "nicknameMaxLength: 20\ncapabilities: {dependent: enabled, base: disabled}\n");
    ServerConfiguration before = reloader.current();

    assertThatThrownBy(reloader::reload)
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("dependent")
        .hasMessageContaining("base");

    assertThat(reloader.current()).isSameAs(before);
    assertThat(extensions.stateOf("base")).isEqualTo(Extension.State.ENABLED);
    assertThat(extensions.stateOf("dependent")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void aPrerequisiteLeftOutOfTheFileCountsWhenItIsAlreadyEnabled() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.enable("base");
    ConfigurationReloader reloader = reloader(extensions, "capabilities: {dependent: enabled}\n");

    assertThat(reloader.reloadWithOutcome().failures()).isEmpty();
    assertThat(extensions.stateOf("dependent")).isEqualTo(Extension.State.ENABLED);
  }

  @Test
  void aProviderThatFailsToStartIsReportedAndTheRestStillApplies() throws Exception {
    ExtensionRegistry extensions = registry();
    ConfigurationReloader reloader =
        reloader(extensions, "capabilities: {broken: enabled, base: enabled}\n");

    ConfigurationReloader.ReloadOutcome outcome = reloader.reloadWithOutcome();

    assertThat(outcome.failures()).containsOnlyKeys("broken");
    assertThat(outcome.failures().get("broken")).contains("cannot start broken");
    assertThat(extensions.stateOf("broken")).isEqualTo(Extension.State.FAILED);
    assertThat(extensions.stateOf("base")).isEqualTo(Extension.State.ENABLED);
    assertThat(reloader.current()).isSameAs(outcome.configuration());
  }

  @Test
  void disablingAPrerequisiteThroughReloadAlsoWithdrawsItsDependent() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.enable("base");
    extensions.enable("dependent");
    ConfigurationReloader reloader =
        reloader(extensions, "capabilities: {base: disabled, dependent: disabled}\n");

    assertThat(reloader.reloadWithOutcome().failures()).isEmpty();
    assertThat(extensions.stateOf("base")).isEqualTo(Extension.State.DISABLED);
    assertThat(extensions.stateOf("dependent")).isEqualTo(Extension.State.DISABLED);
    assertThat(extensions.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void aDependencyCycleIsInvalidAndAnUnrelatedDisableIsNotApplied() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.register(new Offer("cyc-a", "cyc-b", false));
    extensions.register(new Offer("cyc-b", "cyc-a", false));
    extensions.register(new Offer("other", null, false));
    extensions.enable("other");
    ConfigurationReloader reloader =
        reloader(
            extensions,
            "nicknameMaxLength: 20\ncapabilities: {cyc-a: enabled, cyc-b: enabled, other: disabled}\n");
    ServerConfiguration before = reloader.current();

    assertThatThrownBy(reloader::reload)
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("cyclic");

    assertThat(reloader.current()).isSameAs(before);
    assertThat(extensions.stateOf("other")).isEqualTo(Extension.State.ENABLED);
    assertThat(extensions.stateOf("cyc-a")).isEqualTo(Extension.State.DISABLED);
    assertThat(extensions.stateOf("cyc-b")).isEqualTo(Extension.State.DISABLED);
    assertThat(extensions.capabilitySnapshot().providerIds()).containsExactly("other");
    assertThat(started).containsExactly("other");
  }

  @Test
  void aValidReloadIsOnePublicationSentAsOneDel() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.attachContext(
        new ServerContext(
            null,
            null,
            extensions,
            () -> "test.jircd.local",
            null,
            null,
            null,
            extensions.capabilityServices()));
    extensions.register(new Offer("other", null, false));
    extensions.enable("base");
    extensions.enable("other");
    Set<ClientSession> live = ConcurrentHashMap.newKeySet();
    extensions.attachLiveSessions(live);
    ClientSession observer =
        new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    observer.recordCapVersion(302);
    live.add(observer);
    ConfigurationReloader reloader =
        reloader(extensions, "capabilities: {base: disabled, other: disabled}\n");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(observer, out, TagRenderer.NONE)) {
      observer.attachWriter(writer);
      assertThat(reloader.reloadWithOutcome().failures()).isEmpty();
    }

    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo(":test.jircd.local CAP * DEL :base other\r\n");
  }

  @Test
  void theWholeReloadHoldsTheLifecycleSoNothingIsPublishedUntilEveryStartIsDone() throws Exception {
    ExtensionRegistry extensions = registry();
    Slow slow = new Slow();
    extensions.register(slow);
    extensions.register(new Offer("other", null, false));
    extensions.enable("base");
    extensions.enable("other");
    ConfigurationReloader reloader =
        reloader(extensions, "capabilities: {slow: enabled, other: disabled}\n");

    CompletableFuture<ConfigurationReloader.ReloadOutcome> reload =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return reloader.reloadWithOutcome();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    try {
      assertThat(slow.entered.await(5, TimeUnit.SECONDS)).isTrue();
      // Mid-reload: the disable of "other" is not published ahead of the enable.
      assertThat(extensions.capabilitySnapshot().providerIds()).contains("other", "base");
      CompletableFuture<Void> toggle = CompletableFuture.runAsync(() -> extensions.disable("base"));
      assertThatThrownBy(() -> toggle.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);
      slow.release.countDown();
      assertThat(reload.get(5, TimeUnit.SECONDS).failures()).isEmpty();
      toggle.get(5, TimeUnit.SECONDS);
    } finally {
      slow.release.countDown();
    }
    assertThat(extensions.capabilitySnapshot().providerIds()).containsExactly("slow");
  }

  @Test
  void aProviderThatFailsToStartTakesItsDependentInTheSameReloadWithIt() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.register(new Offer("leaning", "broken", false));
    extensions.enable("base");
    ConfigurationReloader reloader =
        reloader(extensions, "capabilities: {broken: enabled, leaning: enabled}\n");

    ConfigurationReloader.ReloadOutcome outcome = reloader.reloadWithOutcome();

    assertThat(outcome.failures()).containsOnlyKeys("broken", "leaning");
    assertThat(outcome.failures().get("leaning")).contains("requires 'broken'");
    assertThat(extensions.stateOf("leaning")).isEqualTo(Extension.State.FAILED);
    assertThat(started).containsExactly("base");
    assertThat(extensions.capabilitySnapshot().providerIds()).containsExactly("base");
  }

  @Test
  void aProviderWhoseStagingFailsIsIsolatedWithItsDependents() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.register(new Unstageable());
    extensions.register(new Offer("propped", "unstageable", false));
    ConfigurationReloader reloader =
        reloader(
            extensions, "capabilities: {unstageable: enabled, propped: enabled, base: enabled}\n");

    ConfigurationReloader.ReloadOutcome outcome = reloader.reloadWithOutcome();

    assertThat(outcome.failures()).containsOnlyKeys("unstageable", "propped");
    assertThat(extensions.stateOf("unstageable")).isEqualTo(Extension.State.FAILED);
    assertThat(extensions.stateOf("propped")).isEqualTo(Extension.State.FAILED);
    assertThat(extensions.stateOf("base")).isEqualTo(Extension.State.ENABLED);
    assertThat(started).containsExactly("base");
  }

  @Test
  void disablingAPrerequisiteOfADependentDesiredEnabledIsInvalid() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.enable("base");
    extensions.enable("dependent");
    ConfigurationReloader reloader =
        reloader(extensions, "capabilities: {base: disabled, dependent: enabled}\n");

    assertThatThrownBy(reloader::reload)
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("dependent");
    assertThat(extensions.capabilitySnapshot().providerIds())
        .containsExactlyInAnyOrder("base", "dependent");
  }

  @Test
  void aReloadFinishesTheCleanupAnEarlierFailedStopLeft() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.register(new Sticky());
    extensions.enable("sticky");
    assertThatThrownBy(() -> extensions.disable("sticky")).hasMessage("stuck");
    assertThat(extensions.stateOf("sticky")).isEqualTo(Extension.State.FAILED);

    ConfigurationReloader reloader = reloader(extensions, "capabilities: {sticky: disabled}\n");
    assertThat(reloader.reloadWithOutcome().failures()).isEmpty();
    assertThat(extensions.stateOf("sticky")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void aReloadEnablingAProviderWithAnUnfinishedCleanupFinishesItFirst() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.register(new Sticky());
    extensions.enable("sticky");
    assertThatThrownBy(() -> extensions.disable("sticky")).hasMessage("stuck");

    ConfigurationReloader reloader = reloader(extensions, "capabilities: {sticky: enabled}\n");
    assertThat(reloader.reloadWithOutcome().failures()).isEmpty();
    assertThat(extensions.stateOf("sticky")).isEqualTo(Extension.State.ENABLED);
    assertThat(extensions.capabilitySnapshot().providerIds()).containsExactly("sticky");
  }

  @Test
  void anIdTheRegistryDoesNotHoldIsLeftAloneAndChangesNothingElse() throws Exception {
    ExtensionRegistry extensions = registry();
    extensions.enable("base");
    // Known to the loader, never discovered here: there is no provider to enable or disable.
    ConfigurationReloader reloader =
        reloader(extensions, "capabilities: {other: enabled, slow: disabled, base: enabled}\n");

    assertThat(reloader.reloadWithOutcome().failures()).isEmpty();
    assertThat(extensions.stateOf("other")).isNull();
    assertThat(extensions.capabilitySnapshot().providerIds()).containsExactly("base");
    assertThat(started).containsExactly("base");
  }
}
