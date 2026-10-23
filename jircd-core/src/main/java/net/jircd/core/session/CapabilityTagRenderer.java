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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.MessageSerializer;

/**
 * Selects negotiated enabled contributions at enqueue and renders that selection at drain. Typed
 * hook contributions are evaluated and validated at enqueue and shared immutably; no provider
 * callback runs at drain for them. The legacy per-extension method is the temporary path for
 * providers that have not migrated.
 */
public final class CapabilityTagRenderer implements TagRenderer {
  private final ExtensionRegistry extensionRegistry;

  public CapabilityTagRenderer(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  @Override
  public PreparedTags prepare(ClientSession recipient, OutboundMessage message) {
    Set<String> negotiated = recipient.negotiatedCapabilities();
    if (negotiated.isEmpty()) return TagRenderer.NONE.prepare(recipient, message);
    boolean forwardClientTags = negotiated.contains(CapabilityName.MESSAGE_TAGS);
    WireBytes.Tags bytes = forwardClientTags ? message.clientTagBytes() : WireBytes.Tags.NONE;
    // One captured view per preparation: the typed hooks and the unmigrated providers both come
    // from it, so a provider withdrawn after the capture contributes nothing inconsistent.
    RuntimeSnapshot snapshot = extensionRegistry.capabilitySnapshot();
    HookDispatcher dispatcher = extensionRegistry.hookDispatcher();
    Map<String, String> composed = new LinkedHashMap<>();
    List<Map<String, String>> contributions = new ArrayList<>();
    for (CapabilityExtension capability : legacySelection(snapshot, negotiated)) {
      OutboundMessage.Contribution contribution =
          message.legacyContribution(capability, dispatcher);
      if (fitsAlongside(composed, contribution.tags())) {
        composed.putAll(contribution.tags());
        contributions.add(contribution.tags());
        bytes = bytes.plus(contribution.bytes());
      } else {
        dispatcher.reportAggregateOverflow(capability.id());
      }
    }
    for (HookRegistration hook : snapshot.hooksFor(HookKind.OUTBOUND_TAGS, negotiated)) {
      OutboundMessage.Contribution contribution = message.contribution(hook, dispatcher);
      if (fitsAlongside(composed, contribution.tags())) {
        composed.putAll(contribution.tags());
        contributions.add(contribution.tags());
        bytes = bytes.plus(contribution.bytes());
      } else {
        // Each contribution fit its own bound; their aggregate must fit one origin as well. The
        // latecomer is dropped and reported so the core message still goes out.
        dispatcher.reportAggregateOverflow(hook);
      }
    }
    if (contributions.isEmpty() && !forwardClientTags) {
      return TagRenderer.NONE.prepare(recipient, message);
    }
    List<Map<String, String>> selected = List.copyOf(contributions);
    // Merging can collapse many overlapping contributions into one legal tag section.
    // No emitted section exceeds the protocol maximum, even when the summed bound does.
    return new PreparedTags(
        Math.min(bytes.wireBytes(), MessageSerializer.MAX_TAG_SECTION_BYTES),
        () -> renderSelected(message, forwardClientTags, selected));
  }

  /** The enabled unmigrated providers of the captured snapshot that the recipient negotiated. */
  private List<CapabilityExtension> legacySelection(
      RuntimeSnapshot snapshot, Set<String> negotiated) {
    List<CapabilityExtension> selected = new ArrayList<>();
    for (String providerId : snapshot.providerIds()) {
      if (snapshot.hasHooks(providerId)) continue;
      if (extensionRegistry.find(providerId).orElse(null) instanceof CapabilityExtension capability
          && negotiated.contains(capability.providedCapability().name())) {
        selected.add(capability);
      }
    }
    return selected;
  }

  private static boolean fitsAlongside(Map<String, String> composed, Map<String, String> added) {
    if (composed.isEmpty()) return true; // one contribution was already checked against its bound
    Map<String, String> candidate = new LinkedHashMap<>(composed);
    candidate.putAll(added);
    return MessageSerializer.tagDataBytesAtMost(
        candidate, MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN);
  }

  @Override
  public Map<String, String> render(ClientSession recipient, OutboundMessage message) {
    return prepare(recipient, message).tags().get();
  }

  private static Map<String, String> renderSelected(
      OutboundMessage message, boolean forwardClientTags, List<Map<String, String>> contributions) {
    Map<String, String> tags = new LinkedHashMap<>();
    if (forwardClientTags) tags.putAll(message.forwardedClientTags());
    checkOriginBudget(tags);
    Map<String, String> serverTags = new LinkedHashMap<>();
    contributions.forEach(serverTags::putAll);
    checkOriginBudget(serverTags);
    tags.putAll(serverTags);
    return tags;
  }

  private static void checkOriginBudget(Map<String, String> tags) {
    if (MessageSerializer.tagDataBytes(tags) > MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN) {
      throw new MessageSerializer.MessageTooLongException(
          "Outbound tag origin exceeds 4094 data bytes");
    }
  }
}
