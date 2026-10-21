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

import java.util.Objects;

/**
 * One channel-state change a {@code MODE} requester can ask for. The set of kinds is closed: {@link
 * Channel#applyAsOperator} is the only way to apply one, and it checks the requester's operator
 * status in the same step as the mutation.
 */
public sealed interface ChannelModeChange {

  /** Sets or clears a boolean mode; any other mode kind is rejected. */
  record Flag(ChannelMode mode, boolean set) implements ChannelModeChange {
    public Flag {
      Objects.requireNonNull(mode);
      if (mode.kind() != ChannelMode.Kind.BOOLEAN) {
        throw new IllegalArgumentException("not a boolean mode: " + mode.flag());
      }
    }
  }

  /**
   * Sets the member limit to a positive value, or clears it with {@code 0}; a negative value is
   * rejected.
   */
  record Limit(int limit) implements ChannelModeChange {
    public Limit {
      if (limit < 0) {
        throw new IllegalArgumentException("negative member limit: " + limit);
      }
    }
  }

  /** Sets the channel key, or clears it with {@code null}. */
  record Key(String key) implements ChannelModeChange {}

  /** Adds a mask to a channel list mode; duplicates are successful no-ops. */
  record ListAdd(ChannelMode mode, BanEntry entry) implements ChannelModeChange {
    public ListAdd {
      Objects.requireNonNull(mode);
      Objects.requireNonNull(entry);
    }
  }

  /** Removes a mask from a channel list mode using RFC1459 case folding. */
  record ListRemove(ChannelMode mode, String mask) implements ChannelModeChange {
    public ListRemove {
      Objects.requireNonNull(mode);
      Objects.requireNonNull(mask);
    }
  }

  /** Grants or revokes a per-member privilege for a target. */
  record Privilege(ClientSession target, Channel.MemberPrivilege privilege, boolean granted)
      implements ChannelModeChange {
    public Privilege {
      Objects.requireNonNull(target);
      Objects.requireNonNull(privilege);
    }
  }
}
