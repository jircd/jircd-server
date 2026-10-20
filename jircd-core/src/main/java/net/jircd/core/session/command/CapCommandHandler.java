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

import java.util.List;
import java.util.function.Supplier;
import net.jircd.core.capability.CapabilityNegotiator;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.CapabilityNegotiationGrammar;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code CAP} — the {@code LS}/{@code LIST}/{@code REQ}/{@code END} negotiation entry points
 * (FR-006/FR-007), delegating the actual state machine to {@link CapabilityNegotiator}; this
 * handler only turns its results into wire replies. {@code LIST} reports the client's own currently
 * negotiated capabilities, distinct from {@code LS}'s full offered list (005-fix-batch-conformance
 * FR-007). A bare {@code CAP ACK}/{@code NAK} from a client (server-originated only, in this
 * design) is a no-op.
 */
public final class CapCommandHandler implements CommandHandler {

  private final CapabilityNegotiator negotiator;
  private final Supplier<String> serverName;
  private final RegistrationCompletion registrationCompletion;

  public CapCommandHandler(
      CapabilityNegotiator negotiator,
      Supplier<String> serverName,
      RegistrationCompletion registrationCompletion) {
    this.negotiator = negotiator;
    this.serverName = serverName;
    this.registrationCompletion = registrationCompletion;
  }

  @Override
  public void handle(ClientSession session, Message message) {
    CapabilityNegotiationGrammar.Subcommand subcommand =
        CapabilityNegotiationGrammar.parseSubcommand(message);
    if (subcommand == null) {
      String rawSubcommand = message.params().isEmpty() ? "*" : message.params().getFirst();
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_INVALIDCAPCMD,
          rawSubcommand,
          "Invalid CAP subcommand");
      return;
    }
    switch (subcommand) {
      case LS -> handleLs(session);
      case LIST -> handleList(session);
      case REQ -> handleReq(session, message);
      case END -> handleEnd(session);
      case ACK, NAK -> {
        // server-originated only in this design; nothing to do on receipt
      }
    }
  }

  private void handleLs(ClientSession session) {
    negotiator.beginNegotiation(session);
    enqueue(
        session,
        CapabilityNegotiationGrammar.ls(
            serverName.get(), target(session), negotiator.currentlyOfferedCapabilityNames()));
  }

  private void handleList(ClientSession session) {
    enqueue(
        session,
        CapabilityNegotiationGrammar.list(
            serverName.get(), target(session), List.copyOf(session.negotiatedCapabilities())));
  }

  private void handleReq(ClientSession session, Message message) {
    List<String> requested = CapabilityNegotiationGrammar.parseCapabilityList(message);
    String target = target(session);
    boolean accepted = negotiator.request(session, requested);
    enqueue(
        session,
        accepted
            ? CapabilityNegotiationGrammar.ack(serverName.get(), target, requested)
            : CapabilityNegotiationGrammar.nak(serverName.get(), target, requested));
  }

  /** The client's nickname once it has one, else {@code *}. */
  private static String target(ClientSession session) {
    String nickname = session.nickname();
    return nickname == null ? "*" : nickname;
  }

  private void handleEnd(ClientSession session) {
    negotiator.endNegotiation(session);
    registrationCompletion.tryComplete(session);
  }

  private static void enqueue(ClientSession session, Message message) {
    if (session.writer() != null) {
      session.writer().enqueueRaw(message);
    }
  }
}
