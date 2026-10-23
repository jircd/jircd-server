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
package net.jircd.core.extension.capability;

import java.util.Map;

/**
 * Immutable context of one inbound request: a core request id plus bounded, provider-namespaced
 * attributes. Core never interprets the attributes.
 */
public record RequestContext(long requestId, Map<String, String> attributes) {
  public static final int MAX_ATTRIBUTES = 8;
  public static final int MAX_ATTRIBUTE_BYTES = 256;

  public RequestContext {
    attributes = Map.copyOf(attributes);
    if (!validAttributes(attributes)) {
      throw new IllegalArgumentException("request attributes exceed their bounds");
    }
  }

  public static RequestContext of(long requestId, Map<String, String> attributes) {
    return new RequestContext(requestId, attributes);
  }

  /** Whether {@code attributes} is a bounded set of {@code provider/key} entries. */
  static boolean validAttributes(Map<String, String> attributes) {
    if (attributes.size() > MAX_ATTRIBUTES) return false;
    for (var entry : attributes.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || value == null || key.indexOf('/') <= 0 || !Tokens.isSafe(key)) {
        return false;
      }
      if (net.jircd.protocol.MessageSerializer.utf8Bytes(value) > MAX_ATTRIBUTE_BYTES) return false;
    }
    return true;
  }
}
