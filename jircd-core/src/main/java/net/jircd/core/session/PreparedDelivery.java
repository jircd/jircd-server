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
import net.jircd.core.extension.capability.RuntimeSnapshot;

/**
 * A delivery ready to queue: the validated frames plus the runtime view they were prepared under.
 * Ordinary output is appended as prepared without revalidating against current state; a state
 * change may overtake it.
 */
public record PreparedDelivery(FrameSequence frames, RuntimeSnapshot view) {

  public PreparedDelivery {
    Objects.requireNonNull(frames);
    Objects.requireNonNull(view);
  }
}
