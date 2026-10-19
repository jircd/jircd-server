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
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.MessageSerializer;

/** Selects negotiated enabled contributions at enqueue and renders that selection at drain. */
public final class CapabilityTagRenderer implements TagRenderer {
  private final ExtensionRegistry extensionRegistry;

  public CapabilityTagRenderer(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  @Override
  public PreparedTags prepare(ClientSession recipient, OutboundMessage message) {
    if (recipient.negotiatedCapabilities().isEmpty())
      return TagRenderer.NONE.prepare(recipient, message);
    boolean forwardClientTags =
        recipient.negotiatedCapabilities().contains(CapabilityName.MESSAGE_TAGS);
    List<CapabilityExtension> selected = new ArrayList<>();
    WireBytes.Tags bytes = forwardClientTags ? message.clientTagBytes() : WireBytes.Tags.NONE;
    for (var extension : extensionRegistry.enabled()) {
      if (extension instanceof CapabilityExtension capability
          && recipient.negotiatedCapabilities().contains(capability.providedCapability().name())) {
        selected.add(capability);
        bytes = bytes.plus(message.contributionBytes(capability));
      }
    }
    if (selected.isEmpty() && !forwardClientTags)
      return TagRenderer.NONE.prepare(recipient, message);
    List<CapabilityExtension> selection = List.copyOf(selected);
    // Merging can collapse many overlapping contributions into one legal tag section.
    // No emitted section exceeds the protocol maximum, even when the summed bound does.
    return new PreparedTags(
        Math.min(bytes.wireBytes(), MessageSerializer.MAX_TAG_SECTION_BYTES),
        () -> renderSelected(message, forwardClientTags, selection));
  }

  @Override
  public Map<String, String> render(ClientSession recipient, OutboundMessage message) {
    return prepare(recipient, message).tags().get();
  }

  private static Map<String, String> renderSelected(
      OutboundMessage message, boolean forwardClientTags, List<CapabilityExtension> selection) {
    Map<String, String> tags = new LinkedHashMap<>();
    if (forwardClientTags) tags.putAll(message.forwardedClientTags());
    checkOriginBudget(tags);
    Map<String, String> serverTags = new LinkedHashMap<>();
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
