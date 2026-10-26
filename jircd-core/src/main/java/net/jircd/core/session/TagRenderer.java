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
import java.util.function.Consumer;
import net.jircd.core.extension.capability.FramingPlan;
import net.jircd.core.extension.capability.OutputGroup;
import net.jircd.core.extension.capability.PreparedGroup;
import net.jircd.core.extension.capability.RequestContext;

/**
 * Produces the message-tags a recipient should see on a delivered {@link OutboundMessage},
 * live-checked against that recipient's currently negotiated-and-enabled capabilities (research.md
 * "Message fan-out concurrency model"). The default renders no tags at all and frames a group as
 * plain members; {@link CapabilityTagRenderer} asks the capability providers that own each.
 */
@FunctionalInterface
public interface TagRenderer {

  PreparedTags NO_TAGS = new PreparedTags(0, Map::of);

  TagRenderer NONE =
      new TagRenderer() {
        @Override
        public Map<String, String> render(ClientSession recipient, OutboundMessage message) {
          return Map.of();
        }

        @Override
        public PreparedTags prepare(ClientSession recipient, OutboundMessage message) {
          return NO_TAGS;
        }
      };

  /**
   * Deferred tag rendering with a conservative bound on its complete tag section, including
   * framing.
   */
  record PreparedTags(long wireBytes, java.util.function.Supplier<Map<String, String>> tags) {}

  /**
   * Prepares a reservation without rendering the recipient's wire line. Custom renderers without
   * contribution metadata reserve the protocol's full legal tag section; capability renderers
   * override this with the selected message contributions.
   */
  default PreparedTags prepare(ClientSession recipient, OutboundMessage message) {
    return new PreparedTags(
        net.jircd.protocol.MessageSerializer.MAX_TAG_SECTION_BYTES,
        () -> render(recipient, message));
  }

  Map<String, String> render(ClientSession recipient, OutboundMessage message);

  /**
   * How {@code group} is framed for {@code recipient}: the complete ordered messages, including any
   * opener and closer. The default sends the members plain; capability renderers ask the provider
   * that owns group framing. A plan returned here is complete: it is queued whole or not at all.
   */
  default FramingPlan frame(ClientSession recipient, OutputGroup group, RequestContext context) {
    return FramingPlan.plain(group);
  }

  /**
   * Both complete alternatives for {@code group}: the framing {@link #frame} would choose now, and
   * the plain members. Prepared outside every core lock; the default frames nothing.
   */
  default PreparedGroup prepareGroup(
      ClientSession recipient, OutputGroup group, RequestContext context) {
    FramingPlan framed = frame(recipient, group, context);
    return new PreparedGroup(framed, FramingPlan.plain(group), null);
  }

  /**
   * Chooses one of {@code group}'s alternatives against the current state and hands it to {@code
   * append}, which queues it whole. Capability renderers choose under the administrative mutex,
   * framed only while its owner and declared prerequisites are still effective for {@code
   * recipient}; no provider runs then. The default keeps the framing as prepared.
   */
  default void admitGroup(
      ClientSession recipient, PreparedGroup group, Consumer<FramingPlan> append) {
    append.accept(group.framed());
  }
}
