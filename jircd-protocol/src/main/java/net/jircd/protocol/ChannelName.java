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

/**
 * Channel name grammar (FR-048): a leading {@code #} followed by additional characters excluding
 * space, comma, and control characters, up to a caller-supplied maximum length in both UTF-16 code
 * units and UTF-8 bytes (FR-056). No {@code &}/{@code +}/{@code !} channel-type variants — {@code
 * #} only, matching {@code CHANTYPES=#} (FR-055).
 */
public final class ChannelName {

    private ChannelName() {}

    public static boolean isValid(String name, int maxLength) {
        if (name == null || name.isEmpty() || name.length() > maxLength) {
            return false;
        }
        if (MessageSerializer.utf8Bytes(name) > maxLength) {
            return false;
        }
        if (name.charAt(0) != '#') {
            return false;
        }
        if (name.length() == 1) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ' ' || c == ',' || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}
