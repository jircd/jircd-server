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
package net.jircd.core.session.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Admits at most one log entry per key per interval, counting the entries it suppressed so the next
 * admitted one can report them. Memory is bounded: past {@code maxKeys} the least recently used key
 * is forgotten, which at worst lets that key log once more.
 */
public final class WarningThrottle {

  private static final class State {
    long lastAdmittedNanos;
    long suppressed;
  }

  private final long intervalNanos;
  private final LongSupplier nanoClock;
  private final Map<String, State> states;

  public WarningThrottle(long intervalNanos, int maxKeys, LongSupplier nanoClock) {
    this.intervalNanos = intervalNanos;
    this.nanoClock = nanoClock;
    this.states =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
            return size() > maxKeys;
          }
        };
  }

  /**
   * Returns {@code -1} when the entry for {@code key} must be suppressed, otherwise the number of
   * entries suppressed for it since the last admitted one (0 for a first occurrence).
   */
  public synchronized long admit(String key) {
    long now = nanoClock.getAsLong();
    State state = states.get(key);
    if (state == null) {
      state = new State();
      state.lastAdmittedNanos = now;
      states.put(key, state);
      return 0;
    }
    if (now - state.lastAdmittedNanos < intervalNanos) {
      state.suppressed++;
      return -1;
    }
    long suppressed = state.suppressed;
    state.suppressed = 0;
    state.lastAdmittedNanos = now;
    return suppressed;
  }

  /** Forgets every key, so the next occurrence of each is logged again. */
  public synchronized void reset() {
    states.clear();
  }
}
