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
package net.jircd.core.extension.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.CommandRegistrar;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.session.CapabilityTagRenderer;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.OutboundMessage;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * #102: a runtime hook fault (exception, null, or an illegal, unauthorized or out-of-bound result)
 * drops that contribution for its documented default and withdraws the provider, with every hard
 * dependent, as FAILED through #97's publication: one snapshot swap, the all-session prune and one
 * {@code DEL}. Committed core work and unrelated providers carry on; diagnostics name the provider,
 * hook and category only and are bounded per incident; nothing recovers automatically; stop runs
 * without waiting for callbacks still in flight.
 */
class CapabilityFailureIsolationTest {

  private static final String SERVER = "irc.example";
  private static final long WAIT_SECONDS = 5;

  /** Text no diagnostic may carry: it stands for exception payloads and client content. */
  private static final String SECRET = "s3cr3t-payload";

  private static final RequestContext CONTEXT = RequestContext.of(1, Map.of());

  enum Mode {
    THROW,
    NULL,
    INVALID
  }

  /** One hook of {@code kind}; valid while {@link #mode} is null, otherwise misbehaving. */
  private static class Faulty extends AbstractCapabilityExtension {
    final HookKind kind;
    final AtomicInteger stops = new AtomicInteger();
    volatile Mode mode;
    volatile RuntimeException stopFailure;

    Faulty(String id, HookKind kind) {
      super(id);
      this.kind = kind;
    }

    @Override
    public void stop() {
      stops.incrementAndGet();
      RuntimeException failure = stopFailure;
      if (failure != null) throw failure;
    }

    private <T> T answer(Supplier<T> valid, Supplier<T> invalid) {
      Mode current = mode;
      if (current == null) return valid.get();
      return switch (current) {
        case THROW -> throw new IllegalStateException(SECRET);
        case NULL -> null;
        case INVALID -> invalid.get();
      };
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      switch (kind) {
        case EVENT ->
            registrar.onEvent(
                EventKind.AWAY_CHANGED,
                event ->
                    answer(
                        () -> List.of(new EventIntent(notice(), event.audience().peers())),
                        // Outside the event's audience: an unauthorized recipient.
                        () -> List.of(new EventIntent(notice(), List.of(outsider())))));
        case INBOUND_TAGS ->
            registrar.onInboundTags(
                (tags, context) ->
                    answer(
                        () -> new InboundTagResult(Map.of("+x", "1"), Map.of()),
                        // A forged server tag.
                        () -> new InboundTagResult(Map.of("time", SECRET), Map.of())));
        case RECIPIENTS ->
            registrar.onRecipients(
                (intent, context) ->
                    answer(
                        () -> Set.of(DeliveryRole.SENDER_COPY),
                        () -> Set.of(DeliveryRole.ORDINARY_TARGET)));
        case OUTBOUND_TAGS ->
            registrar.onOutboundTags(
                Set.of("f"),
                16,
                message ->
                    answer(
                        () -> Map.of("f", "v"),
                        // Beyond its own declared 16-byte bound.
                        () -> Map.of("f", SECRET.repeat(4))));
        case MEMBER_STATUS ->
            registrar.onMemberStatus(
                limits -> 2,
                // "~" is not a held status.
                member -> answer(() -> "@+", () -> "~"));
        case NAMES_IDENTITY ->
            registrar.onNamesIdentity(
                limits -> 16, member -> answer(() -> member.nickname() + "!u@h", () -> "a b"));
        case OUTPUT_GROUP ->
            registrar.onOutputGroup(
                Set.of(),
                (group, context) ->
                    answer(
                        () -> framed(group),
                        // Members out of order.
                        () -> new FramingPlan(group.members().reversed())));
        case COMMAND ->
            registrar.command(
                Command.TAGMSG,
                (session, message) -> {
                  if (mode != null) throw new IllegalStateException(SECRET);
                });
        default -> throw new IllegalArgumentException(kind.toString());
      }
    }
  }

