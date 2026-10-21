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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code CAP} command's own sub-language (FR-006/FR-007/FR-008): {@code LS}/{@code REQ}/{@code
 * ACK}/{@code NAK}/{@code END} subcommands, each with a follow-on capability-list syntax.
 * Deliberately separate from the generic {@code COMMAND [params] [:trailing]} framing {@link
 * MessageParser} already handles, since {@code CAP}'s subcommands branch into genuinely different
 * follow-on syntax.
 */
public final class CapabilityNegotiationGrammar {

  public enum Subcommand {
    LS,
    LIST,
    REQ,
    ACK,
    NAK,
    END
  }

  private CapabilityNegotiationGrammar() {}

  /** Resolves a {@code CAP} message's subcommand token, or {@code null} if unrecognized. */
  public static Subcommand parseSubcommand(Message message) {
    if (message.params().isEmpty()) {
      return null;
    }
    String token = message.params().getFirst().toUpperCase(Locale.ROOT);
    try {
      return Subcommand.valueOf(token);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Splits a {@code CAP REQ}/{@code ACK}/{@code NAK} trailing capability list on spaces. */
  public static List<String> parseCapabilityList(Message message) {
    if (message.params().size() < 2) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (String token : message.params().getLast().split(" ")) {
      if (!token.isBlank()) {
        result.add(token);
      }
    }
    return result;
  }

  public static Message ls(String serverName, String target, List<String> capabilities) {
    return new Message(
        java.util.Map.of(),
        serverName,
        Command.CAP,
        "CAP",
        List.of(target, "LS", join(capabilities)));
  }

  /**
   * {@code CAP LIST} — the client's currently negotiated capabilities, distinct from {@link #ls}
   * which reports everything the server offers regardless of negotiation state
   * (005-fix-batch-conformance FR-007).
   */
  public static Message list(String serverName, String target, List<String> capabilities) {
    return new Message(
        java.util.Map.of(),
        serverName,
        Command.CAP,
        "CAP",
        List.of(target, "LIST", join(capabilities)));
  }

  public static Message ack(String serverName, String target, List<String> capabilities) {
    return new Message(
        java.util.Map.of(),
        serverName,
        Command.CAP,
        "CAP",
        List.of(target, "ACK", join(capabilities)));
  }

  public static Message nak(String serverName, String target, List<String> capabilities) {
    return new Message(
        java.util.Map.of(),
        serverName,
        Command.CAP,
        "CAP",
        List.of(target, "NAK", join(capabilities)));
  }

  private static String join(List<String> capabilities) {
    return String.join(" ", capabilities);
  }
}
