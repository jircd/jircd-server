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
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.InboundTagResult;

/**
 * Stand-in for message-tags, which lives in a module core cannot depend on: forwards every
 * client-only tag to recipients that negotiated {@code message-tags}.
 */
final class ClientTagForwarder extends AbstractCapabilityExtension {
  ClientTagForwarder() {
    super("message-tags");
  }

  @Override
  public void register(CapabilityRegistrar registrar) {
    registrar.onInboundTags(
        (tags, context) -> {
          Map<String, String> forwarded = new LinkedHashMap<>();
          tags.tags()
              .forEach(
                  (key, value) -> {
                    if (key.startsWith("+")) forwarded.put(key, value);
                  });
          return new InboundTagResult(forwarded, Map.of());
        });
  }

  static void enableIn(net.jircd.core.extension.ExtensionRegistry registry) {
    registry.register(new ClientTagForwarder());
    registry.enable("message-tags");
  }
}
