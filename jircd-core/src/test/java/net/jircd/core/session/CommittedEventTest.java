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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.CommittedEvent;
import net.jircd.core.extension.capability.EventIntent;
import net.jircd.core.extension.capability.EventKind;
import net.jircd.core.session.command.AwayCommandHandler;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.core.session.command.KickCommandHandler;
import net.jircd.core.session.command.NickCommandHandler;
import net.jircd.core.session.command.PartCommandHandler;
import net.jircd.core.session.command.RegistrationCompletion;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Committed facts reach listeners once per accepted transition, after the state has changed. */
class CommittedEventTest {

  /** Records every event, and optionally throws or answers with a notice to the whole audience. */
  private static final class Spy extends AbstractCapabilityExtension {
    final List<CommittedEvent> events = Collections.synchronizedList(new ArrayList<>());
    final Map<CommittedEvent, List<ClientSession>> audiences = new ConcurrentHashMap<>();
    final boolean failing;
    final AtomicInteger calls = new AtomicInteger();

    Spy(String id, boolean failing) {
      super(id);
      this.failing = failing;
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      for (EventKind kind : EventKind.values()) {
        registrar.onEvent(
            kind,
            event -> {
              calls.incrementAndGet();
              events.add(event);
              audiences.put(event, event.audience().peers());
              if (failing) throw new IllegalStateException("listener fault");
              return List.of();
            });
      }
    }
  }

  private final NicknameRegistry nicknames = new NicknameRegistry();
  private final ChannelRegistry channels = new ChannelRegistry();

  /** A faulting listener is withdrawn on the faulting thread here, so tests see the outcome. */
  private final ExtensionRegistry extensions = new ExtensionRegistry(Runnable::run);

  private final List<SessionWriter> writers = new ArrayList<>();
  private final Map<ClientSession, ByteArrayOutputStream> outputs = new HashMap<>();
  private final JoinCommandHandler join =
      new JoinCommandHandler(channels, extensions, () -> "server", () -> 50);
  private final PartCommandHandler part =
      new PartCommandHandler(channels, extensions, () -> "server");
  private final KickCommandHandler kick =
      new KickCommandHandler(channels, extensions, () -> "server");
  private final AwayCommandHandler away = new AwayCommandHandler(() -> "server", extensions);
  private final NickCommandHandler nick =
      new NickCommandHandler(
          nicknames,
          () -> "server",
          () -> 30,
          new RegistrationCompletion(
              () -> "server", "test", Instant.now(), extensions, new UserStatistics()),
          extensions);
  private final DisconnectCleanup cleanup =
      new DisconnectCleanup(nicknames, channels, new WhowasHistory(() -> 10), extensions);
  private Spy spy;

  @BeforeEach
  void listen() {
    spy = new Spy("spy", false);
    extensions.register(spy);
    extensions.enable("spy");
  }

  @AfterEach
  void closeWriters() {
    writers.forEach(SessionWriter::close);
  }

