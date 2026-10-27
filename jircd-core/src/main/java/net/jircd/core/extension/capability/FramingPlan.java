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

import java.util.List;
import net.jircd.protocol.Message;

/** The complete ordered messages for a group, including any opener and closer. */
public record FramingPlan(List<Message> frames) {

    public FramingPlan {
        frames = List.copyOf(frames);
    }

    /** The default: the group's members with no framing. */
    public static FramingPlan plain(OutputGroup group) {
        return new FramingPlan(group.members());
    }
}
