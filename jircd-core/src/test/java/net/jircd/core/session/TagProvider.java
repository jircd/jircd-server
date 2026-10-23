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

import java.util.Map;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.protocol.MessageSerializer;

/** A capability that contributes a fixed set of server tags, declared at their exact size. */
final class TagProvider extends AbstractCapabilityExtension {
  private final Map<String, String> tags;

  TagProvider(String id, Map<String, String> tags) {
    super(id);
    this.tags = Map.copyOf(tags);
  }

  @Override
  public void register(CapabilityRegistrar registrar) {
    if (tags.isEmpty()) return; // a provider with nothing to contribute registers nothing
    registrar.onOutboundTags(
        tags.keySet(),
        Math.min(
            MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN,
            Math.max(1, MessageSerializer.tagDataBytes(tags))),
        message -> tags);
  }
}
