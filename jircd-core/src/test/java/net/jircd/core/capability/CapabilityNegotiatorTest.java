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
package net.jircd.core.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.TestNegotiation;
import org.junit.jupiter.api.Test;

class CapabilityNegotiatorTest {

    private static final class OfferedCapability extends AbstractCapabilityExtension {
        OfferedCapability(String name) {
            super(name);
        }
    }

    private static void offer(ExtensionRegistry registry, String... names) {
        for (String name : names) {
            registry.register(new OfferedCapability(name));
            registry.enable(name);
        }
    }

    @Test
    void negotiationSucceedsAndRegistrationCanCompleteWithZeroEnabledCapabilities() {
        ExtensionRegistry registry = new ExtensionRegistry();
        CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
        ClientSession session =
                new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

        // Core cap-notify is offered with no provider enabled; it is not a provider.
        assertThat(negotiator.currentlyOfferedCapabilityNames()).containsExactly("cap-notify");

        negotiator.beginNegotiation(session);
        assertThat(session.isNegotiatingCapabilities()).isTrue();

        // nothing enabled, so nothing is offered and the request is declined (FR-008)
        assertThat(negotiator.request(session, java.util.List.of("message-tags"))).isFalse();

        negotiator.endNegotiation(session);
        assertThat(session.isNegotiatingCapabilities()).isFalse();
    }

    @Test
    void requestWithAnyUnknownCapabilityChangesNothing() {
        ExtensionRegistry registry = new ExtensionRegistry();
        offer(registry, "echo-message");
        CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
        ClientSession session =
                new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

        TestNegotiation.add(session, "message-tags");

        assertThat(negotiator.request(session, List.of("echo-message", "batch"))).isFalse();
        assertThat(session.negotiatedCapabilities()).containsExactly("message-tags");
    }

    @Test
    void requestAppliesAdditionsAndRemovalsTogether() {
        ExtensionRegistry registry = new ExtensionRegistry();
        offer(registry, "echo-message", "message-tags");
        CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
        ClientSession session =
                new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
        TestNegotiation.add(session, "message-tags");

        assertThat(negotiator.request(session, List.of("echo-message", "-message-tags"))).isTrue();
        assertThat(session.negotiatedCapabilities()).containsExactly("echo-message");
    }

    @Test
    void removingAnUnknownCapabilityDeclinesTheWholeRequest() {
        ExtensionRegistry registry = new ExtensionRegistry();
        offer(registry, "echo-message");
        CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
        ClientSession session =
                new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

        TestNegotiation.add(session, "message-tags");

        assertThat(negotiator.request(session, List.of("echo-message", "-nope"))).isFalse();
        assertThat(session.negotiatedCapabilities()).containsExactly("message-tags");
    }

    @Test
    void acceptedRequestSwapsInANewSetSoReadersNeverSeeAHalfAppliedOne() {
        ExtensionRegistry registry = new ExtensionRegistry();
        offer(registry, "echo-message", "message-tags");
        CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
        ClientSession session =
                new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
        TestNegotiation.add(session, "message-tags");
        var before = session.negotiatedCapabilities();

        assertThat(negotiator.request(session, List.of("echo-message", "-message-tags"))).isTrue();

        assertThat(before).containsExactly("message-tags");
        assertThat(session.negotiatedCapabilities()).containsExactly("echo-message");
    }

    @Test
    void candidateDoesNotChangeTheSession() {
        ExtensionRegistry registry = new ExtensionRegistry();
        offer(registry, "echo-message");
        CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
        ClientSession session =
                new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

        assertThat(negotiator.candidate(session, List.of("echo-message"))).isPresent();
        assertThat(negotiator.candidate(session, List.of("echo-message", "nope"))).isEmpty();
        assertThat(session.negotiatedCapabilities()).isEmpty();
    }

    @Test
    void aSecondExtensionOfferingTheSameNameIsRejectedAndRequestsStillWork() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(new DuplicateOffer("first"));
        registry.register(new DuplicateOffer("second"));
        registry.enable("first");
        assertThatThrownBy(() -> registry.enable("second"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shared");
        CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
        ClientSession session =
                new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

        assertThat(negotiator.request(session, List.of("shared"))).isTrue();
        assertThat(session.negotiatedCapabilities()).containsExactly("shared");
    }

    private static final class DuplicateOffer implements CapabilityExtension {
        private final String id;

        DuplicateOffer(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Capability providedCapability() {
            return new Capability("shared");
        }

        @Override
        public void start(ServerContext context) {
            // nothing to start: the stub only offers a capability name
        }

        @Override
        public void stop() {
            // nothing to release: the stub holds no resources
        }
    }
}
