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
    // One captured view per preparation: the typed hooks of migrated providers come from it, and
    // only providers that published none are asked through the legacy method.
    RuntimeSnapshot snapshot = extensionRegistry.capabilitySnapshot();
    List<CapabilityExtension> legacy = legacySelection(snapshot, negotiated);
    Map<String, String> composed = new LinkedHashMap<>();
    for (CapabilityExtension capability : legacy) {
      composed.putAll(message.legacyTags(capability));
      bytes = bytes.plus(message.contributionBytes(capability));
    }
    List<Map<String, String>> typed = new ArrayList<>();
    for (HookRegistration hook : snapshot.hooksFor(HookKind.OUTBOUND_TAGS, negotiated)) {
      OutboundMessage.Contribution contribution =
          message.contribution(hook, extensionRegistry.hookDispatcher());
      if (fitsAlongside(composed, contribution.tags())) {
        composed.putAll(contribution.tags());
        typed.add(contribution.tags());
        bytes = bytes.plus(contribution.bytes());
      } else {
        // Each contribution fit its own bound, and the legacy ones are budgeted at drain; their
        // aggregate must fit one origin as well. The latecomer is dropped and reported so the core
        // message still goes out.
        extensionRegistry.hookDispatcher().reportAggregateOverflow(hook);
      }
    }
    if (legacy.isEmpty() && typed.isEmpty() && !forwardClientTags) {
      return TagRenderer.NONE.prepare(recipient, message);
    }
    List<CapabilityExtension> selection = List.copyOf(legacy);
    List<Map<String, String>> contributed = List.copyOf(typed);
    // Merging can collapse many overlapping contributions into one legal tag section.
    // No emitted section exceeds the protocol maximum, even when the summed bound does.
    return new PreparedTags(
        Math.min(bytes.wireBytes(), MessageSerializer.MAX_TAG_SECTION_BYTES),
        () -> renderSelected(message, forwardClientTags, contributed, selection));
  }

  private List<CapabilityExtension> legacySelection(
      RuntimeSnapshot snapshot, Set<String> negotiated) {
    List<CapabilityExtension> selected = new ArrayList<>();
    for (var extension : extensionRegistry.enabled()) {
      if (extension instanceof CapabilityExtension capability
          && !snapshot.hasHooks(capability.id())
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
    return MessageSerializer.tagDataBytes(candidate)
        <= MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN;
  }

  @Override
  public Map<String, String> render(ClientSession recipient, OutboundMessage message) {
    return prepare(recipient, message).tags().get();
  }

  private static Map<String, String> renderSelected(
      OutboundMessage message,
      boolean forwardClientTags,
      List<Map<String, String>> contributed,
      List<CapabilityExtension> selection) {
    Map<String, String> tags = new LinkedHashMap<>();
    if (forwardClientTags) tags.putAll(message.forwardedClientTags());
    checkOriginBudget(tags);
    Map<String, String> serverTags = new LinkedHashMap<>();
    contributed.forEach(serverTags::putAll);
    for (CapabilityExtension capability : selection)
      serverTags.putAll(capability.contributeTags(message));
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
