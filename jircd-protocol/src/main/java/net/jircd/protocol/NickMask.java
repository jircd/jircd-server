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
 * Ban-mask ({@code nick!user@host}) normalization and matching (FR-062). Deliberately agnostic to
 * which hostname value ({@code presentedForm} or real) the caller passes to {@link #matches(String,
 * String)} — callers that need to check both call it twice, once per identity.
 */
public final class NickMask {

  private NickMask() {}

  /** Fills a partial mask's missing {@code user}/{@code host} segment(s) with {@code *}. */
  public static String normalize(String mask) {
    String nick;
    String user;
    String host;

    int at = mask.indexOf('@');
    String beforeAt = at < 0 ? mask : mask.substring(0, at);
    host = at < 0 ? "*" : mask.substring(at + 1);

    int bang = beforeAt.indexOf('!');
    if (bang < 0) {
      nick = beforeAt;
      user = "*";
    } else {
      nick = beforeAt.substring(0, bang);
      user = beforeAt.substring(bang + 1);
    }

    if (nick.isEmpty()) {
      nick = "*";
    }
    if (user.isEmpty()) {
      user = "*";
    }
    if (host.isEmpty()) {
      host = "*";
    }
    return nick + "!" + user + "@" + host;
  }

  /**
   * Case-insensitive (rfc1459-folded) {@code *}/{@code ?} wildcard match of a full {@code
   * nickname!ident@hostname} identity string against a normalized mask.
   */
  public static boolean matches(String identity, String mask) {
    return CaseMapping.matches(identity, mask);
  }
}
