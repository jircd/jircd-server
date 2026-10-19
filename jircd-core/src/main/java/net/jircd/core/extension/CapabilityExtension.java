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
package net.jircd.core.extension;

import java.util.Map;
import net.jircd.core.capability.Capability;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.OutboundMessage;

/**
 * An {@link Extension} that also provides exactly one client-negotiable {@link Capability}
 * (FR-025). Lives under {@code jircd-capabilities/}.
 */
public interface CapabilityExtension extends Extension {

  Capability providedCapability();

  /**
   * Tags this capability contributes to a delivered message, for a recipient that has this
   * capability negotiated (T088/T089) — the caller ({@code CapabilityTagRenderer}) checks that
   * precondition before calling this; implementations don't re-check it themselves. Extensions that
   * don't decorate tags (e.g. {@code echo-message}) leave this at its no-op default.
   *
   * <p>This is a pure function of the immutable message: equal message inputs produce equal maps,
   * independently of wall clock, randomness, recipient or mutable external state. Identifiers and
   * timestamps derive from {@link OutboundMessage#messageId()} and {@link
   * OutboundMessage#sentAt()}. Contribution byte metadata is memoized once per message and
   * capability identity; rendering may invoke this function again at drain time.
   */
  default Map<String, String> contributeTags(OutboundMessage message) {
    return Map.of();
  }

  /**
   * Whether the sender's own session should be included among a fan-out's recipients (T090) — only
   * {@code echo-message} overrides this; every other capability affects per-recipient formatting
   * ({@link #contributeTags}), not recipient-set construction.
   */
  default boolean includeSenderInFanOut(ClientSession sender) {
    return false;
  }
}
