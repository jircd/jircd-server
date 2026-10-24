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

/**
 * Committed state transitions a capability may observe.
 *
 * <p>Ordering: events caused by one session's own commands are published in the order that session
 * issued them, because its commands run serially on its connection thread and each event is
 * published after its transition. An event another session causes about it ({@link #KICKED}) has no
 * order relative to those, and nothing is ordered across subjects; {@code operationId} is not a
 * global sequence, and a listener must not infer one.
 */
public enum EventKind {
  AWAY_CHANGED,
  JOINED,
  PARTED,
  /** A channel operator removed {@code subject}; {@code actor} is the kicker. */
  KICKED,
  QUIT,
  NICK_CHANGED
}
