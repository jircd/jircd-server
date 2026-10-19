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

import java.util.List;
import java.util.Map;
import net.jircd.protocol.Message;

/** Counts outbound syntax without constructing or serializing a recipient's wire line. */
final class WireBytes {
  private WireBytes() {}

  record Tags(long entryBytes, int entries) {
    static final Tags NONE = new Tags(0, 0);

    Tags plus(Tags other) {
      return new Tags(entryBytes + other.entryBytes, entries + other.entries);
    }

    long wireBytes() {
      return entries == 0 ? 0 : entryBytes + entries + 1;
    }
  }

  static Tags tags(Map<String, String> tags) {
    long bytes = 0;
    for (var entry : tags.entrySet()) {
      bytes += utf8(entry.getKey());
      String value = entry.getValue();
      if (!value.isEmpty()) {
        bytes += 1 + utf8(value);
        for (int i = 0; i < value.length(); i++) {
          char c = value.charAt(i);
          if (c == ';' || c == ' ' || c == '\\' || c == '\r' || c == '\n') bytes++;
        }
      }
    }
    return new Tags(bytes, tags.size());
  }

  static long raw(Message message) {
    return components(
            message.prefix(),
            message.rawCommand() != null ? message.rawCommand() : message.command().name(),
            message.params())
        + tags(message.tags()).wireBytes();
  }

  static long components(String prefix, String command, List<String> params) {
    long size = 2 + utf8(command);
    if (prefix != null) size += 2 + utf8(prefix);
    for (int i = 0; i < params.size(); i++) {
      String param = params.get(i);
      size += 1 + utf8(param);
      if (i == params.size() - 1
          && (param.isEmpty() || param.contains(" ") || param.startsWith(":"))) size++;
    }
    return size;
  }

  private static long utf8(String text) {
    long bytes = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x80) bytes++;
      else if (c < 0x800) bytes += 2;
      else if (Character.isHighSurrogate(c)
          && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        bytes += 4;
        i++;
      } else if (Character.isSurrogate(c)) bytes++;
      else bytes += 3;
    }
    return bytes;
  }
}
