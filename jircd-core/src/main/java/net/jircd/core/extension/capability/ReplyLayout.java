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

import java.util.Map;
import java.util.Set;

/**
 * The schema of one core reply: its fixed bytes, the slots providers may decorate and the formula
 * for its worst-case size including CRLF. Core owns the fixed fields and fit policy.
 *
 * <p>With {@code S} the server name bytes, {@code N}/{@code C} the nickname and channel limits,
 * {@code U}/{@code H} the ident and presented-host maxima, {@code P} the status-slot bytes (at
 * least the core highest-status byte) and {@code I} the identity bytes beyond the nickname.
 */
public final class ReplyLayout {

  /** Core's highest-status default is at most one byte. */
  static final long CORE_STATUS_BYTES = 1;

  /** One 353 member token: {@code S + 2N + C + 13 + P + I}. */
  public static final ReplyLayout NAMES_MEMBER =
      new ReplyLayout(
          "353 (NAMES)",
          Set.of(ReplySlot.STATUS, ReplySlot.IDENTITY),
          (l, slots) ->
              Math.addExact(
                  Math.addExact(
                      l.serverBytes() + 2L * l.nicknameMax() + l.channelMax() + 13, status(slots)),
                  slots.getOrDefault(ReplySlot.IDENTITY, 0L)));

  /** The closing 366: {@code S + N + C + 29}. */
  public static final ReplyLayout NAMES_END =
      new ReplyLayout(
          "366 (ENDOFNAMES)",
          Set.of(),
          (l, slots) -> (long) l.serverBytes() + l.nicknameMax() + l.channelMax() + 29);

  /** A channel 352: {@code 2S + 2N + C + U + H + 18 + F} where {@code F = 2 + P}. */
  public static final ReplyLayout WHO_ENTRY =
      new ReplyLayout(
          "352 (WHO)",
          Set.of(ReplySlot.STATUS),
          (l, slots) ->
              2L * l.serverBytes()
                  + 2L * l.nicknameMax()
                  + l.channelMax()
                  + l.identMax()
                  + l.hostMax()
                  + 18
                  + 2
                  + status(slots));

  @FunctionalInterface
  private interface Formula {
    long bytes(ReplyLimits limits, Map<ReplySlot, Long> slotBytes);
  }

  private final String name;
  private final Set<ReplySlot> slots;
  private final Formula formula;

  private ReplyLayout(String name, Set<ReplySlot> slots, Formula formula) {
    this.name = name;
    this.slots = slots;
    this.formula = formula;
  }

  private static long status(Map<ReplySlot, Long> slots) {
    return Math.max(CORE_STATUS_BYTES, slots.getOrDefault(ReplySlot.STATUS, 0L));
  }

  public String name() {
    return name;
  }

  public Set<ReplySlot> slots() {
    return slots;
  }

  /** Worst-case bytes including CRLF given each slot's reserved bytes. */
  public long worstCaseBytes(ReplyLimits limits, Map<ReplySlot, Long> slotBytes) {
    return formula.bytes(limits, slotBytes);
  }
}
