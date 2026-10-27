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

/**
 * RFC 2812 §2.2's "rfc1459" casemapping (FR-052) — IRC's Scandinavian-origin casemapping: ASCII
 * letters fold together, plus {@code [}↔{@code {}, {@code ]}↔{@code }}, {@code \}↔{@code |}, {@code
 * ^}↔{@code ~}. Used to fold nickname and channel-name comparisons; the original casing a client
 * registered/created with is always stored and displayed — only comparison folds case.
 */
public final class CaseMapping {

    private CaseMapping() {}

    public static String fold(String value) {
        return net.jircd.protocol.CaseMapping.fold(value);
    }

    /** Case-insensitive (rfc1459-folded) {@code *}/{@code ?} wildcard match (FR-052/FR-061). */
    public static boolean matches(String value, String mask) {
        return net.jircd.protocol.CaseMapping.matches(value, mask);
    }
}
