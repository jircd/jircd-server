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
 * A named, non-closed user-mode flag (FR-044) — {@code id} is the stable identifier, {@code flag}
 * the wire letter, {@code definedBy} either {@code "CORE"} or a claiming extension's id, and {@code
 * clientSettable} whether a session may set the {@code +} direction on itself with no privilege
 * check ({@code invisible} may; {@code operator} may only be set by the {@code OPER} grant itself).
 */
public record UserMode(String id, char flag, String definedBy, boolean clientSettable) {

    public static final String CORE = "CORE";

    public static final UserMode OPERATOR = new UserMode("operator", 'o', CORE, false);
    public static final UserMode INVISIBLE = new UserMode("invisible", 'i', CORE, true);
    public static final UserMode WALLOPS = new UserMode("wallops", 'w', CORE, true);

    /** This release's core catalog (FR-044) — extensions may contribute more later. */
    public static final Set<UserMode> CORE_CATALOG = Set.of(OPERATOR, INVISIBLE, WALLOPS);
}
