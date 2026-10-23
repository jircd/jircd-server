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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;
import net.jircd.protocol.MessageSerializer;

/**
 * Where a capability provider stages its typed hooks. Nothing is visible to the server until the
 * descriptor from {@link #build()} is published; closing the registrar (or a {@link Registration})
 * before that discards the staged hooks. A staged registration is bounded and validated here, so a
 * provider cannot register something the dispatcher would have to distrust on every call.
 *
 * <p>Each hook is gated centrally: the dispatcher applies the owner's enabled state and the
 * declared negotiation prerequisites, so providers never read mutable session capability sets.
 * Hooks must be pure, nonblocking and thread-safe, and may not perform I/O.
 */
public final class CapabilityRegistrar {
  public static final int MAX_REGISTRATIONS = 64;
  public static final int MAX_REQUIRED_PROVIDERS = 16;
  public static final int MAX_PREREQUISITES = 8;
  public static final int MAX_OWNED_KEYS = 16;

  /** The offer core advertises itself; no provider may claim it. */
  static final String CORE_CAP_NOTIFY = "cap-notify";

  /** Tag owned by the batch shaper, never by an ordinary tag contributor. */
  static final String BATCH_TAG = "batch";

  private enum State {
    OPEN,
    BUILT,
    CLOSED
  }

  private final String providerId;
  private final String capabilityName;
  private final Set<ReplySlot> fieldSlots;
  private final List<HookRegistration> staged = new ArrayList<>();
  private final Set<String> requiredProviders = new LinkedHashSet<>();
  private State state = State.OPEN;

  public CapabilityRegistrar(String providerId, String capabilityName) {
    this(providerId, capabilityName, Set.of());
  }

  /** {@code fieldSlots} are the extra schema-declared slots {@link #onReplyField} may target. */
  public CapabilityRegistrar(String providerId, String capabilityName, Set<ReplySlot> fieldSlots) {
    this.providerId = Tokens.require(providerId, "provider id");
    this.capabilityName = Tokens.require(capabilityName, "capability name");
    if (CORE_CAP_NOTIFY.equals(capabilityName) || CORE_CAP_NOTIFY.equals(providerId)) {
      throw new IllegalArgumentException("'" + CORE_CAP_NOTIFY + "' is a core capability");
    }
    this.fieldSlots = Set.copyOf(fieldSlots);
  }

  /** Declares hard availability dependencies on other providers; self, duplicates rejected. */
  public synchronized void requiresProviders(String... providerIds) {
    requireOpen();
    // Validated as a whole against a copy, so a rejected call stages none of its ids.
    Set<String> candidate = new LinkedHashSet<>(requiredProviders);
    for (String id : providerIds) {
      Tokens.require(id, "required provider id");
      if (id.equals(providerId)) {
        throw new IllegalArgumentException("provider '" + id + "' cannot require itself");
      }
      if (candidate.contains(id)) {
        throw new IllegalArgumentException("duplicate required provider '" + id + "'");
      }
      if (candidate.size() >= MAX_REQUIRED_PROVIDERS) {
        throw new IllegalStateException(
            "more than " + MAX_REQUIRED_PROVIDERS + " required providers");
      }
      candidate.add(id);
    }
    requiredProviders.addAll(candidate);
  }

  public Registration onEvent(EventKind kind, Hooks.EventHook hook) {
    Objects.requireNonNull(kind);
    return stage(builder(HookKind.EVENT, hook).onEvent(kind));
  }

  public Registration onInboundTags(Hooks.InboundTagHook hook) {
    return onInboundTags(Set.of(), hook);
  }

  /** {@code requiresNegotiated} are sender prerequisites; unmet means the default (ignore). */
  public Registration onInboundTags(Set<String> requiresNegotiated, Hooks.InboundTagHook hook) {
    return stage(builder(HookKind.INBOUND_TAGS, hook).requiring(requiresNegotiated));
  }

  public Registration onRecipients(Hooks.RecipientHook hook) {
    return stage(builder(HookKind.RECIPIENTS, hook));
  }

  /**
   * Registers server tag contributions for recipients that negotiated the owner. {@code ownedKeys}
   * are the only keys the hook may return; {@code maxBytes} bounds the contribution's escaped tag
   * data bytes.
   */
  public Registration onOutboundTags(
      Set<String> ownedKeys, long maxBytes, Hooks.OutboundTagHook hook) {
    Objects.requireNonNull(hook);
    Objects.requireNonNull(ownedKeys);
    if (ownedKeys.isEmpty() || ownedKeys.size() > MAX_OWNED_KEYS) {
      throw new IllegalArgumentException("owned tag keys must number 1-" + MAX_OWNED_KEYS);
    }
    for (String key : ownedKeys) {
      Tokens.require(key, "tag key");
      if (BATCH_TAG.equals(key)) {
        throw new IllegalArgumentException("the 'batch' tag belongs to the batch shaper");
      }
    }
    if (maxBytes < 1 || maxBytes > MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN) {
      throw new IllegalArgumentException(
          "tag bound must be 1-" + MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN + " bytes");
    }
    return stage(builder(HookKind.OUTBOUND_TAGS, hook).owning(Set.copyOf(ownedKeys), maxBytes));
  }

