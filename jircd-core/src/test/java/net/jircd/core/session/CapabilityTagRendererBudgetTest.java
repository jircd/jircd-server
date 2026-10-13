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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.jircd.core.capability.Capability;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import org.junit.jupiter.api.Test;

class CapabilityTagRendererBudgetTest {
  private static CapabilityTagRenderer renderer(Map<String, String> serverTags) {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(
        new CapabilityExtension() {
          @Override
          public String id() {
            return "test-tags";
          }

          @Override
          public void start(ServerContext context) {
            // This tag-only test extension has no resources to initialize.
          }

          @Override
          public void stop() {
            // This tag-only test extension has no resources to release.
          }

          @Override
          public Capability providedCapability() {
            return new Capability("test-tags");
          }

          @Override
          public Map<String, String> contributeTags(OutboundMessage message) {
            return serverTags;
          }
        });
    registry.enable("test-tags");
    return new CapabilityTagRenderer(registry);
  }

  private static ClientSession recipient() {
    ClientSession recipient =
        new ClientSession("recipient", "host", RateLimitBucket.withDefaults());
    recipient.negotiatedCapabilities().add(CapabilityName.MESSAGE_TAGS);
    recipient.negotiatedCapabilities().add("test-tags");
    return recipient;
  }

  @Test
  void clientAndServerOriginsMayEachUse4094DataBytes() {
    OutboundMessage message =
        OutboundMessage.now(
            "sender!ident@host", "PRIVMSG", "bob", "hello", Map.of("+c", "x".repeat(4091)));
    Map<String, String> tags = renderer(Map.of("s", "y".repeat(4092))).render(recipient(), message);
    assertThat(MessageSerializer.tagDataBytes(tags)).isEqualTo(8189);
  }

  @Test
  void oversizedClientDataIsRejectedAfterEscaping() {
    OutboundMessage message =
        OutboundMessage.now(
            "sender!ident@host", "PRIVMSG", "bob", "hello", Map.of("+c", " ".repeat(2046)));
    assertThatThrownBy(() -> renderer(Map.of()).render(recipient(), message))
        .isInstanceOf(MessageSerializer.MessageTooLongException.class);
  }

  @Test
  void oversizedServerDataIsRejectedEvenWithNoClientTags() {
    OutboundMessage message = OutboundMessage.now("sender!ident@host", "PRIVMSG", "bob", "hello");
    assertThatThrownBy(() -> renderer(Map.of("s", "x".repeat(4093))).render(recipient(), message))
        .isInstanceOf(MessageSerializer.MessageTooLongException.class);
  }

  @Test
  void rejectedRenderedTagsDoNotStopSubsequentWrites() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(recipient(), out, renderer(Map.of("s", "x".repeat(4093))))) {
      writer.enqueue(OutboundMessage.now("sender!ident@host", "PRIVMSG", "bob", "hello"));
      writer.enqueueRaw(Message.of(Command.PING, "alive"));
    }
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("PING alive\r\n");
  }
}
