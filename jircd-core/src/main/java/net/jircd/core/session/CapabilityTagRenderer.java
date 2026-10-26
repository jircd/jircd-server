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
import java.util.function.Consumer;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRuntime;
import net.jircd.core.extension.capability.FramingPlan;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.extension.capability.OutputGroup;
import net.jircd.core.extension.capability.PreparedGroup;
import net.jircd.core.extension.capability.RequestContext;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.protocol.MessageSerializer;

/**
 * Selects negotiated enabled contributions at enqueue and renders that selection at drain. Typed
 * hook contributions are evaluated and validated at enqueue and shared immutably; no provider
 * callback runs at drain for them.
 */
public final class CapabilityTagRenderer implements TagRenderer {
  private final ExtensionRegistry extensionRegistry;

  public CapabilityTagRenderer(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  @Override
  public PreparedTags prepare(ClientSession recipient, OutboundMessage message) {
    // One view per preparation, captured under the administrative mutex: the snapshot and this
    // recipient's negotiation come from the same side of any publication and prune. Work prepared
    // from it may be queued after a later DEL; it is not rechecked.
    CapabilityRuntime.CapturedView view = extensionRegistry.capabilityControl().capture(recipient);
    Set<String> negotiated = view.negotiated();
    if (negotiated.isEmpty()) return TagRenderer.NONE.prepare(recipient, message);
    RuntimeSnapshot snapshot = view.snapshot();
    // The tags an inbound-tag provider selected go to recipients that negotiated that provider.
    Map<String, String> forwarded = message.forwardedFor(negotiated);
    boolean forwardClientTags = !forwarded.isEmpty();
    WireBytes.Tags bytes = WireBytes.tags(forwarded);
    HookDispatcher dispatcher = extensionRegistry.hookDispatcher();
    Map<String, String> composed = new LinkedHashMap<>();
    List<Map<String, String>> contributions = new ArrayList<>();
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
        () -> renderSelected(forwarded, selected));
  }

  private static boolean fitsAlongside(Map<String, String> composed, Map<String, String> added) {
    if (composed.isEmpty()) return true; // one contribution was already checked against its bound
    Map<String, String> candidate = new LinkedHashMap<>(composed);
    candidate.putAll(added);
    return MessageSerializer.tagDataBytesAtMost(
        candidate, MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN);
  }

  @Override
  public FramingPlan frame(ClientSession recipient, OutputGroup group, RequestContext context) {
    return prepareGroup(recipient, group, context).framed();
  }

  @Override
  public PreparedGroup prepareGroup(
      ClientSession recipient, OutputGroup group, RequestContext context) {
    CapabilityRuntime.CapturedView view = extensionRegistry.capabilityControl().capture(recipient);
    return extensionRegistry
        .hookDispatcher()
        .prepareOutputGroup(view.snapshot(), view.negotiated(), group, context);
  }

  /**
   * The one current prerequisite choice output makes: under the administrative mutex, framed only
   * if its owner is still published and it and its prerequisites are still negotiated by {@code
   * recipient}, else plain; the choice is appended whole before the mutex is released. Ordinary
   * output never rechecks its captured view like this.
   */
  @Override
  public void admitGroup(
      ClientSession recipient, PreparedGroup group, Consumer<FramingPlan> append) {
    if (group.owner() == null) {
      append.accept(group.framed());
      return;
    }
    extensionRegistry
        .capabilityControl()
        .runUnderControl(
            () ->
                append.accept(
                    extensionRegistry
                            .capabilitySnapshot()
                            .effective(group.owner(), recipient.negotiatedCapabilities())
                        ? group.framed()
                        : group.plain()));
  }

  @Override
  public Map<String, String> render(ClientSession recipient, OutboundMessage message) {
    return prepare(recipient, message).tags().get();
  }

  private static Map<String, String> renderSelected(
      Map<String, String> forwarded, List<Map<String, String>> contributions) {
    Map<String, String> tags = new LinkedHashMap<>(forwarded);
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