  public Registration onMemberStatus(ReplyBound bound, Hooks.MemberStatusHook hook) {
    return stageSlot(HookKind.MEMBER_STATUS, ReplySlot.STATUS, bound, hook);
  }

  public Registration onNamesIdentity(ReplyBound bound, Hooks.NamesIdentityHook hook) {
    return stageSlot(HookKind.NAMES_IDENTITY, ReplySlot.IDENTITY, bound, hook);
  }

  public Registration onReplyField(ReplySlot slot, ReplyBound bound, Hooks.ReplyFieldHook hook) {
    Objects.requireNonNull(slot);
    if (!fieldSlots.contains(slot)) {
      throw new IllegalArgumentException(
          "reply slot '" + slot.name() + "' is not declared by the reply schema");
    }
    return stageSlot(HookKind.REPLY_FIELD, slot, bound, hook);
  }

  /** {@code requiresNegotiated} are session prerequisites; unmet means plain members. */
  public Registration onOutputGroup(Set<String> requiresNegotiated, Hooks.OutputGroupHook hook) {
    return stage(builder(HookKind.OUTPUT_GROUP, hook).requiring(requiresNegotiated));
  }

  public Registration command(Command command, CommandHandler handler) {
    Objects.requireNonNull(command);
    return stage(builder(HookKind.COMMAND, handler).forCommand(command));
  }

  /** Freezes the staged hooks into an immutable descriptor; allowed once. */
  public synchronized CapabilityDescriptor build() {
    requireOpen();
    state = State.BUILT;
    return new CapabilityDescriptor(
        providerId, capabilityName, requiredProviders, List.copyOf(staged));
  }

  /** Discards everything staged and refuses further staging. Idempotent; no-op once built. */
  public synchronized void close() {
    if (state == State.OPEN) {
      state = State.CLOSED;
      staged.clear();
      requiredProviders.clear();
    }
  }

  private Registration stageSlot(HookKind kind, ReplySlot slot, ReplyBound bound, Object hook) {
    Objects.requireNonNull(bound);
    Objects.requireNonNull(hook);
    long bytes = new ReplyContribution(providerId, slot, bound).evaluate(ReplyLimits.REFERENCE);
    return stage(builder(kind, hook).decorating(slot, bound, bytes));
  }

  private HookRegistration.Builder builder(HookKind kind, Object hook) {
    return new HookRegistration.Builder(kind, providerId, capabilityName, hook);
  }

  private synchronized Registration stage(HookRegistration.Builder builder) {
    requireOpen();
    if (staged.size() >= MAX_REGISTRATIONS) {
      throw new IllegalStateException("more than " + MAX_REGISTRATIONS + " registrations");
    }
    HookRegistration registration = builder.build();
    if (registration.requiresNegotiated().size() > MAX_PREREQUISITES) {
      throw new IllegalArgumentException("more than " + MAX_PREREQUISITES + " prerequisites");
    }
    for (String prerequisite : registration.requiresNegotiated()) {
      Tokens.require(prerequisite, "prerequisite");
    }
    rejectInternalConflict(registration);
    staged.add(registration);
    return () -> discard(registration);
  }

  private void rejectInternalConflict(HookRegistration candidate) {
    for (HookRegistration existing : staged) {
      if (candidate.slot() != null && candidate.slot().equals(existing.slot())) {
        throw new IllegalArgumentException(
            "reply slot " + candidate.slot().name() + " is already registered");
      }
      if (candidate.kind() == HookKind.OUTPUT_GROUP && existing.kind() == HookKind.OUTPUT_GROUP) {
        throw new IllegalArgumentException("only one output group shaper per provider");
      }
      if (candidate.command() != null && candidate.command() == existing.command()) {
        throw new IllegalArgumentException(
            "command " + candidate.command() + " already registered");
      }
      for (String key : candidate.ownedKeys()) {
        if (existing.ownedKeys().contains(key)) {
          throw new IllegalArgumentException("tag key '" + key + "' already registered");
        }
      }
    }
  }

  private synchronized void discard(HookRegistration registration) {
    if (state == State.OPEN) staged.removeIf(candidate -> candidate == registration);
  }

  private void requireOpen() {
    if (state != State.OPEN) {
      throw new IllegalStateException("registrar for '" + providerId + "' is no longer staging");
    }
  }
}
