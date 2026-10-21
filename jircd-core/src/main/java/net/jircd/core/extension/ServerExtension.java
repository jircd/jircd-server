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
package net.jircd.core.extension;

import java.util.Set;
import net.jircd.core.session.ChannelMode;
import net.jircd.core.session.UserMode;

/**
 * An {@link Extension} with no client-negotiable {@link net.jircd.core.capability.Capability} —
 * administrator/operational concerns a client never sees exist directly (FR-032/FR-031). Lives
 * under {@code jircd-server-extensions/}.
 */
public interface ServerExtension extends Extension {

  /**
   * The named point this extension supplies a value/decision for (e.g. {@code cloak} claims {@code
   * hostname-display}; a future {@code gline} would claim {@code connection-admission}, FR-066), or
   * {@code null} if this extension makes no such claim (data-model.md {@code Extension
   * .extensionPoint}).
   */
  default String extensionPoint() {
    return null;
  }

  /**
   * {@code BOOLEAN}-kind channel-mode flags this extension contributes. Empty by default. LIST
   * contributions are rejected by {@link ExtensionRegistry#register} because only core list modes
   * have storage and command handling.
   */
  default Set<ChannelMode> contributedChannelModes() {
    return Set.of();
  }

  /** User-mode flags this extension contributes. Empty by default. */
  default Set<UserMode> contributedUserModes() {
    return Set.of();
  }
}