  private ClientSession user(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("ident");
    session.setRealname(nick);
    assertThat(session.lifecycle().completeRegistration()).isTrue();
    nicknames.claim(nick, session);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    outputs.put(session, out);
    @SuppressWarnings("PMD.CloseResource") // closed by closeWriters()
    SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE);
    writers.add(writer);
    session.attachWriter(writer);
    return session;
  }

  private void run(
      net.jircd.core.session.command.CommandHandler handler, ClientSession s, String line)
      throws Exception {
    handler.handle(s, MessageParser.parse(line));
  }

  private List<CommittedEvent> events(EventKind kind) {
    synchronized (spy.events) {
      return spy.events.stream().filter(e -> e.kind() == kind).toList();
    }
  }

  // --- AWAY ---

  @Test
  void everyAcceptedAwayChangeIsAnEventIncludingARepeatedReason() throws Exception {
    ClientSession alice = user("alice");

    run(away, alice, "AWAY :lunch");
    run(away, alice, "AWAY :lunch");
    run(away, alice, "AWAY :dinner");
    run(away, alice, "AWAY");

    List<CommittedEvent> awayEvents = events(EventKind.AWAY_CHANGED);
    assertThat(awayEvents)
        .extracting(CommittedEvent::before, CommittedEvent::after)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("", "lunch"),
            org.assertj.core.groups.Tuple.tuple("lunch", "lunch"),
            org.assertj.core.groups.Tuple.tuple("lunch", "dinner"),
            org.assertj.core.groups.Tuple.tuple("dinner", ""));
    assertThat(awayEvents)
        .allSatisfy(e -> assertThat(e.subject()).isEqualTo("alice!ident@host.example"));
    assertThat(awayEvents).extracting(CommittedEvent::operationId).doesNotHaveDuplicates();
  }

  @Test
  void theAudienceIsSharedChannelPeersOnceEachWithoutTheSubject() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    ClientSession carol = user("carol");
    for (String channel : List.of("#a", "#b")) {
      run(join, alice, "JOIN " + channel);
      run(join, bob, "JOIN " + channel);
    }
    run(join, carol, "JOIN #b");
    run(join, user("loner"), "JOIN #c");

    run(away, alice, "AWAY :x");

    CommittedEvent event = events(EventKind.AWAY_CHANGED).getFirst();
    assertThat(spy.audiences.get(event)).containsExactlyInAnyOrder(bob, carol);
  }

  @Test
  void theSubjectIsThePresentedIdentityNotTheRealHostname() throws Exception {
    extensions.register(new TestCloak());
    extensions.enable("test-cloak");
    ClientSession alice = user("alice");

    run(away, alice, "AWAY :x");

    assertThat(events(EventKind.AWAY_CHANGED).getFirst().subject())
        .isEqualTo("alice!ident@cloak.example");
  }

  // --- JOIN / PART ---

  @Test
  void aJoinIsAnEventWithItsMembersButARepeatedJoinIsNot() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    run(join, alice, "JOIN #c");

    run(join, bob, "JOIN #c");
    run(join, bob, "JOIN #c");

    List<CommittedEvent> joins = events(EventKind.JOINED);
    assertThat(joins).hasSize(2);
    CommittedEvent second = joins.get(1);
    assertThat(second.subject()).isEqualTo("bob!ident@host.example");
    assertThat(second.after()).isEqualTo("#c");
    assertThat(spy.audiences.get(second)).containsExactly(alice);
  }

  @Test
  void aRejectedJoinPublishesNothing() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    run(join, alice, "JOIN #c");
    channels.lookup("#c").orElseThrow().setKey("secret");

    run(join, bob, "JOIN #c wrong");

    assertThat(events(EventKind.JOINED)).hasSize(1);
  }

  @Test
  void aPartCarriesTheAudienceCapturedBeforeTheDeparture() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    run(join, alice, "JOIN #c");
    run(join, bob, "JOIN #c");

    run(part, bob, "PART #c :bye");
    run(part, bob, "PART #c :again");

    List<CommittedEvent> parts = events(EventKind.PARTED);
    assertThat(parts).hasSize(1);
    assertThat(parts.getFirst().before()).isEqualTo("#c");
    assertThat(parts.getFirst().after()).isEqualTo("bye");
    assertThat(spy.audiences.get(parts.getFirst())).containsExactly(alice);
    assertThat(channels.lookup("#c").orElseThrow().members()).doesNotContain(bob);
  }

  // --- KICK ---

  @Test
  void aKickCarriesTheKickedIdentityTheKickerTheReasonAndThePreRemovalAudience() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    ClientSession carol = user("carol");
    run(join, alice, "JOIN #c");
    run(join, bob, "JOIN #c");
    run(join, carol, "JOIN #c");

    run(kick, alice, "KICK #c bob :out");

    List<CommittedEvent> kicks = events(EventKind.KICKED);
    assertThat(kicks).hasSize(1);
    CommittedEvent kicked = kicks.getFirst();
    assertThat(kicked.subject()).isEqualTo("bob!ident@host.example");
    assertThat(kicked.actor()).isEqualTo("alice!ident@host.example");
    assertThat(kicked.before()).isEqualTo("#c");
    assertThat(kicked.after()).isEqualTo("out");
    assertThat(spy.audiences.get(kicked)).containsExactlyInAnyOrder(alice, carol);
    assertThat(channels.lookup("#c").orElseThrow().members()).doesNotContain(bob);
  }

  @Test
  void aKickWithoutACommentReportsTheKickersNicknameAsTheReason() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    run(join, alice, "JOIN #c");
    run(join, bob, "JOIN #c");

    run(kick, alice, "KICK #c bob");

    assertThat(events(EventKind.KICKED)).extracting(CommittedEvent::after).containsExactly("alice");
  }

  @Test
  void aRejectedKickPublishesNothing() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    ClientSession carol = user("carol");
    run(join, alice, "JOIN #c");
    run(join, bob, "JOIN #c");

    run(kick, bob, "KICK #c alice"); // not an operator
    run(kick, carol, "KICK #c alice"); // not a member
    run(kick, alice, "KICK #c carol"); // target not on the channel
    run(kick, alice, "KICK #nowhere bob"); // no such channel
    run(kick, alice, "KICK #c"); // not enough parameters

    assertThat(events(EventKind.KICKED)).isEmpty();
    assertThat(channels.lookup("#c").orElseThrow().members()).contains(alice, bob);
  }

  // --- ordering ---

  @Test
  void eventsASessionCausesItselfArePublishedInTheOrderItIssuedThem() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    run(join, alice, "JOIN #c");

    run(join, bob, "JOIN #c");
    run(part, bob, "PART #c");
    run(join, bob, "JOIN #c");
    run(nick, bob, "NICK robert");
    cleanup.cleanup(bob, "gone");

    // Only the events bob's own commands caused; one that another session causes (KICKED) has no
    // order relative to these.
    List<CommittedEvent> bobs;
    synchronized (spy.events) {
      bobs =
          spy.events.stream()
              .filter(e -> e.subject().startsWith("bob!") || e.subject().startsWith("robert!"))
              .toList();
    }
    assertThat(bobs)
        .extracting(CommittedEvent::kind)
        .containsExactly(
            EventKind.JOINED,
            EventKind.PARTED,
            EventKind.JOINED,
            EventKind.NICK_CHANGED,
            EventKind.QUIT);
    assertThat(bobs).extracting(CommittedEvent::operationId).isSorted();
  }

  // --- NICK ---

  @Test
  void aNickChangeCarriesTheOldIdentityAndThePeers() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    run(join, alice, "JOIN #c");
    run(join, bob, "JOIN #c");

    run(nick, alice, "NICK alicia");
    run(nick, alice, "NICK alicia"); // not a change
    run(nick, alice, "NICK bob"); // rejected: in use

    List<CommittedEvent> nicks = events(EventKind.NICK_CHANGED);
    assertThat(nicks).hasSize(1);
    CommittedEvent change = nicks.getFirst();
    assertThat(change.subject()).isEqualTo("alice!ident@host.example");
    assertThat(change.before()).isEqualTo("alice");
    assertThat(change.after()).isEqualTo("alicia");
    assertThat(spy.audiences.get(change)).containsExactly(bob);
    assertThat(alice.nickname()).isEqualTo("alicia");
  }

  // --- QUIT ---

  @Test
  void aQuitIsPublishedOnceWithTheNeighborsAndCleanupIsIdempotent() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    run(join, alice, "JOIN #c");
    run(join, alice, "JOIN #d");
    run(join, bob, "JOIN #c");
    run(join, bob, "JOIN #d");

    cleanup.cleanup(bob, "Quit: gone");
    cleanup.cleanup(bob, "second trigger");

    List<CommittedEvent> quits = events(EventKind.QUIT);
    assertThat(quits).hasSize(1);
    assertThat(quits.getFirst().subject()).isEqualTo("bob!ident@host.example");
    assertThat(quits.getFirst().after()).isEqualTo("Quit: gone");
    assertThat(spy.audiences.get(quits.getFirst())).containsExactly(alice);
  }

  @Test
  void aSessionThatNeverRegisteredAnIdentityPublishesNoQuit() {
    ClientSession anonymous =
        new ClientSession("anon", "host.example", RateLimitBucket.withDefaults());

    cleanup.cleanup(anonymous, "timeout");

    assertThat(events(EventKind.QUIT)).isEmpty();
  }

  // --- listener faults ---

  @Test
  void committedStateStandsAndLaterEventsStillFlowAfterAListenerFaultWithdrawsIt()
      throws Exception {
    Spy faulty = new Spy("faulty", true);
    extensions.register(faulty);
    extensions.enable("faulty");
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");

    run(join, alice, "JOIN #c");
    run(join, bob, "JOIN #c");
    run(away, alice, "AWAY :x");
    run(nick, alice, "NICK alicia");
    cleanup.cleanup(bob, "bye");

    assertThat(channels.lookup("#c").orElseThrow().members()).containsExactly(alice);
    assertThat(alice.awayReason()).isEqualTo("x");
    assertThat(alice.nickname()).isEqualTo("alicia");
    assertThat(nicknames.lookup("bob")).isEmpty();
    // #102: the first fault withdraws the listener as FAILED; the healthy one gets every event.
    assertThat(faulty.calls).hasValue(1);
    assertThat(extensions.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(spy.calls.get()).isGreaterThanOrEqualTo(5);
  }

  @Test
  void anUnlistenedKindResolvesNeitherTheSubjectNorTheAudience() {
    extensions.disable("spy");
    AbstractCapabilityExtension awayOnly =
        new AbstractCapabilityExtension("only-away") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onEvent(EventKind.AWAY_CHANGED, event -> List.<EventIntent>of());
          }
        };
    extensions.register(awayOnly);
    extensions.enable("only-away");
    AtomicInteger resolved = new AtomicInteger();

    extensions
        .committedEvents()
        .publish(
            EventKind.JOINED,
            () -> {
              resolved.incrementAndGet();
              return "alice!ident@host.example";
            },
            "",
            "#c",
            () -> {
              resolved.incrementAndGet();
              return List.of();
            });

    assertThat(resolved).hasValue(0);
  }

  @Test
  void aFailingPresentedIdentityNeverCostsTheCommandItsRemainingReplies() throws Exception {
    extensions.register(new ThrowingCloak());
    extensions.enable("throwing-cloak");
    ClientSession alice = user("alice");

    // The confirmation precedes the event and the state is set before it: neither is lost.
    run(away, alice, "AWAY :x");

    assertThat(alice.awayReason()).isEqualTo("x");
    writers.forEach(SessionWriter::close);
    assertThat(outputs.get(alice).toString(StandardCharsets.UTF_8)).contains(" 306 ");
    assertThat(events(EventKind.AWAY_CHANGED)).isEmpty();
  }

  // --- listener-driven delivery ---

  @Test
  void aListenerIntentIsDeliveredOnlyToItsPermittedPeers() throws Exception {
    extensions.disable("spy");
    AbstractCapabilityExtension notifier =
        new AbstractCapabilityExtension("notifier") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onEvent(
                EventKind.AWAY_CHANGED,
                event ->
                    List.of(
                        new EventIntent(
                            new Message(
                                Map.of(), event.subject(), Command.AWAY, "AWAY", List.of("seen")),
                            event.audience().peers())));
          }
        };
    extensions.register(notifier);
    extensions.enable("notifier");
    ClientSession alice = user("alice");
    ClientSession listening = user("listening");
    ClientSession deaf = user("deaf");
    listening.replaceNegotiatedCapabilities(List.of("notifier"));
    for (ClientSession member : List.of(alice, listening, deaf)) run(join, member, "JOIN #c");

    run(away, alice, "AWAY :x");
    writers.forEach(SessionWriter::close);

    assertThat(outputs.get(listening).toString(StandardCharsets.UTF_8))
        .contains(":alice!ident@host.example AWAY seen\r\n");
    assertThat(outputs.get(deaf).toString(StandardCharsets.UTF_8)).doesNotContain(" AWAY ");
    assertThat(outputs.get(alice).toString(StandardCharsets.UTF_8)).doesNotContain(" AWAY seen");
  }

  private static final class ThrowingCloak implements ServerExtension, HostnameDisplayExtension {
    @Override
    public String id() {
      return "throwing-cloak";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public String display(String realHostname) {
      throw new IllegalStateException("cloak failure");
    }

    @Override
    public void start(net.jircd.core.extension.ServerContext context) {
      // No resources.
    }

    @Override
    public void stop() {
      // No resources.
    }
  }

  private static final class TestCloak implements ServerExtension, HostnameDisplayExtension {
    @Override
    public String id() {
      return "test-cloak";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public String display(String realHostname) {
      return "cloak.example";
    }

    @Override
    public void start(net.jircd.core.extension.ServerContext context) {
      // No resources.
    }

    @Override
    public void stop() {
      // No resources.
    }
  }
}
