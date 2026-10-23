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

/**
 * An immutable fact about a state transition that has already committed. {@code before}/{@code
 * after} are empty strings when the transition has no such value.
 */
public record CommittedEvent(
    EventKind kind,
    long operationId,
    String subject,
    String before,
    String after,
    Audience audience) {

  public CommittedEvent {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(subject);
    Objects.requireNonNull(before);
    Objects.requireNonNull(after);
    Objects.requireNonNull(audience);
  }
}
