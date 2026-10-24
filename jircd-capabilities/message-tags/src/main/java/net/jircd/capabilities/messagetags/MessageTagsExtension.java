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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.InboundTagResult;
import net.jircd.core.extension.capability.InboundTags;
import net.jircd.core.extension.capability.RequestContext;
import net.jircd.core.session.MessageDelivery;
import net.jircd.core.session.OutboundMessage;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Command;

/**
 * The {@code message-tags} capability (FR-025): owns the semantics of IRCv3 message tags. It
 * selects the client-only ({@code +}) tags of an inbound message for forwarding to recipients that
 * negotiated it, contributes the {@code msgid} tag unconditionally — present on every delivered
 * message for a recipient that has this capability negotiated at all, regardless of whether {@code
 * server-time} is also negotiated (FR-059) — and owns the {@code TAGMSG} command.
 *
 * <p>Core still parses and size-checks tag syntax whether or not this module is present; without it
 * no client tag is forwarded, no {@code msgid} is added and {@code TAGMSG} is an unknown command.
 */
public final class MessageTagsExtension extends AbstractCapabilityExtension {

  public static final String ID = CapabilityName.MESSAGE_TAGS;

  private static final String MSGID_TAG = "msgid";

  /** {@code msgid=} plus the 36-character UUID; no character in it needs tag escaping. */
  static final long MAX_MSGID_TAG_BYTES = 42;

  private final AtomicReference<ServerContext> context = new AtomicReference<>();

  public MessageTagsExtension() {
    super(ID);
  }

  @Override
  public void start(ServerContext serverContext) {
    context.set(serverContext);
  }

  @Override
  public void stop() {
    context.set(null);
  }

  @Override
  public void register(CapabilityRegistrar registrar) {
    registrar.onInboundTags(MessageTagsExtension::forwardClientOnlyTags);
    registrar.onOutboundTags(Set.of(MSGID_TAG), MAX_MSGID_TAG_BYTES, MessageTagsExtension::msgid);
    registrar.command(
        Command.TAGMSG,
        new TagmsgCommandHandler(this::currentDelivery, () -> started().serverName().get()));
  }

  private MessageDelivery currentDelivery() {
    ServerContext started = started();
    return new MessageDelivery(
        started.channelRegistry(),
        started.nicknameRegistry(),
        started.extensionRegistry(),
        started.serverName());
  }

  private ServerContext started() {
    ServerContext current = context.get();
    if (current == null) throw new IllegalStateException("message-tags is not started");
    return current;
  }

  /** Only client-only tags are forwarded; a client's attempt to set a server tag is dropped. */
  static InboundTagResult forwardClientOnlyTags(InboundTags tags, RequestContext context) {
    Map<String, String> forwarded = new LinkedHashMap<>();
    tags.tags()
        .forEach(
            (key, value) -> {
              if (key.startsWith("+")) forwarded.put(key, value);
            });
    return forwarded.isEmpty() ? InboundTagResult.NONE : new InboundTagResult(forwarded, Map.of());
  }

  /** Pure in the envelope: every recipient of one message sees the same identifier. */
  static Map<String, String> msgid(OutboundMessage message) {
    return Map.of(MSGID_TAG, message.messageId().toString());
  }
}