  /** An unrelated, well-behaved tag provider. */
  private static final class Peer extends AbstractCapabilityExtension {
    Peer() {
      super("peer");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onOutboundTags(Set.of("p"), 16, message -> Map.of("p", "ok"));
    }
  }

  /** Requires {@code faulty}; stops after recording its order. */
  private static final class Child extends AbstractCapabilityExtension {
    private final List<String> stops;

    Child(List<String> stops) {
      super("child");
      this.stops = stops;
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.requiresProviders("faulty");
    }

    @Override
    public void stop() {
      stops.add(id());
    }
  }

  private static final ClientSession OUTSIDER =
      new ClientSession("outsider", "host.example", RateLimitBucket.withDefaults());

  private static ClientSession outsider() {
    return OUTSIDER;
  }

  private static Message notice() {
    return new Message(Map.of(), SERVER, Command.NOTICE, "NOTICE", List.of("peer", "note"));
  }

  private static Message privmsg(String body) {
    return new Message(
        Map.of(), "sender!ident@host", Command.PRIVMSG, "PRIVMSG", List.of("#chan", body));
  }

  private static FramingPlan framed(OutputGroup group) {
    List<Message> frames = new ArrayList<>();
    frames.add(new Message(Map.of(), null, null, "BATCH", List.of("+g", group.type())));
    frames.addAll(group.members());
    frames.add(new Message(Map.of(), null, null, "BATCH", List.of("-g")));
    return new FramingPlan(frames);
  }

  /** Core's command table, empty: provider commands resolve from the snapshot. */
  private static final CommandRegistrar NO_CORE_COMMANDS =
      new CommandRegistrar() {
        @Override
        public void register(Command command, CommandHandler handler) {
          throw new UnsupportedOperationException();
        }

        @Override
        public boolean isRegistered(Command command) {
          return false;
        }

        @Override
        public void unregister(Command command, CommandHandler installed) {
          throw new UnsupportedOperationException();
        }
      };

  private ExtensionRegistry registry;
  private final Set<ClientSession> live = ConcurrentHashMap.newKeySet();
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final List<Logger> loggers = new ArrayList<>();

  private ExtensionRegistry registry(Executor withdrawals) {
    ExtensionRegistry created = new ExtensionRegistry(withdrawals);
    created.attachContext(
        new ServerContext(
            null,
            null,
            created,
            () -> SERVER,
            null,
            null,
            NO_CORE_COMMANDS,
            created.capabilityServices()));
    created.attachLiveSessions(live);
    return created;
  }

  @BeforeEach
  void setUp() {
    // Withdrawal runs on the faulting thread here, so each assertion sees its outcome.
    registry = registry(Runnable::run);
    logs.start();
    Logger logger = (Logger) LoggerFactory.getLogger("net.jircd.core");
    logger.addAppender(logs);
    loggers.add(logger);
  }

  @AfterEach
  void tearDown() {
    loggers.forEach(logger -> logger.detachAppender(logs));
  }

  private void enable(Extension... extensions) {
    for (Extension extension : extensions) {
      registry.register(extension);
      registry.enable(extension.id());
    }
  }

  private ClientSession connect(String id, String... negotiated) {
    ClientSession session = new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(List.of(negotiated));
    live.add(session);
    return session;
  }

  private static List<String> lines(ByteArrayOutputStream out) {
    String text = out.toString(StandardCharsets.UTF_8);
    return text.isEmpty() ? List.of() : List.of(text.split("\r\n"));
  }

