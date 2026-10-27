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
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import org.junit.jupiter.api.Test;

/** Providers publish typed tag hooks: evaluated once per envelope, shared, contained on a fault. */
class CapabilityTagRendererMigrationTest {

    private static class Provider implements CapabilityExtension {
        final AtomicInteger hookCalls = new AtomicInteger();
        final boolean failing;

        Provider(boolean failing) {
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
            registrar.onOutboundTags(
                    Set.of("t"),
                    16,
                    message -> {
                        hookCalls.incrementAndGet();
                        if (failing) throw new IllegalStateException("boom");
                        return Map.of("t", "hook");
                    });
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
        ClientTagForwarder.enableIn(registry);
        return registry;
    }

    @Test
    void aProviderIsEvaluatedOncePerEnvelopeAndNeverAtDrain() {
        Provider provider = new Provider(false);
        CapabilityTagRenderer renderer = new CapabilityTagRenderer(registryWith(provider));
        OutboundMessage envelope = OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi");
        for (int i = 0; i < 5; i++) {
            TagRenderer.PreparedTags prepared = renderer.prepare(recipient("r" + i), envelope);
            // Drain-time rendering reads the shared immutable result; it calls no provider code.
            assertThat(prepared.tags().get()).containsExactly(Map.entry("t", "hook"));
        }
        assertThat(provider.hookCalls).hasValue(1);
    }

    @Test
    void aFaultingHookContributesNothingAndLeavesOtherOutputIntact() {
        Provider provider = new Provider(true);
        CapabilityTagRenderer renderer = new CapabilityTagRenderer(registryWith(provider));
        OutboundMessage envelope =
                ForwardedTags.selectedBy(
                        "message-tags",
                        OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi", Map.of("+keep", "me")));
        ClientSession session = recipient("r");
        session.replaceNegotiatedCapabilities(List.of("tagger", "message-tags"));
        assertThat(renderer.render(session, envelope)).containsExactly(Map.entry("+keep", "me"));
    }

    @Test
    void clientTagsWithNoRecordedSelectorAreNotForwardedToAnyone() {
        CapabilityTagRenderer renderer =
                new CapabilityTagRenderer(registryWith(new Provider(false)));
        OutboundMessage envelope =
                OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi", Map.of("+keep", "me"));
        ClientSession session = recipient("r");
        session.replaceNegotiatedCapabilities(List.of("tagger", "message-tags"));

        assertThat(renderer.render(session, envelope)).doesNotContainKey("+keep");
    }

    @Test
    void disablingWithdrawsNewCapturesButCapturedWorkKeepsItsTags() {
        Provider provider = new Provider(false);
        ExtensionRegistry registry = registryWith(provider);
        CapabilityTagRenderer renderer = new CapabilityTagRenderer(registry);
        OutboundMessage envelope = OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi");
        TagRenderer.PreparedTags captured = renderer.prepare(recipient("a"), envelope);
        registry.disable("tagger");
        assertThat(captured.tags().get()).containsExactly(Map.entry("t", "hook"));
        assertThat(
                        renderer.render(
                                recipient("b"), OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "x")))
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
        return new Provider(false) {
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
                registrar.onOutboundTags(
                        Set.of(key), 4000, message -> Map.of(key, "x".repeat(3000)));
            }
        };
    }

    @Test
    void aContributionThatOverflowsAlongsideAnotherIsDroppedNotTheMessage() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(typed("first", "msgid", () -> Map.of("msgid", "m".repeat(2000))));
        registry.register(bulky("second", "t"));
        registry.enable("first");
        registry.enable("second");
        ClientSession session = new ClientSession("r", "host", RateLimitBucket.withDefaults());
        session.replaceNegotiatedCapabilities(List.of("first", "second"));
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

    /** A provider whose single owned tag comes from {@code tags}, declared at the widest bound. */
    private static AbstractCapabilityExtension typed(
            String id, String key, java.util.function.Supplier<Map<String, String>> tags) {
        return new AbstractCapabilityExtension(id) {
            @Override
            public void register(CapabilityRegistrar registrar) {
                registrar.onOutboundTags(Set.of(key), 4094, message -> tags.get());
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
    void aProviderThatThrowsOrReturnsNullContributesNothingAndTheMessageStillGoesOut() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(
                typed(
                        "boom",
                        "a",
                        () -> {
                            throw new IllegalStateException("boom");
                        }));
        registry.register(typed("nul", "b", () -> null));
        registry.register(typed("fine", "msgid", () -> Map.of("msgid", "ok")));
        registry.enable("boom");
        registry.enable("nul");
        registry.enable("fine");
        assertThat(wireOf(registry, List.of("boom", "nul", "fine")))
                .isEqualTo("@msgid=ok :n!u@h PRIVMSG #c kept\r\n");
    }

    @Test
    void oversizedTagsAreOmittedAtEnqueueNotAtDrainSoTheMessageIsNotDropped() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(typed("huge", "msgid", () -> Map.of("msgid", "m".repeat(4100))));
        registry.register(typed("one", "a", () -> Map.of("a", "x".repeat(2500))));
        registry.register(typed("two", "b", () -> Map.of("b", "y".repeat(2500))));
        registry.enable("huge");
        registry.enable("one");
        registry.enable("two");
        String wire = wireOf(registry, List.of("huge", "one", "two"));
        assertThat(wire).endsWith(" :n!u@h PRIVMSG #c kept\r\n").doesNotContain("msgid=");
        // Together they overflow one origin: exactly one of the two survives, whichever came first.
        assertThat(wire.contains("a=") ^ wire.contains("b=")).isTrue();
    }

    @Test
    void selectionFollowsTheCapturedSnapshotNotTheLiveEnabledState() throws Exception {
        java.util.concurrent.CountDownLatch stopping = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AbstractCapabilityExtension slow =
                new AbstractCapabilityExtension("slow") {
                    @Override
                    public void register(CapabilityRegistrar registrar) {
                        registrar.onOutboundTags(Set.of("t"), 16, message -> Map.of("t", "v"));
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
                                .render(
                                        session,
                                        OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "x")))
                .isEmpty();
        release.countDown();
        disabler.join(10_000);
    }
}
