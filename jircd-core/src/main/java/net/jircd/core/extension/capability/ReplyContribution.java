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

/** One provider's declared decoration of one reply slot. */
public record ReplyContribution(String providerId, ReplySlot slot, ReplyBound bound) {

  public ReplyContribution {
    Objects.requireNonNull(providerId);
    Objects.requireNonNull(slot);
    Objects.requireNonNull(bound);
  }

  /** The bound evaluated against {@code limits}, rejecting anything outside 0..512. */
  long evaluate(ReplyLimits limits) {
    long bytes;
    try {
      bytes = bound.maxBytes(limits);
    } catch (RuntimeException | LinkageError e) {
      throw new IllegalArgumentException(
          "Provider '" + providerId + "' reply bound for slot " + slot.name() + " failed", e);
    }
    if (bytes < 0 || bytes > net.jircd.protocol.MessageSerializer.MAX_COMMAND_BYTES) {
      throw new IllegalArgumentException(
          "Provider '"
              + providerId
              + "' reply bound for slot "
              + slot.name()
              + " must be 0..512 bytes, got "
              + bytes);
    }
    return bytes;
  }
}
