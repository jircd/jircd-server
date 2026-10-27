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

import java.util.List;
import net.jircd.protocol.ModeChange;

/**
 * Formats a list of applied {@link ModeChange}s back into a modestring (e.g. {@code +ov-b}),
 * grouping consecutive same-sign changes — shared by {@link UserModeCommandHandler} and {@link
 * ModeCommandHandler} since both echo only what was actually applied, never the originally
 * requested set.
 */
final class ModeEcho {

    private ModeEcho() {}

    static String format(List<ModeChange> changes) {
        StringBuilder sb = new StringBuilder();
        char currentSign = 0;
        for (ModeChange change : changes) {
            if (change.sign() != currentSign) {
                sb.append(change.sign());
                currentSign = change.sign();
            }
            sb.append(change.flag());
        }
        return sb.toString();
    }
}
