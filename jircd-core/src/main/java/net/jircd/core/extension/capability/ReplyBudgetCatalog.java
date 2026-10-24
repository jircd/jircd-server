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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reply decorations a set of providers could apply. Mutually exclusive replacement slots
 * reserve the largest declared bound across providers (any one of them may own the slot), and
 * different slots compose by summing in each layout's formula. The same checker serves startup,
 * reload and runtime enable.
 */
public record ReplyBudgetCatalog(List<ReplyContribution> contributions) {

  private static final ReplyBudgetCatalog EMPTY = new ReplyBudgetCatalog(List.of());

  public ReplyBudgetCatalog {
    contributions = List.copyOf(contributions);
  }

  public static ReplyBudgetCatalog empty() {
    return EMPTY;
  }

  /** The slot decorations declared by {@code descriptors}' hooks. */
  public static ReplyBudgetCatalog fromDescriptors(Collection<CapabilityDescriptor> descriptors) {
    List<ReplyContribution> contributions = new ArrayList<>();
    for (CapabilityDescriptor descriptor : descriptors) {
      for (HookRegistration r : descriptor.registrations()) {
        if (r.slot() != null) {
          contributions.add(new ReplyContribution(r.providerId(), r.slot(), r.replyBound()));
        }
      }
    }
    return new ReplyBudgetCatalog(contributions);
  }

  /** This catalog plus {@code other}'s contributions. */
  public ReplyBudgetCatalog plus(ReplyBudgetCatalog other) {
    List<ReplyContribution> merged = new ArrayList<>(contributions);
    merged.addAll(other.contributions);
    return new ReplyBudgetCatalog(merged);
  }

  /** Reserved bytes per slot, evaluated against {@code limits}. */
  public Map<ReplySlot, Long> slotBytes(ReplyLimits limits) {
    Map<ReplySlot, Long> bytes = new HashMap<>();
    for (ReplyContribution contribution : contributions) {
      bytes.merge(contribution.slot(), contribution.evaluate(limits), Math::max);
    }
    return bytes;
  }

  /** Worst-case bytes of one reply under this catalog. */
  public long worstCaseBytes(ReplyLayout layout, ReplyLimits limits) {
    return layout.worstCaseBytes(limits, slotBytes(limits));
  }
}
