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

import java.util.Objects;
import java.util.Set;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;

/**
 * One typed, owned hook captured at staging. Immutable; compared by identity, so it doubles as the
 * memoization key for per-envelope results (never as a lifecycle version).
 */
public final class HookRegistration {
    private final HookKind kind;
    private final String providerId;
    private final String capabilityName;
    private final Object hook;
    private final Set<String> requiresNegotiated;
    private final Set<String> ownedKeys;
    private final ReplySlot slot;
    private final ReplyBound replyBound;
    private final EventKind eventKind;
    private final Command command;
    private final long maxBytes;

    private HookRegistration(Builder builder) {
        this.kind = Objects.requireNonNull(builder.kind);
        this.providerId = builder.providerId;
        this.capabilityName = builder.capabilityName;
        this.hook = Objects.requireNonNull(builder.hook);
        this.requiresNegotiated = Set.copyOf(builder.requiresNegotiated);
        this.ownedKeys = Set.copyOf(builder.ownedKeys);
        this.slot = builder.slot;
        this.replyBound = builder.replyBound;
        this.eventKind = builder.eventKind;
        this.command = builder.command;
        this.maxBytes = builder.maxBytes;
    }

    /** Collects a registration's attributes at staging; only the registrar creates one. */
    static final class Builder {
        private final HookKind kind;
        private final String providerId;
        private final String capabilityName;
        private final Object hook;
        private Set<String> requiresNegotiated = Set.of();
        private Set<String> ownedKeys = Set.of();
        private ReplySlot slot;
        private ReplyBound replyBound;
        private EventKind eventKind;
        private Command command;
        private long maxBytes;

        Builder(HookKind kind, String providerId, String capabilityName, Object hook) {
            this.kind = kind;
            this.providerId = providerId;
            this.capabilityName = capabilityName;
            this.hook = Objects.requireNonNull(hook);
        }

        Builder requiring(Set<String> negotiated) {
            this.requiresNegotiated = Objects.requireNonNull(negotiated);
            return this;
        }

        Builder owning(Set<String> keys, long bytes) {
            this.ownedKeys = keys;
            this.maxBytes = bytes;
            return this;
        }

        Builder decorating(ReplySlot replySlot, ReplyBound bound, long bytes) {
            this.slot = replySlot;
            this.replyBound = bound;
            this.maxBytes = bytes;
            return this;
        }

        Builder onEvent(EventKind event) {
            this.eventKind = event;
            return this;
        }

        Builder forCommand(Command handled) {
            this.command = handled;
            return this;
        }

        HookRegistration build() {
            return new HookRegistration(this);
        }
    }

    public HookKind kind() {
        return kind;
    }

    public String providerId() {
        return providerId;
    }

    /** The capability the owner offers; recipients/senders must have negotiated it where gated. */
    public String capabilityName() {
        return capabilityName;
    }

    /** Further session capabilities that must be negotiated, evaluated with the owner's. */
    public Set<String> requiresNegotiated() {
        return requiresNegotiated;
    }

    /** Server tag keys this registration owns; empty for other kinds. */
    public Set<String> ownedKeys() {
        return ownedKeys;
    }

    public ReplySlot slot() {
        return slot;
    }

    public ReplyBound replyBound() {
        return replyBound;
    }

    public EventKind eventKind() {
        return eventKind;
    }

    public Command command() {
        return command;
    }

    /** The declared byte bound: tag data bytes, or the reply slot's bytes at reference limits. */
    public long maxBytes() {
        return maxBytes;
    }

    <T> T hook(Class<T> type) {
        return type.cast(hook);
    }

    CommandHandler commandHandler() {
        return hook(CommandHandler.class);
    }

    /** Whether {@code negotiated} satisfies this registration's declared session prerequisites. */
    boolean prerequisitesMet(Set<String> negotiated) {
        return negotiated.containsAll(requiresNegotiated);
    }

    /** Whether the party also negotiated the owner's capability. */
    boolean ownerNegotiated(Set<String> negotiated) {
        return negotiated.contains(capabilityName);
    }
}
