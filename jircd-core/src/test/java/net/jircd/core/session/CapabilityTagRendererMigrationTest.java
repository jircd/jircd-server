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
package net.jircd.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.capability.Capability;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import org.junit.jupiter.api.Test;

/**
 * Migrated providers publish typed tag hooks and are never also asked through the legacy method.
 */
class CapabilityTagRendererMigrationTest {

  private static class Provider implements CapabilityExtension {
    final AtomicInteger hookCalls = new AtomicInteger();
    final AtomicInteger legacyCalls = new AtomicInteger();
    final boolean migrated;
    final boolean failing;

    Provider(boolean migrated, boolean failing) {
      this.migrated = migrated;
      this.failing = failing;
    }

    @Override
    public String id() {
      return "tagger";
    }

    @Override
    public void start(ServerContext context) {
      // No resources.
    }

    @Override
    public void stop() {
      // No resources.
    }

    @Override
    public Capability providedCapability() {
      return new Capability("tagger");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      if (!migrated) return;
      registrar.onOutboundTags(
          Set.of("t"),
          16,
          message -> {
            hookCalls.incrementAndGet();
            if (failing) throw new IllegalStateException("boom");
            return Map.of("t", "hook");
          });
    }

    @Override
    public Map<String, String> contributeTags(OutboundMessage message) {
      legacyCalls.incrementAndGet();
      return Map.of("t", "legacy");
    }
  }

  private static ClientSession recipient(String name) {
    ClientSession session = new ClientSession(name, "host", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(List.of("tagger"));
    return session;
  }

  private static ExtensionRegistry registryWith(Provider provider) {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(provider);
    registry.enable("tagger");
    return registry;
  }

  @Test
  void migratedProviderIsEvaluatedOncePerEnvelopeAndNeverThroughTheLegacyMethod() {
    Provider provider = new Provider(true, false);
    CapabilityTagRenderer renderer = new CapabilityTagRenderer(registryWith(provider));
    OutboundMessage envelope = OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi");
    for (int i = 0; i < 5; i++) {
      TagRenderer.PreparedTags prepared = renderer.prepare(recipient("r" + i), envelope);
      // Drain-time rendering reads the shared immutable result; it calls no provider code.
      assertThat(prepared.tags().get()).containsExactly(Map.entry("t", "hook"));
    }
    assertThat(provider.hookCalls).hasValue(1);
    assertThat(provider.legacyCalls).hasValue(0);
  }

  @Test
  void unmigratedProviderKeepsTheLegacyPath() {
    Provider provider = new Provider(false, false);
    CapabilityTagRenderer renderer = new CapabilityTagRenderer(registryWith(provider));
    OutboundMessage envelope = OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi");
    assertThat(renderer.render(recipient("r"), envelope)).containsExactly(Map.entry("t", "legacy"));
    assertThat(provider.hookCalls).hasValue(0);
  }

  @Test
  void aFaultingHookContributesNothingAndLeavesOtherOutputIntact() {
    Provider provider = new Provider(true, true);
    CapabilityTagRenderer renderer = new CapabilityTagRenderer(registryWith(provider));
    OutboundMessage envelope =
        OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi", Map.of("+keep", "me"));
    ClientSession session = recipient("r");
    session.replaceNegotiatedCapabilities(List.of("tagger", "message-tags"));
    assertThat(renderer.render(session, envelope)).containsExactly(Map.entry("+keep", "me"));
    assertThat(provider.legacyCalls).hasValue(0);
  }

  @Test
  void disablingWithdrawsNewCapturesButCapturedWorkKeepsItsTags() {
    Provider provider = new Provider(true, false);
    ExtensionRegistry registry = registryWith(provider);
    CapabilityTagRenderer renderer = new CapabilityTagRenderer(registry);
    OutboundMessage envelope = OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi");
    TagRenderer.PreparedTags captured = renderer.prepare(recipient("a"), envelope);
    registry.disable("tagger");
    assertThat(captured.tags().get()).containsExactly(Map.entry("t", "hook"));
    assertThat(renderer.render(recipient("b"), OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "x")))
        .isEmpty();
  }

