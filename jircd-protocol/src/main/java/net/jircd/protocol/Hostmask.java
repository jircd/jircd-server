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
 * Formats and validates the standard {@code nickname!ident@hostname} identity form (FR-030), and
 * the nickname/username content grammars registration relies on (RFC 2812 §2.3.1,
 * contracts/irc-protocol-commands.md "Connection Registration Grammar"). Deliberately has no {@code
 * ServerConfiguration} dependency (research.md "Protocol/server boundary") — the nickname length
 * ceiling is always a caller-supplied parameter (FR-056).
 */
public final class Hostmask {

    private static final String NICK_SPECIAL_LEADING = "[]\\`_^{|}";
    private static final String NICK_SPECIAL_TRAILING = "[]\\`_^{|}-";

    private Hostmask() {}

    public static String format(String nickname, String ident, String hostname) {
        return nickname + "!" + ident + "@" + hostname;
    }

    /** RFC 2812 §2.3.1 nickname grammar, with a caller-supplied maximum length (FR-056). */
    public static boolean isValidNickname(String nickname, int maxLength) {
        if (nickname == null || nickname.isEmpty() || nickname.length() > maxLength) {
            return false;
        }
        char first = nickname.charAt(0);
        if (!(Character.isLetter(first) && first < 128)
                && NICK_SPECIAL_LEADING.indexOf(first) < 0) {
            return false;
        }
        for (int i = 1; i < nickname.length(); i++) {
            char c = nickname.charAt(i);
            boolean isLetterOrDigit = (Character.isLetter(c) || Character.isDigit(c)) && c < 128;
            if (!isLetterOrDigit && NICK_SPECIAL_TRAILING.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code <user>} parameter's content rule: any octet except NUL, CR, LF, space, and
     * {@code @} — no length limit at this layer (truncation to the ident display length is the
     * caller's concern, e.g. {@code UserCommandHandler}).
     */
    public static boolean isValidUsernameContent(String username) {
        if (username == null || username.isEmpty()) {
            return false;
        }
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            if (c == '\0' || c == '\r' || c == '\n' || c == ' ' || c == '@') {
                return false;
            }
        }
        return true;
    }
}
