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

import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one path every disconnect trigger funnels through — a client-sent {@code QUIT}, a keep-alive
 * timeout, an {@code OPER} lockout, and an abrupt TCP-level loss all call this rather than
 * duplicating cleanup logic per trigger. Removes channel membership (including operator and voice
 * status) and notifies every affected channel — exactly once per distinct neighbor, not once per
 * shared channel.
 */
public final class DisconnectCleanup {

  private static final Logger LOG = LoggerFactory.getLogger(DisconnectCleanup.class);

  private final NicknameRegistry nicknameRegistry;
  private final ChannelRegistry channelRegistry;
  private final WhowasHistory whowasHistory;
  private final ExtensionRegistry extensionRegistry;

  public DisconnectCleanup(
      NicknameRegistry nicknameRegistry,
      ChannelRegistry channelRegistry,
      WhowasHistory whowasHistory,
      ExtensionRegistry extensionRegistry) {
    this.nicknameRegistry = nicknameRegistry;
    this.channelRegistry = channelRegistry;
    this.whowasHistory = whowasHistory;
    this.extensionRegistry = extensionRegistry;
  }

  public void cleanup(ClientSession session, String reason) {
    // Idempotency guard: two disconnect triggers can race for the same session (a client-sent
    // QUIT racing an abrupt TCP-level close of the same socket is the concrete case that
    // surfaced this — an OS-level RST can hit the read loop's IOException path before the QUIT
    // line's own cleanup call has finished, or vice versa). Whichever call actually claims the
    // CLOSING transition proceeds; every other call for this session is a no-op — otherwise a
    // WHOWAS entry (among other things) could be recorded twice for one logical disconnect.
    if (!session.lifecycle().closeIfNotAlreadyClosing()) {
      return;
    }
    ConnectionMonitorLog.disconnected(
        session.connectionId(),
        java.time.Duration.between(session.connectedAt(), java.time.Instant.now()),
        reason);
    Set<ClientSession> neighbors = channelRegistry.leaveAll(session);

    if (session.nickname() != null && session.ident() != null && session.realHostname() != null) {
      String prefix = PresentedIdentity.presentedForm(session, extensionRegistry);
      Message quit =
          new Message(java.util.Map.of(), prefix, Command.QUIT, "QUIT", java.util.List.of(reason));
      Recipients.deliver(neighbors, quit);
      // One WHOWAS entry per disconnection, regardless of
      // cause (QUIT, KILL, keep-alive timeout) — this is the single point every cause funnels
      // through, so no cause-specific recording is needed anywhere else. presentedHostname is a
      // snapshot of whatever cloak was displaying for this session right now, not a live
      // recomputation against cloak's state whenever WHOWAS is later queried (research.md
      // "Cloak extension boundary" — the same live-vs-cached distinction that section already
      // establishes for a still-connected session doesn't apply here; there's no "live" state
      // left to check once this session is gone).
      whowasHistory.record(
          new WhowasEntry(
              session.nickname(),
              session.ident(),
              session.realHostname(),
              PresentedIdentity.displayHostname(session, extensionRegistry),
              session.realname(),
              java.time.Instant.now()));
    }

    if (session.nickname() != null) {
      nicknameRegistry.release(session.nickname(), session);
    }

    // The CLOSING transition already happened at the top of this method (the idempotency guard)
    // — nothing left to do here but close the writer.
    // This session's own writer — this is exactly where it gets closed; PMD's
    // CloseResource heuristic doesn't credit a conditional close() here.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer = session.writer();
    if (writer != null) {
      writer.close();
    }
    LOG.debug("Cleaned up connection {} ({})", session.connectionId(), reason);
  }
}
