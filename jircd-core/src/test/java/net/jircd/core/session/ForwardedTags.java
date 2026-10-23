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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Test helper: records {@code owner} as the selector of every client-only tag of an envelope. */
final class ForwardedTags {
  private ForwardedTags() {}

  static OutboundMessage selectedBy(String owner, OutboundMessage message) {
    Map<String, Set<String>> owners = new LinkedHashMap<>();
    message.clientTags().keySet().stream()
        .filter(key -> key.startsWith("+"))
        .forEach(key -> owners.put(key, Set.of(owner)));
    return message.forwardedBy(owners);
  }
}
