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
package net.jircd.capabilities.servertime;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.session.OutboundMessage;
import net.jircd.protocol.CapabilityName;

/**
 * The {@code server-time} capability (FR-025): contributes the {@code time} tag from {@link
 * OutboundMessage#sentAt()} — the sender's send-time instant, the same value for every recipient,
 * never each recipient's own drain time (data-model.md "OutboundMessage").
 */
public final class ServerTimeExtension extends AbstractCapabilityExtension {

  public static final String ID = CapabilityName.SERVER_TIME;

  /** IRCv3 server-time's required format: millisecond-precision, UTC, {@code Z}-suffixed. */
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  public ServerTimeExtension() {
    super(ID);
  }

  @Override
  public Map<String, String> contributeTags(OutboundMessage message) {
    return Map.of("time", TIME_FORMAT.format(message.sentAt()));
  }
}
