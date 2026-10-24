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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.MemberSnapshot;
import net.jircd.core.extension.capability.RuntimeSnapshot;

/**
 * Renders member facts for one requester: the NAMES listing token and the channel WHO status flag.
 * Core's defaults are the nickname and the highest held status. The status and the NAMES identity
 * are separate slots, each replaceable by at most one provider the requester negotiated, so a
 * provider that lists every status and one that changes the identity compose without knowing of one
 * another. The hooks run on the caller's thread outside every registry and channel monitor, against
 * one published snapshot captured per call, and a faulty hook costs only its own contribution.
 *
 * <p>Identity fields are resolved only if a provider could use them: the presented hostname may run
 * hostname-display extension code, which a plain NAMES must not pay for on every member.
 */
public final class MemberReplies {
  private final ExtensionRegistry extensionRegistry;

  public MemberReplies(ExtensionRegistry extensionRegistry) {
    this.extensionRegistry = Objects.requireNonNull(extensionRegistry);
  }

  /** One NAMES token per member, in the order given. */
  public List<String> namesTokens(ClientSession requester, List<MemberFacts> facts) {
    RuntimeSnapshot snapshot = extensionRegistry.capabilitySnapshot();
    Set<String> negotiated = requester.negotiatedCapabilities();
    boolean customStatus = !snapshot.hooksFor(HookKind.MEMBER_STATUS, negotiated).isEmpty();
    boolean customIdentity = !snapshot.hooksFor(HookKind.NAMES_IDENTITY, negotiated).isEmpty();
    List<String> tokens = new ArrayList<>(facts.size());
    if (!customStatus && !customIdentity) {
      for (MemberFacts fact : facts) {
        tokens.add(highest(fact) + fact.member().nickname());
      }
      return tokens;
    }
    HookDispatcher dispatcher = extensionRegistry.hookDispatcher();
    for (MemberFacts fact : facts) {
      MemberSnapshot member = snapshot(fact, customIdentity);
      tokens.add(
          dispatcher.memberStatus(snapshot, negotiated, member)
              + dispatcher.namesIdentity(snapshot, negotiated, member));
    }
    return tokens;
  }

  /** The status symbols to append to a channel WHO entry's {@code H}/{@code G}/{@code *} flags. */
  public String whoStatus(ClientSession requester, MemberFacts fact) {
    RuntimeSnapshot snapshot = extensionRegistry.capabilitySnapshot();
    Set<String> negotiated = requester.negotiatedCapabilities();
    if (snapshot.hooksFor(HookKind.MEMBER_STATUS, negotiated).isEmpty()) {
      return highest(fact);
    }
    return extensionRegistry
        .hookDispatcher()
        .memberStatus(snapshot, negotiated, snapshot(fact, false));
  }

  private MemberSnapshot snapshot(MemberFacts fact, boolean withIdentity) {
    ClientSession member = fact.member();
    return new MemberSnapshot(
        Objects.toString(member.nickname(), ""),
        withIdentity ? Objects.toString(member.ident(), "") : "",
        withIdentity
            ? Objects.toString(PresentedIdentity.displayHostname(member, extensionRegistry), "")
            : "",
        fact.heldStatuses());
  }

  private static String highest(MemberFacts fact) {
    return fact.heldStatuses().isEmpty() ? "" : fact.heldStatuses().getFirst();
  }
}