  private List<String> warnings() {
    return logs.list.stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private void assertSanitized() {
    assertThat(logs.list)
        .isNotEmpty()
        .allSatisfy(
            event -> {
              assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  static Stream<Arguments> faults() {
    List<Arguments> cases = new ArrayList<>();
    for (HookKind kind : HookKind.values()) {
      if (kind == HookKind.REPLY_FIELD) continue; // no reply schema declares an extension slot
      for (Mode mode : Mode.values()) {
        if (kind == HookKind.COMMAND && mode != Mode.THROW) continue; // a handler returns nothing
        cases.add(Arguments.of(kind, mode));
      }
    }
    return cases.stream();
  }

  /** Calls the faulty provider's hook once through the dispatcher; returns the result it got. */
  private Object invoke(HookKind kind, ClientSession holder, ClientSession audienceMember) {
    RuntimeSnapshot snapshot = registry.capabilitySnapshot();
    HookDispatcher dispatcher = registry.hookDispatcher();
    Set<String> negotiated = holder.negotiatedCapabilities();
    MemberSnapshot member = new MemberSnapshot("nick", "u", "h", List.of("@", "+"));
    return switch (kind) {
      case EVENT ->
          dispatcher.event(
              snapshot,
              new CommittedEvent(
                  EventKind.AWAY_CHANGED,
                  1,
                  "nick!u@h",
                  "",
                  "away",
                  new Audience(() -> List.of(audienceMember))));
      case INBOUND_TAGS ->
          dispatcher.inboundTags(
              snapshot, negotiated, new InboundTags("PRIVMSG", Map.of("+x", "1")), CONTEXT);
      case RECIPIENTS ->
          dispatcher.recipientRoles(
              snapshot,
              negotiated,
              new DeliveryIntent(UUID.randomUUID(), EnumSet.of(DeliveryRole.ORDINARY_TARGET)),
              CONTEXT);
      case OUTBOUND_TAGS ->
          dispatcher.outboundTags(
              snapshot.hooks(HookKind.OUTBOUND_TAGS).stream()
                  .filter(r -> r.providerId().equals("faulty"))
                  .findFirst()
                  .orElseThrow(),
              OutboundMessage.now("sender!ident@host", "PRIVMSG", "#chan", "hi"));
      case MEMBER_STATUS -> dispatcher.memberStatus(snapshot, negotiated, member);
      case NAMES_IDENTITY -> dispatcher.namesIdentity(snapshot, negotiated, member);
      case OUTPUT_GROUP ->
          dispatcher
              .prepareOutputGroup(
                  snapshot,
                  negotiated,
                  new OutputGroup(
                      "g", "type", List.of(), List.of(privmsg("a"), privmsg("b")), 4096),
                  CONTEXT)
              .framed()
              .frames()
              .size();
      case COMMAND -> {
        CommandHandler handler = registry.capabilityCommand(Command.TAGMSG).orElseThrow();
        assertThatCode(() -> handler.handle(holder, Message.of(Command.TAGMSG, "#c")))
            .doesNotThrowAnyException();
        yield "handled";
      }
      default -> throw new IllegalArgumentException(kind.toString());
    };
  }

  /** What each kind returns when the faulty contribution is dropped. */
  private static Object documentedDefault(HookKind kind) {
    return switch (kind) {
      case EVENT -> List.of();
      case INBOUND_TAGS -> InboundTagResult.NONE;
      case RECIPIENTS -> Set.of();
      case OUTBOUND_TAGS -> Map.of();
      case MEMBER_STATUS -> "@";
      case NAMES_IDENTITY -> "nick";
      case OUTPUT_GROUP -> 2; // the plain members
      case COMMAND -> "handled";
      default -> throw new IllegalArgumentException(kind.toString());
    };
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("faults")
  void aFaultingHookUsesItsDefaultAndWithdrawsItsProviderAsFailedThroughOnePublication(
      HookKind kind, Mode mode) {
    Faulty faulty = new Faulty("faulty", kind);
    enable(faulty, new Peer());
    ClientSession holder = connect("holder", "faulty", "peer");
    holder.recordCapVersion(302);
    ClientSession neighbour = connect("neighbour", "faulty");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(holder, out, TagRenderer.NONE)) {
      holder.attachWriter(writer);
      Object valid = invoke(kind, holder, neighbour);
      if (kind != HookKind.COMMAND) assertThat(valid).isNotEqualTo(documentedDefault(kind));

      faulty.mode = mode;
      assertThat(invoke(kind, holder, neighbour)).isEqualTo(documentedDefault(kind));
    }

    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("peer")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("peer");
    assertThat(holder.negotiatedCapabilities()).containsExactly("peer");
    assertThat(neighbour.negotiatedCapabilities()).isEmpty();
    assertThat(lines(out)).containsExactly(":irc.example CAP * DEL faulty");
    assertThat(faulty.stops).hasValue(1);
    String category =
        switch (mode) {
          case THROW -> "EXCEPTION";
          case NULL -> "NULL_RESULT";
          case INVALID -> "INVALID_RESULT";
        };
    assertThat(warnings())
        .singleElement()
        .asString()
        .contains("'faulty'", kind.toString(), category, "FAILED");
    assertSanitized();

    // The unrelated provider still contributes to new work.
    OutboundMessage next = OutboundMessage.now("sender!ident@host", "PRIVMSG", "#chan", "hi");
    HookRegistration peerTags = registry.capabilitySnapshot().hooks(HookKind.OUTBOUND_TAGS).get(0);
    assertThat(registry.hookDispatcher().outboundTags(peerTags, next)).containsEntry("p", "ok");
  }

  @Test
  void aFaultWithdrawsEveryHardDependentAsFailedInTheSamePublicationDependentsStoppedFirst() {
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    Faulty faulty =
        new Faulty("faulty", HookKind.OUTBOUND_TAGS) {
          @Override
          public void stop() {
            order.add(id());
          }
        };
    enable(faulty, new Child(order), new Peer());
    ClientSession holder = connect("holder", "faulty", "child", "peer");
    holder.recordCapVersion(302);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(holder, out, TagRenderer.NONE)) {
      holder.attachWriter(writer);
      faulty.mode = Mode.THROW;
      invoke(HookKind.OUTBOUND_TAGS, holder, holder);
    }
    assertThat(lines(out)).containsExactly(":irc.example CAP * DEL :child faulty");
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("child")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("peer")).isEqualTo(Extension.State.ENABLED);
    assertThat(holder.negotiatedCapabilities()).containsExactly("peer");
    assertThat(order).containsExactly("child", "faulty");
    assertThat(warnings()).singleElement().asString().contains("'faulty'", "child");
    assertSanitized();
  }

  @Test
  void aStopThatFailsAfterAFaultKeepsTheProviderWithdrawnStillStopsTheRestAndIsRetriedLater() {
    List<String> stops = Collections.synchronizedList(new ArrayList<>());
    Faulty faulty = new Faulty("faulty", HookKind.OUTBOUND_TAGS);
    faulty.stopFailure = new IllegalStateException(SECRET);
    enable(faulty, new Child(stops));
    ClientSession holder = connect("holder", "faulty", "child");
    faulty.mode = Mode.INVALID;

    invoke(HookKind.OUTBOUND_TAGS, holder, holder);

    assertThat(stops).containsExactly("child");
    assertThat(faulty.stops).hasValue(1);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("child")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    assertThat(warnings())
        .hasSize(2)
        .anySatisfy(w -> assertThat(w).contains("'faulty'", "stop", "IllegalStateException"));
    assertSanitized();

    // The owed cleanup is retried by an explicit disable, never by the fault path again.
    faulty.stopFailure = null;
    registry.disable("faulty");
    assertThat(faulty.stops).hasValue(2);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void aFailedProviderStaysFailedUntilAnExplicitEnableWhichRestoresNoNegotiation() {
    Faulty faulty = new Faulty("faulty", HookKind.OUTBOUND_TAGS);
    enable(faulty);
    ClientSession holder = connect("holder", "faulty");
    holder.recordCapVersion(302);
    faulty.mode = Mode.THROW;
    invoke(HookKind.OUTBOUND_TAGS, holder, holder);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);

    // New captures no longer reach the hook, so nothing re-fails or recovers by itself.
    assertThat(registry.capabilitySnapshot().hooks(HookKind.OUTBOUND_TAGS)).isEmpty();
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);

    faulty.mode = null;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(holder, out, TagRenderer.NONE)) {
      holder.attachWriter(writer);
      registry.enable("faulty");
    }
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.ENABLED);
    assertThat(lines(out)).containsExactly(":irc.example CAP * NEW faulty");
    assertThat(holder.negotiatedCapabilities()).isEmpty();
  }

  @Test
  void aLateFaultFromWorkCapturedBeforeAReEnableIsAttributedToTheProviderAndWithdrawsItAgain() {
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty);
    ClientSession holder = connect("holder", "faulty");
    CapabilityRuntime.CapturedView old = registry.capabilityControl().capture(holder);
    faulty.mode = Mode.THROW;
    invoke(HookKind.MEMBER_STATUS, holder, holder);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);

    faulty.mode = null;
    registry.enable("faulty");
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.ENABLED);

    // Work captured before the first failure finishes now and faults again.
    faulty.mode = Mode.THROW;
    MemberSnapshot member = new MemberSnapshot("nick", "u", "h", List.of("@", "+"));
    assertThat(registry.hookDispatcher().memberStatus(old.snapshot(), old.negotiated(), member))
        .isEqualTo("@");
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    assertThat(faulty.stops).hasValue(2);
  }

  @Test
  void aFaultAfterTheProviderIsWithdrawnChangesNothingAndIsOnlyLoggedBounded() {
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty);
    ClientSession holder = connect("holder", "faulty");
    CapabilityRuntime.CapturedView old = registry.capabilityControl().capture(holder);
    faulty.mode = Mode.THROW;
    invoke(HookKind.MEMBER_STATUS, holder, holder);

    MemberSnapshot member = new MemberSnapshot("nick", "u", "h", List.of("@"));
    for (int i = 0; i < 50; i++) {
      registry.hookDispatcher().memberStatus(old.snapshot(), old.negotiated(), member);
    }
    assertThat(faulty.stops).hasValue(1);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    // The failure transition, then the late faults suppressed within the window.
    assertThat(warnings()).singleElement().asString().contains("FAILED");
    assertSanitized();
  }

  @Test
  void theFaultingCallNeverRunsTheWithdrawalAndOneIncidentSchedulesOneWithdrawal() {
    List<Runnable> scheduled = new ArrayList<>();
    registry = registry(scheduled::add);
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty);
    ClientSession holder = connect("holder", "faulty");
    faulty.mode = Mode.THROW;

    for (int i = 0; i < 100; i++) {
      assertThat(invoke(HookKind.MEMBER_STATUS, holder, holder)).isEqualTo("@");
    }
    assertThat(scheduled).hasSize(1);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.ENABLED);
    assertThat(faulty.stops).hasValue(0);

    scheduled.getFirst().run();
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(holder.negotiatedCapabilities()).isEmpty();
    // Faults reported while the incident was pending, then the transition, at most one line each.
    assertThat(warnings()).hasSizeLessThanOrEqualTo(2).anyMatch(w -> w.contains("FAILED"));
  }

  @Test
  void stopRunsWhileACapturedCallbackIsStillInFlightWithoutWaitingForIt() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger stops = new AtomicInteger();
    Extension blocking =
        new AbstractCapabilityExtension("faulty") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onMemberStatus(
                limits -> 2,
                member -> {
                  if (calls.incrementAndGet() > 1) throw new IllegalStateException(SECRET);
                  entered.countDown();
                  try {
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                  } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                  }
                  return "@";
                });
          }

          @Override
          public void stop() {
            stops.incrementAndGet();
          }
        };
    enable(blocking);
    ClientSession holder = connect("holder", "faulty");
    MemberSnapshot member = new MemberSnapshot("nick", "u", "h", List.of("@", "+"));
    RuntimeSnapshot captured = registry.capabilitySnapshot();
    AtomicReference<String> lateResult = new AtomicReference<>();
    Thread inFlight =
        Thread.ofVirtual()
            .start(
                () ->
                    lateResult.set(
                        registry
                            .hookDispatcher()
                            .memberStatus(captured, holder.negotiatedCapabilities(), member)));
    try {
      assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      registry.hookDispatcher().memberStatus(captured, holder.negotiatedCapabilities(), member);

      assertThat(stops).hasValue(1);
      assertThat(inFlight.isAlive()).isTrue();
      assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    } finally {
      release.countDown();
      inFlight.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    }
    // A valid result from older captured work is still allowed after the withdrawal.
    assertThat(lateResult.get()).isEqualTo("@");
  }

  @Test
  void validContributionsThatOnlyOverflowTogetherDropTheLatecomerWithoutFailingAnyone() {
    Extension first =
        new AbstractCapabilityExtension("first") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onOutboundTags(Set.of("a"), 4094, message -> Map.of("a", "x".repeat(3000)));
          }
        };
    Extension second =
        new AbstractCapabilityExtension("second") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onOutboundTags(Set.of("b"), 4094, message -> Map.of("b", "y".repeat(3000)));
          }
        };
    enable(first, second);
    ClientSession recipient = connect("r", "first", "second");
    OutboundMessage message = OutboundMessage.now("sender!ident@host", "PRIVMSG", "#chan", "hi");

    TagRenderer.PreparedTags tags = new CapabilityTagRenderer(registry).prepare(recipient, message);

    assertThat(tags.tags().get()).containsKey("a").doesNotContainKey("b");
    assertThat(registry.stateOf("first")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.stateOf("second")).isEqualTo(Extension.State.ENABLED);
    assertThat(warnings())
        .singleElement()
        .asString()
        .contains("'second'", "COMPOSITION_LIMIT")
        .doesNotContain("FAILED");
  }

  @Test
  void aProviderDisabledBeforeItsPendingWithdrawalRunsIsLeftDisabled() {
    List<Runnable> scheduled = new ArrayList<>();
    registry = registry(scheduled::add);
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty);
    ClientSession holder = connect("holder", "faulty");
    faulty.mode = Mode.THROW;
    invoke(HookKind.MEMBER_STATUS, holder, holder);

    registry.disable("faulty");
    scheduled.getFirst().run();

    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.DISABLED);
    assertThat(faulty.stops).hasValue(1);
  }

  @Test
  void aWithdrawalThatCannotBeScheduledIsReportedAndTheNextFaultTriesAgain() {
    AtomicInteger attempts = new AtomicInteger();
    registry =
        registry(
            task -> {
              if (attempts.incrementAndGet() == 1) throw new RejectedExecutionException("full");
              task.run();
            });
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty);
    ClientSession holder = connect("holder", "faulty");
    faulty.mode = Mode.THROW;

    invoke(HookKind.MEMBER_STATUS, holder, holder);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.ENABLED);
    assertThat(warnings())
        .singleElement()
        .asString()
        .contains("'faulty'", "could not be scheduled");

    invoke(HookKind.MEMBER_STATUS, holder, holder);
    assertThat(attempts).hasValue(2);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertSanitized();
  }

  @Test
  void aStopThatThrowsAnErrorStillLeavesEveryWithdrawnProviderFailedAndStoppedThenPropagates() {
    // Sourcery, PR #112 comment 3: an Error is not contained as a provider failure, but it must not
    // leave a withdrawn provider ENABLED or skip the stops after it.
    List<Runnable> scheduled = new ArrayList<>();
    registry = registry(scheduled::add);
    Faulty faulty = new Faulty("faulty", HookKind.OUTBOUND_TAGS);
    Extension child =
        new AbstractCapabilityExtension("child") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.requiresProviders("faulty");
          }

          @Override
          public void stop() {
            throw new AssertionError("child stop");
          }
        };
    enable(faulty, child);
    ClientSession holder = connect("holder", "faulty", "child");
    faulty.mode = Mode.THROW;
    invoke(HookKind.OUTBOUND_TAGS, holder, holder);

    assertThatThrownBy(scheduled.getFirst()::run).isInstanceOf(AssertionError.class);
    assertThat(faulty.stops).as("the prerequisite is still stopped").hasValue(1);
    assertThat(registry.stateOf("child")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void aThrowingPublicationObserverChangesNoPublicationsOutcome() {
    // Sourcery, PR #112 comment 1: the observer runs after the publication has committed.
    registry.observeCapabilityPublications(
        timing -> {
          throw new IllegalStateException("observer");
        });
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("faulty");

    ClientSession holder = connect("holder", "faulty");
    faulty.mode = Mode.THROW;
    invoke(HookKind.MEMBER_STATUS, holder, holder);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(faulty.stops).hasValue(1);
  }

  @Test
  void eachPublicationThatChangesTheOfferedSetReportsItsStageTimings() {
    List<CapabilityRuntime.PublicationTiming> timings = new ArrayList<>();
    registry.observeCapabilityPublications(timings::add);
    connect("one");
    connect("two");
    ClientSession holder = connect("three", "faulty");
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty);
    registry.updateConfiguredLengths(30, 50, 300, 4); // limits only: the offered set is unchanged
    faulty.mode = Mode.THROW;
    invoke(HookKind.MEMBER_STATUS, holder, holder);

    // The enable and the fault's withdrawal.
    assertThat(timings)
        .hasSize(2)
        .allSatisfy(
            timing -> {
              assertThat(timing.sessions()).isEqualTo(3);
              assertThat(timing.waitNanos()).isNotNegative();
              assertThat(timing.publishNanos()).isNotNegative();
              assertThat(timing.notifyNanos()).isNotNegative();
            });
  }

  private static ExtensionRegistry.DesiredState desired(Set<String> changed) {
    return new ExtensionRegistry.DesiredState(
        Map.of("faulty", true, "child", true), Map.of(), 30, 50, 300, 4, changed);
  }

  @Test
  void aReloadRetriesAFailedProviderOrDependentOnlyWhenItsOwnEntryChanged() {
    List<String> stops = Collections.synchronizedList(new ArrayList<>());
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty, new Child(stops));
    ClientSession holder = connect("holder", "faulty");
    faulty.mode = Mode.THROW;
    invoke(HookKind.MEMBER_STATUS, holder, holder);
    faulty.mode = null;
    assertThat(registry.stateOf("child")).isEqualTo(Extension.State.FAILED);

    assertThat(registry.applyDesiredState(desired(Set.of()), () -> {})).isEmpty();
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("child")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();

    assertThat(registry.applyDesiredState(desired(Set.of("faulty")), () -> {})).isEmpty();
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.stateOf("child")).isEqualTo(Extension.State.FAILED);

    assertThat(registry.applyDesiredState(desired(Set.of("child")), () -> {})).isEmpty();
    assertThat(registry.stateOf("child")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("child", "faulty");
  }

  @Test
  void aFaultByAReEnabledInstanceStartsItsOwnIncidentWhileTheOldOneIsStillOpen() {
    // Review round 1, A1: the old instance's incident is still open (its withdrawal has not run)
    // when the provider is re-enabled and the new instance faults. That fault must not be
    // swallowed by the old marker.
    List<Runnable> scheduled = new ArrayList<>();
    registry = registry(scheduled::add);
    Faulty faulty = new Faulty("faulty", HookKind.MEMBER_STATUS);
    enable(faulty);
    ClientSession holder = connect("holder", "faulty");
    faulty.mode = Mode.THROW;
    invoke(HookKind.MEMBER_STATUS, holder, holder);
    assertThat(scheduled).hasSize(1);

    registry.disable("faulty");
    registry.enable("faulty");
    holder.replaceNegotiatedCapabilities(List.of("faulty")); // pruned by the disable
    invoke(HookKind.MEMBER_STATUS, holder, holder);
    assertThat(scheduled).hasSize(2);

    // The old incident's withdrawal is attributed to the provider and withdraws the new instance;
    // the second finds nothing left to withdraw.
    scheduled.forEach(Runnable::run);
    assertThat(registry.stateOf("faulty")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }
}
