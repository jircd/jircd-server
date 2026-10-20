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

import java.util.List;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
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
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

    assertThat(negotiator.currentlyOfferedCapabilityNames()).isEmpty();

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
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

    assertThat(negotiator.request(session, List.of("echo-message", "batch"))).isFalse();
    assertThat(session.negotiatedCapabilities()).isEmpty();
  }

  @Test
  void requestAppliesAdditionsAndRemovalsTogether() {
    ExtensionRegistry registry = new ExtensionRegistry();
    offer(registry, "echo-message", "message-tags");
    CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    session.negotiatedCapabilities().add("message-tags");

    assertThat(negotiator.request(session, List.of("echo-message", "-message-tags"))).isTrue();
    assertThat(session.negotiatedCapabilities()).containsExactly("echo-message");
  }

  @Test
  void removingAnUnknownCapabilityDeclinesTheWholeRequest() {
    ExtensionRegistry registry = new ExtensionRegistry();
    offer(registry, "echo-message");
    CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());

    assertThat(negotiator.request(session, List.of("echo-message", "-nope"))).isFalse();
    assertThat(session.negotiatedCapabilities()).isEmpty();
  }
}
