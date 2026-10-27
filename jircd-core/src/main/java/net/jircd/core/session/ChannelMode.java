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

import java.util.Set;

/**
 * A named, non-closed channel-mode flag (FR-043) — {@code id} the stable identifier, {@code flag}
 * the wire letter, {@code kind} its shape ({@code BOOLEAN}/{@code VALUE_SET_ONLY}/{@code
 * VALUE_ALWAYS}/{@code LIST}/{@code MEMBER}, of which only {@code BOOLEAN} has a generic extension
 * mechanism this release), {@code gates} which command(s) it restricts (independent of {@code
 * kind}), and {@code definedBy} either {@code "CORE"} or a claiming extension's id. A future {@code
 * ServerExtension} can contribute an additional {@code BOOLEAN}-kind flag without a core-codebase
 * change (research.md "Channel/user mode extensibility").
 */
public record ChannelMode(
        String id, char flag, Kind kind, Set<GateAction> gates, String definedBy) {

    public static final String CORE = "CORE";

    /**
     * {@code VALUE_SET_ONLY} (a parameter is present when setting, absent when unsetting — {@code
     * l}'s shape) and {@code VALUE_ALWAYS} (a parameter is present on both setting and unsetting —
     * {@code k}'s shape, since a client must name which key it's clearing, the same "must name what
     * you're removing" reasoning {@code LIST}-kind {@code b} already follows) replace a single,
     * previously-unused {@code VALUE} kind (006-complete-core-protocol, research.md "Story 1") —
     * the distinction {@code SupportedFeatures.formatChanModes()}'s {@code CHANMODES} groups
     * already reserved before either mode existed.
     */
    public enum Kind {
        BOOLEAN,
        VALUE_SET_ONLY,
        VALUE_ALWAYS,
        LIST,
        MEMBER
    }

    public ChannelMode {
        gates = Set.copyOf(gates);
    }
}
