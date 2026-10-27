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
package net.jircd.capabilities.servertime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityDescriptor;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.FaultCategory;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.session.CapabilityTagRenderer;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.OutboundMessage;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.MessageSerializer;
import org.junit.jupiter.api.Test;

class ServerTimeExtensionTest {
    private final List<String> faults = new ArrayList<>();
    private final HookDispatcher dispatcher =
            new HookDispatcher(
                    (provider, kind, category) ->
                            faults.add(provider + "/" + kind + "/" + category));

    private static OutboundMessage message(Instant sentAt) {
        return new OutboundMessage(
                "n!u@h",
                "PRIVMSG",
                "#c",
                "hello",
                Map.of("+client", "kept"),
                sentAt,
                UUID.fromString("00000000-0000-0000-0000-000000000123"));
    }

    private static HookRegistration hook() {
        CapabilityRegistrar registrar = new CapabilityRegistrar("server-time", "server-time");
        new ServerTimeExtension().register(registrar);
        CapabilityDescriptor descriptor = registrar.build();
        assertThat(descriptor.registrations()).hasSize(1);
        return descriptor.registrations().getFirst();
    }

    @Test
    void registersOneOwnedTimeTagWithADeclaredBound() {
        HookRegistration hook = hook();
        assertThat(hook.kind()).isEqualTo(HookKind.OUTBOUND_TAGS);
        assertThat(hook.ownedKeys()).containsExactly("time");
        assertThat(hook.requiresNegotiated()).isEmpty();
    }

    @Test
    void contributesTheEnvelopeSendTimeAsAnExactTimestampTag() {
        Map<String, String> tags =
                dispatcher.outboundTags(hook(), message(Instant.parse("2020-01-02T03:04:05.006Z")));
        assertThat(tags).containsExactly(Map.entry("time", "2020-01-02T03:04:05.006Z"));
        assertThat(faults).isEmpty();
    }

    @Test
    void contributionIsPureAndIndependentOfRecipientAndWallClock() {
        OutboundMessage first = message(Instant.parse("2020-01-02T03:04:05.006Z"));
        OutboundMessage equal = message(Instant.parse("2020-01-02T03:04:05.006Z"));
        HookRegistration hook = hook();
        for (int i = 0; i < 10; i++) {
            assertThat(dispatcher.outboundTags(hook, first))
                    .isEqualTo(dispatcher.outboundTags(hook(), equal))
                    .containsEntry("time", "2020-01-02T03:04:05.006Z");
        }
    }

    @Test
    void contributionIsImmutableAndWithinItsDeclaredBoundAtTheExtremes() {
        HookRegistration hook = hook();
        for (Instant extreme :
                List.of(
                        Instant.EPOCH,
                        Instant.parse("2000-01-01T00:00:00.000Z"),
                        Instant.parse("9999-12-31T23:59:59.999Z"))) {
            Map<String, String> tags = dispatcher.outboundTags(hook, message(extreme));
            assertThat(MessageSerializer.tagDataBytes(tags))
                    .isLessThanOrEqualTo((int) hook.maxBytes());
            assertThat(tags).hasSize(1);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tags.put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(faults).isEmpty();
    }

    @Test
    void aFailureIsContainedAndYieldsNoTagInsteadOfPropagating() {
        assertThat(dispatcher.outboundTags(hook(), message(null))).isEmpty();
        assertThat(faults).containsExactly("server-time/OUTBOUND_TAGS/" + FaultCategory.EXCEPTION);
    }

    @Test
    void decoratesWithoutMessageTagsNegotiatedOrEnabled() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(new ServerTimeExtension());
        registry.enable(ServerTimeExtension.ID);
        ClientSession recipient = new ClientSession("r", "host", RateLimitBucket.withDefaults());
        recipient.replaceNegotiatedCapabilities(List.of(CapabilityName.SERVER_TIME));
        Map<String, String> tags =
                new CapabilityTagRenderer(registry)
                        .render(recipient, message(Instant.parse("2021-05-06T07:08:09.010Z")));
        assertThat(tags).containsExactly(Map.entry("time", "2021-05-06T07:08:09.010Z"));
        ClientSession without = new ClientSession("w", "host", RateLimitBucket.withDefaults());
        assertThat(new CapabilityTagRenderer(registry).render(without, message(Instant.EPOCH)))
                .isEmpty();
    }
}
