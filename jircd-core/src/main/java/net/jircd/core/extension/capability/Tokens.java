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

/** Protocol-safe identifier rules shared by provider ids, capability names and tag keys. */
final class Tokens {
  static final int MAX_LENGTH = 64;

  private Tokens() {}

  static boolean isSafe(String token) {
    if (token == null || token.isEmpty() || token.length() > MAX_LENGTH) return false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '.'
              || c == '/'
              || c == '_';
      if (!ok) return false;
    }
    return true;
  }

  static String require(String token, String what) {
    if (!isSafe(token)) {
      throw new IllegalArgumentException(
          what + " must be 1-" + MAX_LENGTH + " characters of [A-Za-z0-9._/-]");
    }
    return token;
  }
}
