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
package net.jircd.core.capability;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.CapabilityNegotiationGrammar;

/**
 * The {@code CAP LS}/{@code REQ}/{@code ACK}/{@code NAK}/{@code END} state machine
 * (FR-006/FR-007/FR-008). Gates registration completion while a negotiation is in progress. Always
 * available — never one of the toggleable extensions itself (FR-035); the offered/accepted list is
 * sourced live from {@link ExtensionRegistry}, never cached (FR-007).
 */
public final class CapabilityNegotiator {

  private final ExtensionRegistry extensionRegistry;

  public CapabilityNegotiator(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  public void beginNegotiation(ClientSession session) {
    session.setNegotiating(true);
  }

  public List<String> currentlyOfferedCapabilityNames() {
    return extensionRegistry.offeredCapabilities().stream().map(Capability::name).toList();
  }

  /**
   * Whether every requested name (a {@code -name} removal is checked by its bare name) is currently
   * offered. Changes nothing, so the caller can size the reply before committing.
   */
  public boolean isAcceptable(List<String> requested) {
    Set<String> offered = new HashSet<>(currentlyOfferedCapabilityNames());
    return requested.stream().allMatch(name -> offered.contains(bareName(name)));
  }

  /**
   * Applies a {@code CAP REQ} atomically: the session changes only if {@link #isAcceptable}, and
   * then through one swap of the whole negotiated set. Returns whether the request was accepted;
   * the {@code ACK}/{@code NAK} echo is the caller's, built from the client's original list so it
   * keeps order and duplicates (005-fix-batch-conformance FR-009).
   */
  public boolean request(ClientSession session, List<String> requested) {
    if (!isAcceptable(requested)) {
      return false;
    }
    apply(session, requested);
    return true;
  }

  /** Applies already-validated additions and removals as a single set replacement. */
  public void apply(ClientSession session, List<String> requested) {
    Set<String> updated = new HashSet<>(session.negotiatedCapabilities());
    for (String name : requested) {
      if (isRemoval(name)) {
        updated.remove(bareName(name));
      } else {
        updated.add(name);
      }
    }
    session.replaceNegotiatedCapabilities(updated);
  }

  private static boolean isRemoval(String name) {
    return name.startsWith("-");
  }

  private static String bareName(String name) {
    return isRemoval(name) ? name.substring(1) : name;
  }

  public void endNegotiation(ClientSession session) {
    session.setNegotiating(false);
  }

  /**
   * Whether {@code CAP} params name a recognized subcommand at all — for a generic
   * malformed-command fallback.
   */
  public static boolean isRecognizedSubcommand(net.jircd.protocol.Message message) {
    return CapabilityNegotiationGrammar.parseSubcommand(message) != null;
  }
}
