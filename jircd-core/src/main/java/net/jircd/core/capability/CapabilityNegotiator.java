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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.CapabilityNegotiationGrammar;

/**
 * The {@code CAP LS}/{@code REQ}/{@code ACK}/{@code NAK}/{@code END} state machine
 * (FR-006/FR-007/FR-008). Gates registration completion while a negotiation is in progress. Always
 * available — never one of the toggleable extensions itself (FR-035). The offered list is core
 * {@code cap-notify} plus the published providers', read live from {@link ExtensionRegistry}, never
 * cached (FR-007).
 *
 * <p>Every read-validate-commit of a session's negotiation runs under the capability runtime's
 * administrative mutex ({@link #runUnderControl}), the same one a disable holds while it prunes, so
 * a request racing a disable either lands before the prune and is pruned, or after it and is
 * refused; it can never bring a withdrawn name back.
 */
public final class CapabilityNegotiator {

  private final ExtensionRegistry extensionRegistry;

  public CapabilityNegotiator(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = extensionRegistry;
  }

  public void beginNegotiation(ClientSession session) {
    session.setNegotiating(true);
  }

  /** Core cap-notify and every published provider's capability, sorted. */
  public List<String> currentlyOfferedCapabilityNames() {
    List<String> offered = new ArrayList<>(extensionRegistry.capabilitySnapshot().offeredNames());
    offered.add(CapabilityName.CAP_NOTIFY);
    offered.sort(Comparator.naturalOrder());
    return offered;
  }

  /** Runs {@code action} under the administrative mutex CAP state shares with publication. */
  public void runUnderControl(Runnable action) {
    extensionRegistry.capabilityControl().runUnderControl(action);
  }

  /**
   * The explicit set {@code requested} would leave {@code session} with, applying its tokens in
   * order, or empty if the whole request must be refused. Changes nothing. A positive token must be
   * offered now. A {@code -name} token is accepted if the name is held at that point (offered or
   * not), or if it is a known capability that is simply not held (for example one a disable already
   * pruned); an unknown, unheld name refuses the request. Notification support is not removable by
   * a client that indicated {@code CAP LS 302}. An empty request is refused.
   */
  public Optional<Set<String>> candidate(ClientSession session, List<String> requested) {
    if (requested.isEmpty()) return Optional.empty();
    Set<String> offered = new HashSet<>(currentlyOfferedCapabilityNames());
    Set<String> candidate = new HashSet<>(session.negotiatedCapabilities());
    for (String token : requested) {
      if (!isRemoval(token)) {
        if (!offered.contains(token)) return Optional.empty();
        candidate.add(token);
        continue;
      }
      String name = bareName(token);
      if (name.equals(CapabilityName.CAP_NOTIFY)
          && session.highestCapVersion() >= CapabilityNegotiationGrammar.NOTIFY_VERSION) {
        return Optional.empty();
      }
      if (!candidate.remove(name) && !isKnown(name)) return Optional.empty();
    }
    return Optional.of(candidate);
  }

  /**
   * Whether {@code name} is core cap-notify or offered by any discovered provider, enabled or not.
   */
  private boolean isKnown(String name) {
    return name.equals(CapabilityName.CAP_NOTIFY)
        || extensionRegistry.knownCapabilityNames().contains(name);
  }

  /**
   * Applies a {@code CAP REQ} atomically under the administrative mutex: the session changes only
   * if the whole request is acceptable, and then through one swap of the whole negotiated set.
   * Returns whether the request was accepted; the {@code ACK}/{@code NAK} echo is the caller's,
   * built from the client's original list so it keeps order and duplicates
   * (005-fix-batch-conformance FR-009).
   */
  public boolean request(ClientSession session, List<String> requested) {
    return extensionRegistry
        .capabilityControl()
        .underControl(
            () -> {
              Optional<Set<String>> accepted = candidate(session, requested);
              accepted.ifPresent(session::replaceNegotiatedCapabilities);
              return accepted.isPresent();
            });
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
