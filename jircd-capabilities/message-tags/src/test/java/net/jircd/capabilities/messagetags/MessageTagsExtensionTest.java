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
package net.jircd.capabilities.messagetags;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jircd.core.extension.capability.CapabilityDescriptor;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.extension.capability.InboundTagResult;
import net.jircd.core.extension.capability.InboundTags;
import net.jircd.core.extension.capability.RequestContext;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.core.session.OutboundMessage;
import net.jircd.protocol.Command;
import net.jircd.protocol.MessageSerializer;
import org.junit.jupiter.api.Test;

class MessageTagsExtensionTest {
  private final List<String> faults = new ArrayList<>();
  private final HookDispatcher dispatcher =
      new HookDispatcher(
          (provider, kind, category) -> faults.add(provider + "/" + kind + "/" + category));

  private static CapabilityDescriptor descriptor() {
    CapabilityRegistrar registrar = new CapabilityRegistrar("message-tags", "message-tags");
    new MessageTagsExtension().register(registrar);
    return registrar.build();
  }

  private static HookRegistration hook(HookKind kind) {
    return descriptor().registrations().stream()
        .filter(registration -> registration.kind() == kind)
        .findFirst()
        .orElseThrow();
  }

  private static OutboundMessage message() {
    return new OutboundMessage(
        "n!u@h",
        "PRIVMSG",
        "#c",
        "hello",
        Map.of(),
        Instant.parse("2020-01-02T03:04:05.006Z"),
        UUID.fromString("00000000-0000-0000-0000-000000000123"));
  }

  @Test
  void ownsInboundTagsTheMsgidTagAndTheTagmsgCommand() {
    CapabilityDescriptor descriptor = descriptor();
    assertThat(descriptor.registrations())
        .extracting(HookRegistration::kind)
        .containsExactlyInAnyOrder(HookKind.INBOUND_TAGS, HookKind.OUTBOUND_TAGS, HookKind.COMMAND);
    assertThat(hook(HookKind.OUTBOUND_TAGS).ownedKeys()).containsExactly("msgid");
    assertThat(hook(HookKind.COMMAND).command()).isEqualTo(Command.TAGMSG);
    // The sender needs nothing negotiated: client-only tags are accepted as before.
    assertThat(hook(HookKind.INBOUND_TAGS).requiresNegotiated()).isEmpty();
  }

  @Test
  void msgidIsPureInTheEnvelopeAndWithinItsDeclaredBound() {
    HookRegistration hook = hook(HookKind.OUTBOUND_TAGS);
    OutboundMessage message = message();
    for (int i = 0; i < 10; i++) {
      Map<String, String> tags = dispatcher.outboundTags(hook, message);
      assertThat(tags).containsExactly(Map.entry("msgid", message.messageId().toString()));
      assertThat(MessageSerializer.tagDataBytes(tags)).isLessThanOrEqualTo((int) hook.maxBytes());
    }
    assertThat(faults).isEmpty();
  }

  @Test
  void forwardsOnlyClientOnlyTagsAndDropsForgedServerKeys() {
    RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY.with(descriptor());
    InboundTags input =
        new InboundTags(
            "PRIVMSG",
            Map.of("+typing", "active", "+draft/reply", "abc", "msgid", "forged", "time", "x"));

    InboundTagResult result =
        dispatcher.inboundTags(snapshot, Set.of(), input, RequestContext.of(1, Map.of()));

    assertThat(result.forwarded())
        .containsOnly(Map.entry("+typing", "active"), Map.entry("+draft/reply", "abc"));
    assertThat(result.context()).isEmpty();
    assertThat(faults).isEmpty();
  }

  @Test
  void noClientTagsYieldsNoForwarding() {
    RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY.with(descriptor());
    InboundTagResult result =
        dispatcher.inboundTags(
            snapshot,
            Set.of(),
            new InboundTags("PRIVMSG", Map.of()),
            RequestContext.of(1, Map.of()));
    assertThat(result).isEqualTo(InboundTagResult.NONE);
  }

  @Test
  void theSelectionRecordsMessageTagsAsTheOwnerOfEachForwardedTag() {
    RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY.with(descriptor());
    HookDispatcher.InboundSelection selection =
        dispatcher.inboundSelection(
            snapshot,
            Set.of(),
            new InboundTags("PRIVMSG", Map.of("+typing", "active", "msgid", "forged")),
            RequestContext.of(1, Map.of()));

    assertThat(selection.ownersByKey())
        .containsExactly(Map.entry("+typing", Set.of("message-tags")));
  }
}
