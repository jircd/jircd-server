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
package net.jircd.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a {@code MODE} command's leading modestring argument(s) — e.g. {@code +bbb-o}, or the
 * RFC-permitted {@code +b} {@code -o} split across multiple arguments — into an ordered {@link
 * ModeChange} list (FR-064, RFC 2812 §3.2.3: {@code *( ( "-" / "+" ) *<modes> )}). No knowledge of
 * what any flag means or whether it needs a parameter — that's {@code jircd-core}'s concern.
 */
public final class ModeStringParser {

    private ModeStringParser() {}

    public static List<ModeChange> parse(String... modeArgs) {
        List<ModeChange> changes = new ArrayList<>();
        char sign = '+';
        for (String arg : modeArgs) {
            if (arg == null || arg.isEmpty()) {
                continue;
            }
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c == '+' || c == '-') {
                    sign = c;
                } else {
                    changes.add(new ModeChange(sign, c));
                }
            }
        }
        return List.copyOf(changes);
    }
}
