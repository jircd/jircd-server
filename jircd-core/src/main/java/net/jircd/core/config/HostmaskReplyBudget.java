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
package net.jircd.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Worst-case wire size, including CRLF and excluding message tags, of every server reply that
 * embeds a nick!user@host or a user's ident and host, for the configured limits. Free text that the
 * handlers cut to fit (a real name) is counted as empty, so the figures are what must fit before
 * the text is cut.
 *
 * <p>With {@code S} the server name bytes, {@code N} the nickname limit, {@code C} the channel
 * limit, {@code U} {@link ServerConfiguration#IDENT_MAX_BYTES} and {@code H} {@link
 * ServerConfiguration#PRESENTED_HOST_MAX_BYTES}, a hostmask is {@code N+U+H+2} bytes.
 */
public final class HostmaskReplyBudget {

  private static final int U = ServerConfiguration.IDENT_MAX_BYTES;
  private static final int H = ServerConfiguration.PRESENTED_HOST_MAX_BYTES;

  /** Epoch seconds are 10 digits until the year 2286. */
  private static final int TIMESTAMP_BYTES = 10;

  /** A WHO flags field at its widest: away, operator and channel status. */
  private static final int WHO_FLAGS_BYTES = 3;

  private HostmaskReplyBudget() {}

  /** Worst-case bytes per reply, keyed by a label naming it; callers compare to the limit. */
  public static Map<String, Integer> worstCaseBytes(
      int serverBytes, int nicknameMax, int channelMax) {
    int s = serverBytes;
    int n = nicknameMax;
    int c = channelMax;
    int hostmask = n + U + H + 2;
    Map<String, Integer> worst = new LinkedHashMap<>();
    // ":S 352 N C U H S N F :0 " + CRLF: the server name appears twice.
    worst.put("352 (WHO)", 2 * s + 2 * n + c + U + H + 13 + WHO_FLAGS_BYTES + 5);
    // ":S 333 N C nick!user@host ts" + CRLF
    worst.put("333 (TOPICWHOTIME)", s + n + c + hostmask + TIMESTAMP_BYTES + 9 + 2);
    // ":S 311 N nick user host * :" + CRLF; 314 (WHOWAS) has the same shape
    worst.put("311/314 (WHOIS/WHOWAS)", s + 2 * n + U + H + 15);
    // ":S 302 N nick*=+user@host" + CRLF, one entry; a single word takes no trailing colon
    worst.put("302 (USERHOST)", s + 2 * n + U + H + 13);
    // ":nick!user@host INVITE N C" + CRLF, the longest S-free prefix line
    worst.put("INVITE", hostmask + n + c + 12);
    return worst;
  }
}
