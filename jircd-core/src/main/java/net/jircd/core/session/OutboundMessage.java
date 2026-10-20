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

import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.jircd.core.extension.CapabilityExtension;

/**
 * Immutable fan-out content shared by recipients, with message-scoped contribution byte metadata.
 * Recipient selection and rendering remain in the tag renderer; metadata never changes content.
 */
public final class OutboundMessage {
  private final String senderPresentedForm;
  private final String command;
  private final String target;
  private final String body;
  private final Map<String, String> clientTags;
  private final Instant sentAt;
  private final UUID messageId;
  private final long componentBytes;
  private final Map<String, String> forwardedClientTags;
  private final WireBytes.Tags clientTagBytes;
  private Map<CapabilityExtension, WireBytes.Tags> contributions;

  /** Creates shared message content, defensively copying client tags. */
  public OutboundMessage(
      String senderPresentedForm,
      String command,
      String target,
      String body,
      Map<String, String> clientTags,
      Instant sentAt,
      UUID messageId) {
    this.senderPresentedForm = senderPresentedForm;
    this.command = command;
    this.target = target;
    this.body = body;
    this.clientTags = Map.copyOf(clientTags);
    this.sentAt = sentAt;
    this.messageId = messageId;
    this.componentBytes =
        WireBytes.components(
            senderPresentedForm, command, body == null ? List.of(target) : List.of(target, body));
    Map<String, String> forwarded = new java.util.LinkedHashMap<>();
    this.clientTags.forEach(
        (key, value) -> {
          if (key.startsWith("+")) forwarded.put(key, value);
        });
    this.forwardedClientTags = Map.copyOf(forwarded);
    this.clientTagBytes = WireBytes.tags(forwardedClientTags);
  }

  public String senderPresentedForm() {
    return senderPresentedForm;
  }

  public String command() {
    return command;
  }

  public String target() {
    return target;
  }

  public String body() {
    return body;
  }

  public Map<String, String> clientTags() {
    return clientTags;
  }

  public Instant sentAt() {
    return sentAt;
  }

  public UUID messageId() {
    return messageId;
  }

  long componentBytes() {
    return componentBytes;
  }

  Map<String, String> forwardedClientTags() {
    return forwardedClientTags;
  }

  WireBytes.Tags clientTagBytes() {
    return clientTagBytes;
  }

  synchronized WireBytes.Tags contributionBytes(CapabilityExtension capability) {
    if (contributions == null) contributions = new IdentityHashMap<>();
    return contributions.computeIfAbsent(
        capability, key -> WireBytes.tags(key.contributeTags(this)));
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof OutboundMessage message)) return false;
    return Objects.equals(senderPresentedForm, message.senderPresentedForm)
        && Objects.equals(command, message.command)
        && Objects.equals(target, message.target)
        && Objects.equals(body, message.body)
        && Objects.equals(clientTags, message.clientTags)
        && Objects.equals(sentAt, message.sentAt)
        && Objects.equals(messageId, message.messageId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(senderPresentedForm, command, target, body, clientTags, sentAt, messageId);
  }

  /** Creates a message with a fresh identifier and send time. */
  public static OutboundMessage now(
      String senderPresentedForm, String command, String target, String body) {
    return now(senderPresentedForm, command, target, body, Map.of());
  }

  /** Creates a message with immutable client tags, a fresh identifier and send time. */
  public static OutboundMessage now(
      String senderPresentedForm,
      String command,
      String target,
      String body,
      Map<String, String> clientTags) {
    return new OutboundMessage(
        senderPresentedForm, command, target, body, clientTags, Instant.now(), UUID.randomUUID());
  }
}
