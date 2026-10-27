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
import java.util.Objects;

/**
 * Identity permitted for the requester plus every held channel status symbol in PREFIX order. It
 * holds no capability flag: providers choose how to render it.
 */
public record MemberSnapshot(
        String nickname, String ident, String displayHost, List<String> heldStatuses) {

    public MemberSnapshot {
        Objects.requireNonNull(nickname);
        Objects.requireNonNull(ident);
        Objects.requireNonNull(displayHost);
        heldStatuses = List.copyOf(heldStatuses);
    }

    /** Core's default status: the highest held symbol, or none. */
    public String highestStatus() {
        return heldStatuses.isEmpty() ? "" : heldStatuses.getFirst();
    }
}
