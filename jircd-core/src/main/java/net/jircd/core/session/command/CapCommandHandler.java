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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.jircd.core.capability.CapabilityNegotiator;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.CapabilityNegotiationGrammar;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import net.jircd.protocol.NumericReply;

/**
 * {@code CAP} — the {@code LS}/{@code LIST}/{@code REQ}/{@code END} negotiation entry points
 * (FR-006/FR-007), delegating the actual state machine to {@link CapabilityNegotiator}; this
 * handler only turns its results into wire replies. {@code LIST} reports the client's own currently
 * negotiated capabilities, distinct from {@code LS}'s full offered list (005-fix-batch-conformance
 * FR-007). A bare {@code CAP ACK}/{@code NAK} from a client (server-originated only, in this
 * design) is a no-op.
 *
 * <p>{@code LS}, {@code LIST} and {@code REQ} each read state, change it and queue their reply
 * under the administrative mutex runtime changes publish under, so none of them can observe a
 * half-applied prune or lose a concurrent update, and each reply is queued in order with any {@code
 * CAP NEW}/{@code DEL}. Replies to a client that indicated {@code CAP LS 302} use continuation
 * lines when needed; an older client always gets one line.
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
      Replies.sendFitted(
          session,
          serverName.get(),
          NumericReply.ERR_INVALIDCAPCMD,
          List.of(rawSubcommand),
          "Invalid CAP subcommand");
      return;
    }
    switch (subcommand) {
      case LS -> handleLs(session, message);
      case LIST -> handleList(session);
      case REQ -> handleReq(session, message);
      case END -> handleEnd(session);
      case ACK, NAK -> {
        // server-originated only in this design; nothing to do on receipt
      }
    }
  }

  private static boolean continuation(ClientSession session) {
    return session.highestCapVersion() >= CapabilityNegotiationGrammar.NOTIFY_VERSION;
  }

  private void handleLs(ClientSession session, Message message) {
    negotiator.beginNegotiation(session);
    int version = CapabilityNegotiationGrammar.parseLsVersion(message);
    String server = serverName.get();
    negotiator.runUnderControl(
        () -> {
          session.recordCapVersion(version);
          String target = Replies.target(session);
          List<String> offered = negotiator.currentlyOfferedCapabilityNames();
          // Enable rejects an offer set that cannot fit one line for an older client at the
          // configured nickname length; a later longer limit still never truncates the list.
          boolean continuation =
              continuation(session)
                  || !CapabilityNegotiationGrammar.fitsOneLine(server, target, "LS", offered);
          enqueueAll(
              session,
              CapabilityNegotiationGrammar.lsReplies(server, target, offered, continuation));
        });
  }

  private void handleList(ClientSession session) {
    String server = serverName.get();
    negotiator.runUnderControl(
        () -> {
          String target = Replies.target(session);
          List<String> held = new ArrayList<>(session.negotiatedCapabilities());
          held.sort(Comparator.naturalOrder());
          // A held set an older client could not get on one line still reaches it whole.
          boolean continuation =
              continuation(session)
                  || !CapabilityNegotiationGrammar.fitsOneLine(server, target, "LIST", held);
          enqueueAll(
              session,
              CapabilityNegotiationGrammar.listReplies(server, target, held, continuation));
        });
  }

  /**
   * The reply is built and size-checked before any state changes: an echo that cannot fit the
   * 512-byte outbound limit would be dropped by the writer after the change, leaving the client
   * unaware of it, so that case gets {@code 417} and changes nothing. An empty request is never
   * ACKed. Validation, the size check, the change and the reply happen in one step under the
   * administrative mutex.
   */
  private void handleReq(ClientSession session, Message message) {
    List<String> requested = CapabilityNegotiationGrammar.parseCapabilityList(message);
    String server = serverName.get();
    negotiator.runUnderControl(
        () -> {
          String target = Replies.target(session);
          Optional<Set<String>> candidate = negotiator.candidate(session, requested);
          Message reply =
              candidate.isPresent()
                  ? CapabilityNegotiationGrammar.ack(server, target, requested)
                  : CapabilityNegotiationGrammar.nak(server, target, requested);
          if (MessageSerializer.commandBytes(reply) > MessageSerializer.MAX_COMMAND_BYTES) {
            Replies.sendFitted(
                session,
                server,
                NumericReply.ERR_INPUTTOOLONG,
                List.of(),
                "Input line was too long");
            return;
          }
          candidate.ifPresent(session::replaceNegotiatedCapabilities);
          enqueue(session, reply);
        });
  }

  private void handleEnd(ClientSession session) {
    negotiator.endNegotiation(session);
    registrationCompletion.tryComplete(session);
  }

  private static void enqueueAll(ClientSession session, List<Message> messages) {
    messages.forEach(message -> enqueue(session, message));
  }

  private static void enqueue(ClientSession session, Message message) {
    if (session.writer() != null) {
      session.writer().enqueueRaw(message);
    }
  }
}
