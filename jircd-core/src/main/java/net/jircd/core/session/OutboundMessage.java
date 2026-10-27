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
import java.util.Set;
import java.util.UUID;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookRegistration;

/**
 * Immutable fan-out content shared by recipients, with message-scoped contribution byte metadata.
 * Recipient selection and rendering remain in the tag renderer; metadata never changes content.
 *
 * <p>Client-only ({@code +}) tags passed to a constructor are candidates for forwarding, not
 * forwarded: a recipient sees one only if it negotiated a capability that selected it, which {@code
 * MessageDelivery} records. An envelope built without that record forwards no client tag.
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

    /**
     * Forwarded tag key to every capability that selected it. Empty until {@link #forwardedBy}: a
     * client tag with no recorded selector is shown to nobody.
     */
    private Map<String, Set<String>> forwardOwners = Map.of();

    private Map<HookRegistration, Contribution> hookContributions;

    /** A hook's validated, immutable tags with their exact escaped size, computed once. */
    record Contribution(Map<String, String> tags, WireBytes.Tags bytes) {}

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
                        senderPresentedForm,
                        command,
                        body == null ? List.of(target) : List.of(target, body));
        Map<String, String> forwarded = new java.util.LinkedHashMap<>();
        this.clientTags.forEach(
                (key, value) -> {
                    if (key.startsWith("+")) forwarded.put(key, value);
                });
        this.forwardedClientTags = Map.copyOf(forwarded);
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

    /**
     * Records which capabilities selected each forwarded client tag, so {@link #forwardedFor} shows
     * a tag only to recipients of one of its selectors. Call once, before the message is shared.
     */
    OutboundMessage forwardedBy(Map<String, Set<String>> ownersByKey) {
        Map<String, Set<String>> copy = new java.util.LinkedHashMap<>();
        ownersByKey.forEach((key, owners) -> copy.put(key, Set.copyOf(owners)));
        this.forwardOwners = Map.copyOf(copy);
        return this;
    }

    /**
     * The forwarded client tags a recipient with {@code negotiated} capabilities may see: those for
     * which it negotiated at least one selecting capability. It fails closed: a tag whose selector
     * was never recorded, as for an envelope built outside {@code MessageDelivery}, is not shown.
     */
    Map<String, String> forwardedFor(Set<String> negotiated) {
        Map<String, String> visible = new java.util.LinkedHashMap<>();
        forwardedClientTags.forEach(
                (key, value) -> {
                    if (!java.util.Collections.disjoint(
                            forwardOwners.getOrDefault(key, Set.of()), negotiated)) {
                        visible.put(key, value);
                    }
                });
        return visible;
    }

    /**
     * The validated contribution of {@code hook} for this envelope. A pure hook yields the same map
     * for the same immutable message, so it is evaluated once here and shared by every recipient;
     * the registration identity is only the memoization key. Nothing evaluated here reaches the
     * writer queue except as these immutable results.
     */
    synchronized Contribution contribution(HookRegistration hook, HookDispatcher dispatcher) {
        if (hookContributions == null) hookContributions = new IdentityHashMap<>();
        Contribution cached = hookContributions.get(hook);
        if (cached == null) {
            Map<String, String> tags = dispatcher.outboundTags(hook, this);
            cached = new Contribution(tags, WireBytes.tags(tags));
            hookContributions.put(hook, cached);
        }
        return cached;
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
        return Objects.hash(
                senderPresentedForm, command, target, body, clientTags, sentAt, messageId);
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
                senderPresentedForm,
                command,
                target,
                body,
                clientTags,
                Instant.now(),
                UUID.randomUUID());
    }
}
