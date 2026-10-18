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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import net.jircd.protocol.NumericReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and sends numeric-reply {@link Message}s, always addressed to the session's current
 * nickname, or {@code *} if it hasn't claimed one yet.
 */
public final class Replies {

  private static final Logger LOG = LoggerFactory.getLogger(Replies.class);

  private Replies() {}

  public static void send(
      ClientSession session, String serverName, NumericReply numeric, String... trailingParams) {
    List<String> params = new ArrayList<>();
    params.add(target(session));
    params.addAll(List.of(trailingParams));
    Message message = new Message(Map.of(), serverName, null, numeric.wireCode(), params);
    if (session.writer() != null) {
      session.writer().enqueueRaw(message);
    }
  }

  /**
   * Sends a space-separated list across byte-budgeted numeric replies, including an empty list.
   * Unrepresentable headers and indivisible items are logged and skipped; later items still send.
   */
  public static void sendListed(
      ClientSession session,
      String serverName,
      NumericReply numeric,
      List<String> fixedParams,
      List<String> items) {
    List<String> params = new ArrayList<>();
    params.add(target(session));
    params.addAll(fixedParams);
    params.add("");
    Message empty = new Message(Map.of(), serverName, null, numeric.wireCode(), params);
    // Reserve the trailing colon even for a singleton reply, which may serialize without it.
    int headerBytes = MessageSerializer.commandBytes(empty);
    int budget = MessageSerializer.MAX_COMMAND_BYTES - headerBytes;
    String channel = fixedParams.isEmpty() ? "*" : fixedParams.getLast();
    if (budget < 0) {
      LOG.warn(
          "Dropping oversized listed reply header: numeric={}, channel={}, headerBytes={}, limit={}",
          numeric.wireCode(),
          channel,
          headerBytes,
          MessageSerializer.MAX_COMMAND_BYTES);
      return;
    }
    StringBuilder listed = new StringBuilder();
    int used = 0;
    for (String item : items) {
      int bytes = MessageSerializer.utf8Bytes(item);
      if (!listed.isEmpty() && used + 1 + bytes > budget) {
        params.set(params.size() - 1, listed.toString());
        send(session, serverName, numeric, params.subList(1, params.size()).toArray(String[]::new));
        listed.setLength(0);
        used = 0;
      }
      if (bytes > budget) {
        params.set(params.size() - 1, item);
        Message singleton = new Message(Map.of(), serverName, null, numeric.wireCode(), params);
        int singletonBytes = MessageSerializer.commandBytes(singleton);
        if (singletonBytes > MessageSerializer.MAX_COMMAND_BYTES) {
          LOG.warn(
              "Dropping oversized listed item: numeric={}, channel={}, itemBytes={}, budget={}",
              numeric.wireCode(),
              channel,
              bytes,
              MessageSerializer.MAX_COMMAND_BYTES - (singletonBytes - bytes));
          continue;
        }
      }
      if (!listed.isEmpty()) {
        listed.append(' ');
        used++;
      }
      listed.append(item);
      used += bytes;
    }
    if (!listed.isEmpty() || items.isEmpty()) {
      params.set(params.size() - 1, listed.toString());
      send(session, serverName, numeric, params.subList(1, params.size()).toArray(String[]::new));
    }
  }

  public static String target(ClientSession session) {
    return session.nickname() != null ? session.nickname() : "*";
  }
}
