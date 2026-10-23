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

import java.util.concurrent.atomic.AtomicReference;

/**
 * The live holder of the current {@link RuntimeSnapshot}. Publication is serialized and atomic:
 * readers see the old or the new snapshot, and a rejected change leaves the old one in place.
 */
public final class CapabilityRuntime {
  private final AtomicReference<RuntimeSnapshot> snapshot =
      new AtomicReference<>(RuntimeSnapshot.EMPTY);

  public RuntimeSnapshot snapshot() {
    return snapshot.get();
  }

  /** Replaces the configured limits slot bounds are evaluated against; providers unchanged. */
  public synchronized void setLimits(ReplyLimits limits) {
    snapshot.set(snapshot.get().withLimits(limits));
  }

  /**
   * Publishes {@code descriptor}.
   *
   * @throws IllegalArgumentException if it conflicts with a published provider; nothing changes
   */
  public synchronized RuntimeSnapshot publish(CapabilityDescriptor descriptor) {
    RuntimeSnapshot next = snapshot.get().with(descriptor);
    snapshot.set(next);
    return next;
  }

  /**
   * Withdraws {@code providerId} if published.
   *
   * @throws IllegalStateException if a published provider still requires it
   */
  public synchronized RuntimeSnapshot withdraw(String providerId) {
    RuntimeSnapshot current = snapshot.get();
    if (current.descriptor(providerId).isEmpty()) return current;
    RuntimeSnapshot next = current.without(providerId);
    snapshot.set(next);
    return next;
  }
}
