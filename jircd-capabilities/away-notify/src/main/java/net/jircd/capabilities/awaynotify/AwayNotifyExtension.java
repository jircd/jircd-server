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
package net.jircd.capabilities.awaynotify;

import java.util.List;
import java.util.Map;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.CommittedEvent;
import net.jircd.core.extension.capability.EventIntent;
import net.jircd.core.extension.capability.EventKind;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;

/**
 * The {@code away-notify} capability (011-away-notify-batch FR-001 through FR-006): unlike {@code
 * message-tags}/{@code server-time}/{@code echo-message}, this doesn't decorate an existing fan-out
 * — it gates a wholly new one. It listens for the committed {@code AWAY_CHANGED} event and answers
 * with one {@code AWAY} notice for the peers who share a channel with the user, deduplicated and
 * without the user itself by the event's audience. Core narrows the notice to peers that negotiated
 * this capability; it keeps the away state and the confirmations to the user.
 */
public final class AwayNotifyExtension extends AbstractCapabilityExtension {

  public static final String ID = CapabilityName.AWAY_NOTIFY;

  public AwayNotifyExtension() {
    super(ID);
  }

  @Override
  public void register(CapabilityRegistrar registrar) {
    registrar.onEvent(EventKind.AWAY_CHANGED, AwayNotifyExtension::awayNotice);
  }

  /** {@code :<presented identity> AWAY [:<reason>]}; no parameter means the user is back. */
  static List<EventIntent> awayNotice(CommittedEvent event) {
    List<ClientSession> peers = event.audience().peers();
    if (peers.isEmpty()) {
      return List.of();
    }
    List<String> params = event.after().isEmpty() ? List.of() : List.of(event.after());
    Message notice = new Message(Map.of(), event.subject(), Command.AWAY, "AWAY", params);
    return List.of(new EventIntent(notice, peers));
  }
}