  @Test
  void hooksThatAreIndividuallyValidButOverflowTogetherLoseTheLaterOneNotTheMessage() {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(bulky("aaa", "a"));
    registry.register(bulky("bbb", "b"));
    registry.enable("aaa");
    registry.enable("bbb");
    ClientSession session = new ClientSession("r", "host", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(List.of("aaa", "bbb"));
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(session, out, new CapabilityTagRenderer(registry), 1 << 20)) {
      writer.enqueue(OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "kept"));
    }
    String wire = out.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(wire)
        .endsWith(" :n!u@h PRIVMSG #c kept\r\n")
        .contains("a=")
        .doesNotContain(";b=")
        .doesNotContain("@b=");
  }

  private static CapabilityExtension bulky(String id, String key) {
    return new Provider(true, false) {
      @Override
      public String id() {
        return id;
      }

      @Override
      public Capability providedCapability() {
        return new Capability(id);
      }

      @Override
      public void register(CapabilityRegistrar registrar) {
        registrar.onOutboundTags(Set.of(key), 4000, message -> Map.of(key, "x".repeat(3000)));
      }
    };
  }

  @Test
  void aTypedContributionThatOverflowsWithALegacyOneIsDroppedNotTheMessage() {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(
        new Provider(false, false) {
          @Override
          public String id() {
            return "legacy";
          }

          @Override
          public Capability providedCapability() {
            return new Capability("legacy");
          }

          @Override
          public Map<String, String> contributeTags(OutboundMessage message) {
            return Map.of("msgid", "m".repeat(2000));
          }
        });
    registry.register(bulky("typed", "t"));
    registry.enable("legacy");
    registry.enable("typed");
    ClientSession session = new ClientSession("r", "host", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(List.of("legacy", "typed"));
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(session, out, new CapabilityTagRenderer(registry), 1 << 20)) {
      writer.enqueue(OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "kept"));
    }
    assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8))
        .endsWith(" :n!u@h PRIVMSG #c kept\r\n")
        .contains("msgid=")
        .doesNotContain("t=x");
  }

  private static Provider legacy(String id, java.util.function.Supplier<Map<String, String>> tags) {
    return new Provider(false, false) {
      @Override
      public String id() {
        return id;
      }

      @Override
      public Capability providedCapability() {
        return new Capability(id);
      }

      @Override
      public Map<String, String> contributeTags(OutboundMessage message) {
        return tags.get();
      }
    };
  }

  private static String wireOf(ExtensionRegistry registry, List<String> negotiated) {
    ClientSession session = new ClientSession("r", "host", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(negotiated);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(session, out, new CapabilityTagRenderer(registry), 1 << 20)) {
      writer.enqueue(OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "kept"));
    }
    return out.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  void aLegacyProviderThatThrowsOrReturnsNullContributesNothingAndTheMessageStillGoesOut() {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(
        legacy(
            "boom",
            () -> {
              throw new IllegalStateException("boom");
            }));
    registry.register(legacy("nul", () -> null));
    registry.register(legacy("fine", () -> Map.of("msgid", "ok")));
    registry.enable("boom");
    registry.enable("nul");
    registry.enable("fine");
    assertThat(wireOf(registry, List.of("boom", "nul", "fine")))
        .isEqualTo("@msgid=ok :n!u@h PRIVMSG #c kept\r\n");
  }

  @Test
  void oversizedLegacyTagsAreOmittedAtEnqueueNotAtDrainSoTheMessageIsNotDropped() {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(legacy("huge", () -> Map.of("msgid", "m".repeat(4100))));
    registry.register(legacy("one", () -> Map.of("a", "x".repeat(2500))));
    registry.register(legacy("two", () -> Map.of("b", "y".repeat(2500))));
    registry.enable("huge");
    registry.enable("one");
    registry.enable("two");
    String wire = wireOf(registry, List.of("huge", "one", "two"));
    assertThat(wire).endsWith(" :n!u@h PRIVMSG #c kept\r\n").doesNotContain("msgid=");
    // Together they overflow one origin: exactly one of the two survives, whichever came first.
    assertThat(wire.contains("a=") ^ wire.contains("b=")).isTrue();
  }

  @Test
  void legacySelectionFollowsTheCapturedSnapshotNotTheLiveEnabledState() throws Exception {
    java.util.concurrent.CountDownLatch stopping = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    Provider slow =
        new Provider(false, false) {
          @Override
          public String id() {
            return "slow";
          }

          @Override
          public Capability providedCapability() {
            return new Capability("slow");
          }

          @Override
          public Map<String, String> contributeTags(OutboundMessage message) {
            return Map.of("t", "legacy");
          }

          @Override
          public void stop() {
            stopping.countDown();
            try {
              release.await();
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
          }
        };
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(slow);
    registry.enable("slow");
    Thread disabler = new Thread(() -> registry.disable("slow"));
    disabler.start();
    assertThat(stopping.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    ClientSession session = new ClientSession("r", "host", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(List.of("slow"));
    // Withdrawn from the published view: no tags, even while stop() has not returned.
    assertThat(
            new CapabilityTagRenderer(registry)
                .render(session, OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "x")))
        .isEmpty();
    release.countDown();
    disabler.join(10_000);
  }
}
