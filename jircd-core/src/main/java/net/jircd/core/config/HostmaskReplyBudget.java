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
import net.jircd.core.extension.capability.ReplyBudgetCatalog;
import net.jircd.core.extension.capability.ReplyLayout;
import net.jircd.core.extension.capability.ReplyLimits;
import net.jircd.protocol.MessageSerializer;

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

  private HostmaskReplyBudget() {}

  /**
   * Worst-case bytes per reply under the widths reserved before providers declared theirs; callers
   * compare to the limit.
   */
  public static Map<String, Integer> worstCaseBytes(
      int serverBytes, int nicknameMax, int channelMax) {
    return worstCaseBytes(
        serverBytes, nicknameMax, channelMax, ReplyBudgetCatalog.legacyCompatible());
  }

  /**
   * Worst-case bytes per reply, keyed by a label naming it. The channel WHO entry follows the
   * decorations in {@code catalog}; every other entry has core-owned fixed fields.
   */
  public static Map<String, Integer> worstCaseBytes(
      int serverBytes, int nicknameMax, int channelMax, ReplyBudgetCatalog catalog) {
    int s = serverBytes;
    int n = nicknameMax;
    int c = channelMax;
    int hostmask = n + U + H + 2;
    Map<String, Integer> worst = new LinkedHashMap<>();
    // ":S 352 N C U H S N F :0 " + CRLF: the server name appears twice.
    worst.put(
        "352 (WHO)",
        Math.toIntExact(catalog.worstCaseBytes(ReplyLayout.WHO_ENTRY, ReplyLimits.of(s, n, c))));
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

  /**
   * Worst-case bytes of the NAMES member and closing numerics, keyed by numeric, under {@code
   * catalog}'s status and identity decorations.
   */
  public static Map<String, Integer> namesWorstCaseBytes(
      int serverBytes, int nicknameMax, int channelMax, ReplyBudgetCatalog catalog) {
    ReplyLimits limits = ReplyLimits.of(serverBytes, nicknameMax, channelMax);
    Map<String, Integer> worst = new LinkedHashMap<>();
    worst.put("353", Math.toIntExact(catalog.worstCaseBytes(ReplyLayout.NAMES_MEMBER, limits)));
    worst.put("366", Math.toIntExact(catalog.worstCaseBytes(ReplyLayout.NAMES_END, limits)));
    return worst;
  }

  /**
   * Checks every reply this class and NAMES cover against the 512-byte command limit.
   *
   * @throws IllegalArgumentException naming each reply that does not fit
   */
  public static void requireFits(
      int serverBytes, int nicknameMax, int channelMax, ReplyBudgetCatalog catalog) {
    Map<String, Integer> all = new LinkedHashMap<>();
    all.putAll(namesWorstCaseBytes(serverBytes, nicknameMax, channelMax, catalog));
    all.putAll(worstCaseBytes(serverBytes, nicknameMax, channelMax, catalog));
    StringBuilder over = new StringBuilder();
    all.forEach(
        (reply, bytes) -> {
          if (bytes > MessageSerializer.MAX_COMMAND_BYTES) {
            if (!over.isEmpty()) over.append(", ");
            over.append(reply).append('=').append(bytes);
          }
        });
    if (!over.isEmpty()) {
      throw new IllegalArgumentException(
          "Provider reply decorations exceed the "
              + MessageSerializer.MAX_COMMAND_BYTES
              + "-byte reply limit for the configured serverName/nicknameMaxLength/"
              + "channelNameMaxLength: "
              + over);
    }
  }
}
