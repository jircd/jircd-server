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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * An immutable, coherent table of published providers and their hook indexes in deterministic
 * (provider id, then registration) order. Build a new snapshot to change anything; a captured one
 * stays valid for as long as the work using it runs.
 */
public final class RuntimeSnapshot {

  public static final RuntimeSnapshot EMPTY = new RuntimeSnapshot(Map.of(), ReplyLimits.REFERENCE);

  private final Map<String, CapabilityDescriptor> providers;
  private final Map<HookKind, List<HookRegistration>> hooks;
  private final ReplyLimits limits;

  private RuntimeSnapshot(Map<String, CapabilityDescriptor> providers, ReplyLimits limits) {
    this.limits = limits;
    this.providers = Map.copyOf(providers);
    Map<HookKind, List<HookRegistration>> index = new EnumMap<>(HookKind.class);
    for (HookKind kind : HookKind.values()) index.put(kind, new ArrayList<>());
    providers.values().stream()
        .sorted(Comparator.comparing(CapabilityDescriptor::providerId))
        .forEach(d -> d.registrations().forEach(r -> index.get(r.kind()).add(r)));
    index.replaceAll((kind, list) -> List.copyOf(list));
    this.hooks = index;
  }

  /**
   * Validates uniqueness of ids, offers, tag keys, replacement slots and commands, and that every
   * hard dependency exists and the dependency graph is acyclic.
   *
   * @throws IllegalArgumentException if the set is inconsistent; nothing is built
   */
  public static RuntimeSnapshot of(Collection<CapabilityDescriptor> descriptors) {
    Map<String, CapabilityDescriptor> byId = new TreeMap<>();
    Set<String> offers = new HashSet<>();
    Map<String, String> owners = new HashMap<>();
    for (CapabilityDescriptor descriptor : descriptors) {
      if (byId.putIfAbsent(descriptor.providerId(), descriptor) != null) {
        throw new IllegalArgumentException(
            "duplicate provider id '" + descriptor.providerId() + "'");
      }
      if (!offers.add(descriptor.capabilityName())) {
        throw new IllegalArgumentException(
            "capability '"
                + descriptor.capabilityName()
                + "' is offered by more than one provider");
      }
      for (HookRegistration registration : descriptor.registrations()) {
        claim(owners, registration, descriptor.providerId());
      }
    }
    validateDependencies(byId);
    return new RuntimeSnapshot(byId, ReplyLimits.REFERENCE);
  }

  private static void claim(Map<String, String> owners, HookRegistration r, String providerId) {
    for (String key : r.ownedKeys()) claimOne(owners, "tag key '" + key + "'", providerId);
    if (r.slot() != null) claimOne(owners, "reply slot " + r.slot().name(), providerId);
    if (r.command() != null) claimOne(owners, "command " + r.command(), providerId);
  }

  private static void claimOne(Map<String, String> owners, String what, String providerId) {
    String existing = owners.putIfAbsent(what, providerId);
    if (existing != null) {
      throw new IllegalArgumentException(
          what + " is claimed by both '" + existing + "' and '" + providerId + "'");
    }
  }

  private static void validateDependencies(Map<String, CapabilityDescriptor> byId) {
    for (CapabilityDescriptor descriptor : byId.values()) {
      for (String required : descriptor.requiresProviders()) {
        if (!byId.containsKey(required)) {
          throw new IllegalArgumentException(
              "provider '"
                  + descriptor.providerId()
                  + "' requires unknown provider '"
                  + required
                  + "'");
        }
      }
    }
    Set<String> done = new HashSet<>();
    for (String id : byId.keySet()) visit(id, byId, new HashSet<>(), done);
  }

  private static void visit(
      String id, Map<String, CapabilityDescriptor> byId, Set<String> path, Set<String> done) {
    if (done.contains(id)) return;
    if (!path.add(id)) {
      throw new IllegalArgumentException("cyclic provider dependency through '" + id + "'");
    }
    for (String required : byId.get(id).requiresProviders()) visit(required, byId, path, done);
    path.remove(id);
    done.add(id);
  }

  /** A snapshot with {@code descriptor} added. */
  public RuntimeSnapshot with(CapabilityDescriptor descriptor) {
    List<CapabilityDescriptor> all = new ArrayList<>(providers.values());
    all.add(descriptor);
    return of(all).withLimits(limits);
  }

  /**
   * A snapshot without {@code providerId}.
   *
   * @throws IllegalStateException if another published provider still requires it
   */
  public RuntimeSnapshot without(String providerId) {
    for (CapabilityDescriptor other : providers.values()) {
      if (other.requiresProviders().contains(providerId)) {
        throw new IllegalStateException(
            "provider '" + providerId + "' is required by '" + other.providerId() + "'");
      }
    }
    Map<String, CapabilityDescriptor> remaining = new HashMap<>(providers);
    remaining.remove(providerId);
    return of(remaining.values()).withLimits(limits);
  }

  /** The validated configured limits slot bounds are evaluated against at dispatch. */
  public ReplyLimits limits() {
    return limits;
  }

  /** The same providers under {@code configured} limits. */
  public RuntimeSnapshot withLimits(ReplyLimits configured) {
    return new RuntimeSnapshot(providers, Objects.requireNonNull(configured));
  }

  public Optional<CapabilityDescriptor> descriptor(String providerId) {
    return Optional.ofNullable(providers.get(providerId));
  }

  public Set<String> providerIds() {
    return providers.keySet();
  }

  /** Hooks of {@code kind} in provider-id, then registration, order. */
  public List<HookRegistration> hooks(HookKind kind) {
    return hooks.get(kind);
  }

  /**
   * Hooks of {@code kind} whose owner and declared prerequisites {@code negotiated} satisfies, in
   * the same deterministic order. Reads only this snapshot's index, never the live registry.
   */
  public List<HookRegistration> hooksFor(HookKind kind, java.util.Set<String> negotiated) {
    return hooks.get(kind).stream()
        .filter(r -> r.ownerNegotiated(negotiated) && r.prerequisitesMet(negotiated))
        .toList();
  }

  /** The reply decorations of the published providers, for budget validation. */
  public ReplyBudgetCatalog replyBudgetCatalog() {
    return ReplyBudgetCatalog.fromDescriptors(providers.values());
  }
}
