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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.command.CapCommandHandler;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CAP negotiation against runtime changes (#97): advertised core cap-notify, sticky 302 support,
 * negative REQ for held, pruned and unknown names, all-or-nothing requests with exact echoes, and
 * DEL/NEW to exactly the eligible sessions.
 */
class CapabilityLifecycleNegotiationTest {

  private static final String SERVER = "irc.example";

  private static final class Offer extends AbstractCapabilityExtension {
    Offer(String name) {
      super(name);
    }
  }

  private ExtensionRegistry registry;
  private CapCommandHandler cap;
  private final Set<ClientSession> live = ConcurrentHashMap.newKeySet();

  @BeforeEach
  void setUp() {
    registry = new ExtensionRegistry();
    registry.register(new Offer("alpha"));
    registry.register(new Offer("beta"));
    registry.attachContext(
        new ServerContext(
            null, null, registry, () -> SERVER, null, null, null, registry.capabilityServices()));
    registry.attachLiveSessions(live);
    cap = new CapCommandHandler(new CapabilityNegotiator(registry), () -> SERVER, null);
  }

  private ClientSession connect(String id) {
    ClientSession session = new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
    live.add(session);
    return session;
  }

  /** The lines written to each session while {@code action} runs. */
  private static List<List<String>> during(Runnable action, ClientSession... sessions) {
    List<ByteArrayOutputStream> outputs = new ArrayList<>();
    List<SessionWriter> writers = new ArrayList<>();
    for (ClientSession session : sessions) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      // Closed in the finally below, after the action has run.
      @SuppressWarnings("PMD.CloseResource")
      SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE);
      session.attachWriter(writer);
      outputs.add(out);
      writers.add(writer);
    }
    try {
      action.run();
    } finally {
      writers.forEach(SessionWriter::close);
    }
    List<List<String>> result = new ArrayList<>();
    for (ByteArrayOutputStream out : outputs) {
      String text = out.toString(StandardCharsets.UTF_8);
      result.add(text.isEmpty() ? List.of() : List.of(text.split("\r\n")));
    }
    return result;
  }

  private List<String> send(ClientSession session, String line) {
    return during(
            () -> {
              try {
                cap.handle(session, MessageParser.parse(line));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            session)
        .getFirst();
  }

  @Test
  void lsAdvertisesCoreCapNotifyEvenWithNoProviderEnabled() {
    ClientSession client = connect("c1");
    assertThat(send(client, "CAP LS")).containsExactly(":irc.example CAP * LS cap-notify");
    assertThat(send(client, "CAP LS 302")).containsExactly(":irc.example CAP * LS cap-notify");
  }

  @Test
  void lsListsCapNotifyAndTheEnabledProvidersInADeterministicOrder() {
    registry.enable("beta");
    registry.enable("alpha");
    assertThat(send(connect("c1"), "CAP LS"))
        .containsExactly(":irc.example CAP * LS :alpha beta cap-notify");
  }

  @Test
  void anExplicitCapNotifyRequestIsAnOrdinaryOfferedAdditionAndAppearsInList() {
    ClientSession client = connect("c1");
    assertThat(send(client, "CAP REQ :cap-notify"))
        .containsExactly(":irc.example CAP * ACK cap-notify");
    assertThat(client.notificationEligible()).isTrue();
    assertThat(send(client, "CAP LIST")).containsExactly(":irc.example CAP * LIST cap-notify");
  }

  @Test
  void implicit302SupportIsOmittedFromListAndCannotBeRemoved() {
    ClientSession client = connect("c1");
    send(client, "CAP LS 302");
    assertThat(client.notificationEligible()).isTrue();
    assertThat(send(client, "CAP LIST")).containsExactly(":irc.example CAP * LIST :");

    assertThat(send(client, "CAP REQ :-cap-notify"))
        .containsExactly(":irc.example CAP * NAK -cap-notify");
    assertThat(client.notificationEligible()).isTrue();
  }

  @Test
  void a302ClientThatAlsoRequestedCapNotifyExplicitlyStillCannotRemoveIt() {
    registry.enable("alpha");
    ClientSession client = connect("c1");
    send(client, "CAP LS 302");
    send(client, "CAP REQ :cap-notify alpha");

    assertThat(send(client, "CAP REQ :-alpha -cap-notify"))
        .containsExactly(":irc.example CAP * NAK :-alpha -cap-notify");
    assertThat(client.negotiatedCapabilities()).containsExactlyInAnyOrder("cap-notify", "alpha");
  }

  @Test
  void aLegacyClientMayRemoveExplicitCapNotify() {
    ClientSession client = connect("c1");
    send(client, "CAP REQ :cap-notify");
    assertThat(send(client, "CAP REQ :-cap-notify"))
        .containsExactly(":irc.example CAP * ACK -cap-notify");
    assertThat(client.notificationEligible()).isFalse();
    assertThat(send(client, "CAP LIST")).containsExactly(":irc.example CAP * LIST :");
  }

  @Test
  void the302VersionIsStickyAgainstLowerOmittedAndMalformedVersions() {
    ClientSession client = connect("c1");
    send(client, "CAP LS 302");
    for (String later : List.of("CAP LS", "CAP LS 301", "CAP LS 0", "CAP LS abc", "CAP LS -1")) {
      assertThat(send(client, later)).containsExactly(":irc.example CAP * LS cap-notify");
      assertThat(client.notificationEligible()).as(later).isTrue();
    }
    assertThat(client.highestCapVersion()).isEqualTo(302);
  }

  @Test
  void aMalformedOrHugeVersionKeepsOrdinaryLsBehavior() {
    ClientSession malformed = connect("c1");
    assertThat(send(malformed, "CAP LS 3o2")).containsExactly(":irc.example CAP * LS cap-notify");
    assertThat(malformed.notificationEligible()).isFalse();

    ClientSession huge = connect("c2");
    send(huge, "CAP LS 99999999999999999999999");
    assertThat(huge.notificationEligible()).isTrue();
  }

  @Test
  void aHeldButUnofferedNameCanBeRemoved() {
    ClientSession client = connect("c1");
    // Controlled setup: a name the session holds although nothing offers it.
    client.replaceNegotiatedCapabilities(List.of("ghost"));

    assertThat(send(client, "CAP REQ :-ghost")).containsExactly(":irc.example CAP * ACK -ghost");
    assertThat(client.negotiatedCapabilities()).isEmpty();
  }

  @Test
  void removingADiscoveredNameAlreadyPrunedByDisableIsAnAcknowledgedNoOp() {
    registry.enable("alpha");
    ClientSession client = connect("c1");
    send(client, "CAP REQ :alpha");
    registry.disable("alpha");
    assertThat(client.negotiatedCapabilities()).isEmpty();

    assertThat(send(client, "CAP REQ :-alpha")).containsExactly(":irc.example CAP * ACK -alpha");
    assertThat(client.negotiatedCapabilities()).isEmpty();
  }

  @Test
  void removingAnUnknownUnheldNameNaksTheWholeRequest() {
    registry.enable("alpha");
    ClientSession client = connect("c1");
    assertThat(send(client, "CAP REQ :alpha -nope"))
        .containsExactly(":irc.example CAP * NAK :alpha -nope");
    assertThat(client.negotiatedCapabilities()).isEmpty();
    assertThat(send(client, "CAP REQ :-")).containsExactly(":irc.example CAP * NAK -");
  }

  @Test
  void anUnavailableAdditionNaksAMixedRequestAtomically() {
    registry.enable("beta");
    ClientSession client = connect("c1");
    send(client, "CAP REQ :beta");
    // alpha is discovered but disabled: not offered, so it cannot be added.
    assertThat(send(client, "CAP REQ :-beta alpha"))
        .containsExactly(":irc.example CAP * NAK :-beta alpha");
    assertThat(client.negotiatedCapabilities()).containsExactly("beta");
  }

  @Test
  void repeatedAndConflictingTokensApplyInOrderAndAreEchoedExactly() {
    registry.enable("alpha");
    ClientSession client = connect("c1");
    assertThat(send(client, "CAP REQ :alpha -alpha alpha alpha"))
        .containsExactly(":irc.example CAP * ACK :alpha -alpha alpha alpha");
    assertThat(client.negotiatedCapabilities()).containsExactly("alpha");
    assertThat(send(client, "CAP REQ :alpha -alpha"))
        .containsExactly(":irc.example CAP * ACK :alpha -alpha");
    assertThat(client.negotiatedCapabilities()).isEmpty();
  }

  @Test
  void anIdempotentRequestOfAHeldOfferedNameSucceeds() {
    registry.enable("alpha");
    ClientSession client = connect("c1");
    send(client, "CAP REQ :alpha");
    assertThat(send(client, "CAP REQ :alpha")).containsExactly(":irc.example CAP * ACK alpha");
    assertThat(client.negotiatedCapabilities()).containsExactly("alpha");
  }

  @Test
  void anEmptyRequestIsNakedAndChangesNothing() {
    registry.enable("alpha");
    ClientSession client = connect("c1");
    send(client, "CAP REQ :alpha");
    assertThat(send(client, "CAP REQ :")).containsExactly(":irc.example CAP * NAK :");
    assertThat(client.negotiatedCapabilities()).containsExactly("alpha");
  }

  @Test
  void anOversizeEchoGets417AndChangesNothing() {
    registry.enable("alpha");
    ClientSession client = connect("c1");
    String request = String.join(" ", Collections.nCopies(90, "alpha"));
    List<String> lines = send(client, "CAP REQ :" + request);
    assertThat(lines).singleElement().asString().contains(" 417 ");
    assertThat(client.negotiatedCapabilities()).isEmpty();
  }

  @Test
  void listShowsTheCurrentExplicitSetAfterPruning() {
    registry.enable("alpha");
    registry.enable("beta");
    ClientSession client = connect("c1");
    send(client, "CAP REQ :alpha beta");
    registry.disable("alpha");
    assertThat(send(client, "CAP LIST")).containsExactly(":irc.example CAP * LIST beta");
  }

  @Test
  void disableSendsDelToExplicitAndImplicitNotifyClientsEvenIfTheyNeverHeldIt() {
    registry.enable("alpha");
    ClientSession explicit = connect("explicit");
    send(explicit, "CAP REQ :cap-notify");
    ClientSession implicit = connect("implicit");
    send(implicit, "CAP LS 302");
    implicit.setNickname("ivy");
    ClientSession holder = connect("holder");
    send(holder, "CAP LS 302");
    send(holder, "CAP REQ :alpha");
    ClientSession legacy = connect("legacy");
    send(legacy, "CAP REQ :alpha");

    List<List<String>> wire =
        during(() -> registry.disable("alpha"), explicit, implicit, holder, legacy);

    assertThat(wire.get(0)).containsExactly(":irc.example CAP * DEL alpha");
    assertThat(wire.get(1)).containsExactly(":irc.example CAP ivy DEL alpha");
    assertThat(wire.get(2)).containsExactly(":irc.example CAP * DEL alpha");
    assertThat(wire.get(3)).isEmpty();
    assertThat(holder.negotiatedCapabilities()).isEmpty();
    assertThat(legacy.negotiatedCapabilities()).isEmpty();
  }

  @Test
  void enableSendsNewWithoutRestoringPriorNegotiations() {
    registry.enable("alpha");
    ClientSession holder = connect("holder");
    send(holder, "CAP LS 302");
    send(holder, "CAP REQ :alpha");
    registry.disable("alpha");

    List<List<String>> wire = during(() -> registry.enable("alpha"), holder);

    assertThat(wire.getFirst()).containsExactly(":irc.example CAP * NEW alpha");
    assertThat(holder.negotiatedCapabilities()).isEmpty();
    assertThat(send(holder, "CAP REQ :alpha")).containsExactly(":irc.example CAP * ACK alpha");
  }

  @Test
  void aRepeatedToggleOrASessionOnlyChangeSendsNoNotification() {
    registry.enable("alpha");
    ClientSession observer = connect("observer");
    send(observer, "CAP LS 302");
    ClientSession other = connect("other");

    List<List<String>> wire =
        during(
            () -> {
              registry.enable("alpha");
              registry.disable("beta");
              try {
                cap.handle(other, MessageParser.parse("CAP REQ :alpha"));
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            observer);

    assertThat(wire.getFirst()).isEmpty();
  }
}
