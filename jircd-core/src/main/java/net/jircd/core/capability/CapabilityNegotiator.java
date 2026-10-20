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
   * Applies a {@code CAP REQ} atomically: every name is validated against the live offered list
   * first (a {@code -name} removal is validated by its bare name), and the session changes only if
   * all of them pass. Returns whether the request was accepted; the {@code ACK}/{@code NAK} echo is
   * the caller's, built from the client's original list so it keeps order and duplicates
   * (005-fix-batch-conformance FR-009).
   */
  public boolean request(ClientSession session, List<String> requested) {
    Set<String> offered = Set.copyOf(currentlyOfferedCapabilityNames());
    for (String name : requested) {
      if (!offered.contains(bareName(name))) {
        return false;
      }
    }
    for (String name : requested) {
      if (isRemoval(name)) {
        session.negotiatedCapabilities().remove(bareName(name));
      } else {
        session.negotiatedCapabilities().add(name);
      }
    }
    return true;
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
