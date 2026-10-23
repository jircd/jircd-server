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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.OutboundMessage;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

class CapabilityDispatchTest {
  private record Fault(String provider, HookKind kind, FaultCategory category) {}

  private final List<Fault> faults = new ArrayList<>();
  private final HookDispatcher dispatcher =
      new HookDispatcher(
          (provider, kind, category) -> faults.add(new Fault(provider, kind, category)));

  private static RuntimeSnapshot snapshotOf(String id, Consumer<CapabilityRegistrar> staging) {
    CapabilityRegistrar registrar = new CapabilityRegistrar(id, id);
    staging.accept(registrar);
    return RuntimeSnapshot.of(List.of(registrar.build()));
  }

  private static ClientSession session(String name) {
    ClientSession session = new ClientSession(name, "host", RateLimitBucket.withDefaults());
    session.setNickname(name);
    return session;
  }

  private static final RequestContext CONTEXT = RequestContext.of(1, Map.of());

  // ---- events

  private static CommittedEvent event(ClientSession... peers) {
    return new CommittedEvent(
        EventKind.AWAY_CHANGED, 7, "n!u@h", "", "gone", new Audience(() -> List.of(peers)));
  }

  @Test
  void eventHookIntentsAreFilteredToNegotiatedAudienceMembers() {
    ClientSession listening = session("a");
    listening.replaceNegotiatedCapabilities(Set.of("watcher"));
    ClientSession plain = session("b");
    Message notice = Message.of(Command.AWAY, "gone");
    RuntimeSnapshot snapshot =
        snapshotOf(
            "watcher",
            r ->
                r.onEvent(
                    EventKind.AWAY_CHANGED,
                    e -> List.of(new EventIntent(notice, List.of(listening, plain)))));
    List<EventIntent> intents = dispatcher.event(snapshot, event(listening, plain));
    assertThat(intents).hasSize(1);
    assertThat(intents.getFirst().recipients()).containsExactly(listening);
    assertThat(faults).isEmpty();
  }

  @Test
  void eventHooksOnlyRunForTheirKindAndNoListenerMeansNoAudienceTraversal() {
    int[] traversed = {0};
    Audience audience =
        new Audience(
            () -> {
              traversed[0]++;
              return List.of();
            });
    CommittedEvent committed = new CommittedEvent(EventKind.JOINED, 1, "n!u@h", "", "#c", audience);
    dispatcher.event(snapshotOf("p", r -> r.onEvent(EventKind.PARTED, e -> List.of())), committed);
    dispatcher.event(RuntimeSnapshot.EMPTY, committed);
    assertThat(traversed[0]).isZero();
  }

  @Test
  void eventIntentsCannotWidenTheAudience() {
    ClientSession inside = session("in");
    inside.replaceNegotiatedCapabilities(Set.of("watcher"));
    ClientSession outside = session("out");
    outside.replaceNegotiatedCapabilities(Set.of("watcher"));
    RuntimeSnapshot snapshot =
        snapshotOf(
            "watcher",
            r ->
                r.onEvent(
                    EventKind.AWAY_CHANGED,
                    e ->
                        List.of(
                            new EventIntent(
                                Message.of(Command.AWAY, "x"), List.of(inside, outside)))));
    assertThat(dispatcher.event(snapshot, event(inside))).isEmpty();
    assertThat(faults)
        .containsExactly(new Fault("watcher", HookKind.EVENT, FaultCategory.INVALID_RESULT));
  }

  @Test
  void eventAudienceIsComputedOnceAndDeduplicated() {
    ClientSession peer = session("a");
    int[] calls = {0};
    Audience audience =
        new Audience(
            () -> {
              calls[0]++;
              return List.of(peer, peer);
            });
    assertThat(audience.peers()).containsExactly(peer);
    assertThat(audience.peers()).containsExactly(peer);
    assertThat(calls[0]).isEqualTo(1);
  }

  @Test
  void throwingNullAndOversizedEventResultsAreContainedAndFallBackToNoDelivery() {
    ClientSession peer = session("a");
    peer.replaceNegotiatedCapabilities(Set.of("a", "b", "c"));
    RuntimeSnapshot thrown =
        snapshotOf(
            "a",
            r ->
                r.onEvent(
                    EventKind.AWAY_CHANGED,
                    e -> {
                      throw new IllegalStateException("secret payload");
                    }));
    RuntimeSnapshot nulls = snapshotOf("b", r -> r.onEvent(EventKind.AWAY_CHANGED, e -> null));
    RuntimeSnapshot many =
        snapshotOf(
            "c",
            r ->
                r.onEvent(
                    EventKind.AWAY_CHANGED,
                    e ->
                        java.util.Collections.nCopies(
                            HookDispatcher.MAX_EVENT_INTENTS + 1,
                            new EventIntent(Message.of(Command.AWAY, "x"), List.of(peer)))));
    assertThat(dispatcher.event(thrown, event(peer))).isEmpty();
    assertThat(dispatcher.event(nulls, event(peer))).isEmpty();
    assertThat(dispatcher.event(many, event(peer))).isEmpty();
    assertThat(faults)
        .extracting(Fault::category)
        .containsExactly(
            FaultCategory.EXCEPTION, FaultCategory.NULL_RESULT, FaultCategory.INVALID_RESULT);
  }

  @Test
  void aFaultingProviderDoesNotStopTheOthers() {
    ClientSession peer = session("a");
    peer.replaceNegotiatedCapabilities(Set.of("good", "bad"));
    CapabilityRegistrar bad = new CapabilityRegistrar("bad", "bad");
    bad.onEvent(
        EventKind.AWAY_CHANGED,
        e -> {
          throw new IllegalStateException();
        });
    CapabilityRegistrar good = new CapabilityRegistrar("good", "good");
    good.onEvent(
        EventKind.AWAY_CHANGED,
        e -> List.of(new EventIntent(Message.of(Command.AWAY), List.of(peer))));
    RuntimeSnapshot snapshot = RuntimeSnapshot.of(List.of(bad.build(), good.build()));
    assertThat(dispatcher.event(snapshot, event(peer))).hasSize(1);
    assertThat(faults).extracting(Fault::provider).containsExactly("bad");
  }

  @Test
  void linkageErrorsAreContainedButVirtualMachineErrorsPropagate() {
    RuntimeSnapshot linkage =
        snapshotOf(
            "a",
            r ->
                r.onEvent(
                    EventKind.AWAY_CHANGED,
                    e -> {
                      throw new NoClassDefFoundError("missing");
                    }));
    assertThat(dispatcher.event(linkage, event())).isEmpty();
    RuntimeSnapshot fatal =
        snapshotOf(
            "b",
            r ->
                r.onEvent(
                    EventKind.AWAY_CHANGED,
                    e -> {
                      throw new OutOfMemoryError();
                    }));
    CommittedEvent committed = event();
    assertThatThrownBy(() -> dispatcher.event(fatal, committed))
        .isInstanceOf(OutOfMemoryError.class);
  }

  // ---- inbound tags

  @Test
  void inboundTagsSelectOnlyClientTagsPresentInTheInput() {
    RuntimeSnapshot snapshot =
        snapshotOf(
            "mt",
            r ->
                r.onInboundTags(
                    (in, ctx) ->
                        new InboundTagResult(
                            Map.of("+a", "1", "+forged", "x"), Map.of("mt/seen", "yes"))));
    InboundTags input = new InboundTags("PRIVMSG", Map.of("+a", "1"));
    InboundTagResult result = dispatcher.inboundTags(snapshot, Set.of("mt"), input, CONTEXT);
    assertThat(result).isEqualTo(InboundTagResult.NONE);
    assertThat(faults)
        .containsExactly(new Fault("mt", HookKind.INBOUND_TAGS, FaultCategory.INVALID_RESULT));
  }

  @Test
  void validInboundTagResultIsMergedAndSenderPrerequisitesGateIt() {
    CapabilityRegistrar registrar = new CapabilityRegistrar("mt", "mt");
    registrar.onInboundTags(
        Set.of("gate"),
        (in, ctx) -> new InboundTagResult(Map.of("+a", "1"), Map.of("mt/seen", "yes")));
    RuntimeSnapshot snapshot = RuntimeSnapshot.of(List.of(registrar.build()));
    InboundTags input = new InboundTags("PRIVMSG", Map.of("+a", "1", "+b", "2"));
    assertThat(dispatcher.inboundTags(snapshot, Set.of("mt"), input, CONTEXT))
        .isEqualTo(InboundTagResult.NONE);
    InboundTagResult gated = dispatcher.inboundTags(snapshot, Set.of("gate"), input, CONTEXT);
    assertThat(gated.forwarded()).containsExactly(Map.entry("+a", "1"));
    assertThat(gated.context()).containsExactly(Map.entry("mt/seen", "yes"));
    assertThat(faults).isEmpty();
  }

  @Test
  void inboundContextMustBeProviderNamespacedAndBounded() {
    RuntimeSnapshot snapshot =
        snapshotOf(
            "mt",
            r ->
                r.onInboundTags(
                    (in, ctx) -> new InboundTagResult(Map.of(), Map.of("other/key", "v"))));
    assertThat(
            dispatcher.inboundTags(
                snapshot, Set.of(), new InboundTags("PRIVMSG", Map.of()), CONTEXT))
        .isEqualTo(InboundTagResult.NONE);
    assertThat(faults).hasSize(1);
  }

  // ---- recipients

  @Test
  void recipientHookAddsOnlySenderCopyWhenSenderNegotiatedTheOwner() {
    RuntimeSnapshot snapshot =
        snapshotOf("echo", r -> r.onRecipients((intent, ctx) -> Set.of(DeliveryRole.SENDER_COPY)));
    DeliveryIntent intent =
        new DeliveryIntent(UUID.randomUUID(), Set.of(DeliveryRole.ORDINARY_TARGET));
    assertThat(dispatcher.recipientRoles(snapshot, Set.of(), intent, CONTEXT)).isEmpty();
    assertThat(dispatcher.recipientRoles(snapshot, Set.of("echo"), intent, CONTEXT))
        .containsExactly(DeliveryRole.SENDER_COPY);
  }

  @Test
  void recipientHookResultOutsideItsPermittedScopeIsAFault() {
    RuntimeSnapshot snapshot =
        snapshotOf(
            "echo", r -> r.onRecipients((intent, ctx) -> Set.of(DeliveryRole.REQUEST_REPLY)));
    DeliveryIntent intent =
        new DeliveryIntent(UUID.randomUUID(), Set.of(DeliveryRole.ORDINARY_TARGET));
    assertThat(dispatcher.recipientRoles(snapshot, Set.of("echo"), intent, CONTEXT)).isEmpty();
    RuntimeSnapshot nulls = snapshotOf("n", r -> r.onRecipients((i, ctx) -> null));
    assertThat(dispatcher.recipientRoles(nulls, Set.of("n"), intent, CONTEXT)).isEmpty();
    assertThat(faults)
        .extracting(Fault::category)
        .containsExactly(FaultCategory.INVALID_RESULT, FaultCategory.NULL_RESULT);
  }

  // ---- outbound tags

  private static OutboundMessage message() {
    return OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hi");
  }

  private static HookRegistration tagHook(Set<String> keys, long max, Map<String, String> result) {
    CapabilityRegistrar registrar = new CapabilityRegistrar("tagger", "tagger");
    registrar.onOutboundTags(keys, max, m -> result);
    return registrar.build().registrations().getFirst();
  }

  @Test
  void outboundTagContributionIsValidatedAgainstOwnedKeysAndDeclaredBound() {
    assertThat(dispatcher.outboundTags(tagHook(Set.of("a"), 16, Map.of("a", "v")), message()))
        .containsExactly(Map.entry("a", "v"));
    assertThat(dispatcher.outboundTags(tagHook(Set.of("a"), 16, Map.of()), message())).isEmpty();
    assertThat(faults).isEmpty();
    // forged key, over declared bound, null value, null map
    assertThat(dispatcher.outboundTags(tagHook(Set.of("a"), 16, Map.of("b", "v")), message()))
        .isEmpty();
    assertThat(dispatcher.outboundTags(tagHook(Set.of("a"), 4, Map.of("a", "toolong")), message()))
        .isEmpty();
    Map<String, String> nullValue = new java.util.HashMap<>();
    nullValue.put("a", null);
    assertThat(dispatcher.outboundTags(tagHook(Set.of("a"), 16, nullValue), message())).isEmpty();
    assertThat(dispatcher.outboundTags(tagHook(Set.of("a"), 16, null), message())).isEmpty();
    assertThat(faults)
        .extracting(Fault::category)
        .containsExactly(
            FaultCategory.INVALID_RESULT,
            FaultCategory.INVALID_RESULT,
            FaultCategory.INVALID_RESULT,
            FaultCategory.NULL_RESULT);
  }

  @Test
  void outboundTagBoundCountsEscapedBytes() {
    // "a=\:\s": key 1 + '=' + the value "; " escaped to 4 bytes = 6 data bytes
    HookRegistration tight = tagHook(Set.of("a"), 5, Map.of("a", "; "));
    assertThat(dispatcher.outboundTags(tight, message())).isEmpty();
    assertThat(faults).hasSize(1);
    HookRegistration roomy = tagHook(Set.of("a"), 6, Map.of("a", "; "));
    assertThat(dispatcher.outboundTags(roomy, message())).containsEntry("a", "; ");
  }

  @Test
  void outboundTagResultIsImmutableAndDetachedFromTheProvidersMap() {
    Map<String, String> source = new java.util.HashMap<>(Map.of("a", "v"));
    HookRegistration hook = tagHook(Set.of("a"), 16, source);
    Map<String, String> result = dispatcher.outboundTags(hook, message());
    source.put("a", "changed");
    assertThat(result).containsEntry("a", "v");
    assertThatThrownBy(() -> result.put("b", "c"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ---- member status / identity / fields

  private static MemberSnapshot member() {
    return new MemberSnapshot("nick", "id", "host", List.of("@", "+"));
  }

  @Test
  void memberStatusDefaultsToHighestAndHookReplacesWithOrderedHeldSymbols() {
    RuntimeSnapshot snapshot =
        snapshotOf(
            "mp", r -> r.onMemberStatus(limits -> 2, m -> String.join("", m.heldStatuses())));
    assertThat(dispatcher.memberStatus(snapshot, Set.of(), member())).isEqualTo("@");
    assertThat(dispatcher.memberStatus(snapshot, Set.of("mp"), member())).isEqualTo("@+");
    assertThat(dispatcher.memberStatus(RuntimeSnapshot.EMPTY, Set.of("mp"), member()))
        .isEqualTo("@");
    assertThat(
            dispatcher.memberStatus(
                RuntimeSnapshot.EMPTY, Set.of(), new MemberSnapshot("n", "i", "h", List.of())))
        .isEmpty();
  }

  @Test
  void memberStatusOutsideHeldOrderedOrBoundedSymbolsIsAFaultAndFallsBack() {
    for (String bad : new String[] {"+@", "%", "@@", "@+!"}) {
      RuntimeSnapshot snapshot = snapshotOf("mp", r -> r.onMemberStatus(limits -> 2, m -> bad));
      assertThat(dispatcher.memberStatus(snapshot, Set.of("mp"), member())).isEqualTo("@");
    }
    RuntimeSnapshot nulls = snapshotOf("n", r -> r.onMemberStatus(limits -> 2, m -> null));
    assertThat(dispatcher.memberStatus(nulls, Set.of("n"), member())).isEqualTo("@");
    RuntimeSnapshot thrown =
        snapshotOf(
            "t",
            r ->
                r.onMemberStatus(
                    limits -> 2,
                    m -> {
                      throw new IllegalStateException();
                    }));
    assertThat(dispatcher.memberStatus(thrown, Set.of("t"), member())).isEqualTo("@");
    assertThat(faults).hasSize(6);
  }

  @Test
  void memberStatusLongerThanItsDeclaredBoundIsAFault() {
    RuntimeSnapshot snapshot =
        snapshotOf(
            "mp", r -> r.onMemberStatus(limits -> 1, m -> String.join("", m.heldStatuses())));
    assertThat(dispatcher.memberStatus(snapshot, Set.of("mp"), member())).isEqualTo("@");
    assertThat(faults).hasSize(1);
  }

  @Test
  void namesIdentityDefaultsToNicknameAndRejectsUnsafeOrOversizedTokens() {
    RuntimeSnapshot snapshot =
        snapshotOf(
            "uh",
            r ->
                r.onNamesIdentity(
                    limits -> 100, m -> m.nickname() + "!" + m.ident() + "@" + m.displayHost()));
    assertThat(dispatcher.namesIdentity(snapshot, Set.of(), member())).isEqualTo("nick");
    assertThat(dispatcher.namesIdentity(snapshot, Set.of("uh"), member()))
        .isEqualTo("nick!id@host");
    for (String bad : new String[] {"", "has space", "x".repeat(200), "line\r\nbreak"}) {
      RuntimeSnapshot broken = snapshotOf("b", r -> r.onNamesIdentity(limits -> 100, m -> bad));
      assertThat(dispatcher.namesIdentity(broken, Set.of("b"), member())).isEqualTo("nick");
    }
    assertThat(faults).hasSize(4);
  }

  @Test
  void replyFieldContributionIsBoundedAndOptional() {
    ReplySlot slot = new ReplySlot("extra");
    CapabilityRegistrar registrar = new CapabilityRegistrar("f", "f", Set.of(slot));
    registrar.onReplyField(slot, limits -> 3, m -> m.nickname().length() > 3 ? "toolong" : "ok");
    RuntimeSnapshot snapshot = RuntimeSnapshot.of(List.of(registrar.build()));
    assertThat(
            dispatcher.replyField(
                snapshot, Set.of("f"), slot, new MemberSnapshot("ab", "i", "h", List.of())))
        .contains("ok");
    assertThat(dispatcher.replyField(snapshot, Set.of(), slot, member())).isEmpty();
    assertThat(dispatcher.replyField(snapshot, Set.of("f"), slot, member())).isEmpty();
    assertThat(faults).hasSize(1);
  }

  // ---- output groups

  private static OutputGroup group() {
    return new OutputGroup(
        "g1", "test", List.of(), List.of(Message.of(Command.PRIVMSG, "#c", "hi")), 2048);
  }

  @Test
  void outputGroupFallsBackToPlainMembersWithoutOwnerOrPrerequisites() {
    Message open = new Message(Map.of(), null, null, "BATCH", List.of("+r", "test"));
    Message close = new Message(Map.of(), null, null, "BATCH", List.of("-r"));
    CapabilityRegistrar registrar = new CapabilityRegistrar("b", "b");
    registrar.onOutputGroup(
        Set.of("mt"), (g, ctx) -> new FramingPlan(List.of(open, g.members().getFirst(), close)));
    RuntimeSnapshot snapshot = RuntimeSnapshot.of(List.of(registrar.build()));
    FramingPlan plain = FramingPlan.plain(group());
    assertThat(dispatcher.outputGroup(snapshot, Set.of(), group(), CONTEXT).frames())
        .isEqualTo(plain.frames());
    assertThat(dispatcher.outputGroup(snapshot, Set.of("b"), group(), CONTEXT).frames())
        .isEqualTo(plain.frames());
    assertThat(dispatcher.outputGroup(snapshot, Set.of("b", "mt"), group(), CONTEXT).frames())
        .hasSize(3);
    assertThat(faults).isEmpty();
  }

  @Test
  void illegalFramingPlansAreFaultsAndFallBackToPlainMembers() {
    OutputGroup group = group();
    Message tooBig = Message.of(Command.TOPIC, "#c", "x".repeat(4000));
    FramingPlan[] bad = {
      new FramingPlan(List.of()),
      new FramingPlan(List.of(tooBig)),
      new FramingPlan(
          java.util.Collections.nCopies(
              HookDispatcher.MAX_FRAMING_OVERHEAD_FRAMES + 2, group.members().getFirst())),
    };
    for (FramingPlan plan : bad) {
      RuntimeSnapshot snapshot = snapshotOf("b", r -> r.onOutputGroup(Set.of(), (g, ctx) -> plan));
      assertThat(dispatcher.outputGroup(snapshot, Set.of("b"), group, CONTEXT).frames())
          .isEqualTo(group.members());
    }
    RuntimeSnapshot nulls = snapshotOf("n", r -> r.onOutputGroup(Set.of(), (g, ctx) -> null));
    assertThat(dispatcher.outputGroup(nulls, Set.of("n"), group, CONTEXT).frames())
        .isEqualTo(group.members());
    assertThat(faults).hasSize(4);
  }

  @Test
  void outputGroupByteBoundIsEnforcedBeforeAcceptingThePlan() {
    OutputGroup tight =
        new OutputGroup("g", "t", List.of(), List.of(Message.of(Command.PRIVMSG, "#c", "hi")), 10);
    RuntimeSnapshot snapshot =
        snapshotOf("b", r -> r.onOutputGroup(Set.of(), (g, ctx) -> new FramingPlan(g.members())));
    assertThat(dispatcher.outputGroup(snapshot, Set.of("b"), tight, CONTEXT).frames())
        .isEqualTo(tight.members());
    assertThat(faults).hasSize(1);
  }

  // ---- commands

  @Test
  void commandHandlerExceptionsAreContained() {
    int[] ran = {0};
    CapabilityRegistrar registrar = new CapabilityRegistrar("cmd", "cmd");
    registrar.command(
        Command.TAGMSG,
        (session, message) -> {
          ran[0]++;
          throw new IllegalStateException("bad");
        });
    HookRegistration hook = registrar.build().registrations().getFirst();
    var guarded = dispatcher.guardedCommand(hook);
    guarded.handle(session("a"), Message.of(Command.TAGMSG, "#c"));
    assertThat(ran[0]).isEqualTo(1);
    assertThat(faults).containsExactly(new Fault("cmd", HookKind.COMMAND, FaultCategory.EXCEPTION));
  }

  // ---- locks

  @Test
  void noHookRunsWhileTheCallerHoldsTheDispatcherOrSnapshotMonitor() {
    boolean[] heldDuringCallback = {false};
    RuntimeSnapshot[] holder = new RuntimeSnapshot[1];
    holder[0] =
        snapshotOf(
            "p",
            r ->
                r.onEvent(
                    EventKind.AWAY_CHANGED,
                    e -> {
                      heldDuringCallback[0] =
                          Thread.holdsLock(dispatcher) || Thread.holdsLock(holder[0]);
                      return List.of();
                    }));
    dispatcher.event(holder[0], event());
    assertThat(heldDuringCallback[0]).isFalse();
  }

  // ---- group plans must cover the members

  private static Message batch(String param) {
    return new Message(Map.of(), null, null, "BATCH", List.of(param, "test"));
  }

  @Test
  void groupPlansMustCoverEveryMemberInOrderWithMatchedFraming() {
    Message m1 = Message.of(Command.PRIVMSG, "#c", "one");
    Message m2 = Message.of(Command.PRIVMSG, "#c", "two");
    OutputGroup two = new OutputGroup("g", "test", List.of(), List.of(m1, m2), 4096);
    Message tagged1 =
        new Message(Map.of("batch", "r"), null, Command.PRIVMSG, "PRIVMSG", m1.params());
    Message tagged2 =
        new Message(Map.of("batch", "r"), null, Command.PRIVMSG, "PRIVMSG", m2.params());
    FramingPlan good = new FramingPlan(List.of(batch("+r"), tagged1, tagged2, batch("-r")));
    FramingPlan[] bad = {
      new FramingPlan(List.of(batch("+r"))), // opener only, members lost
      new FramingPlan(List.of(batch("+r"), tagged1, tagged2)), // never closed
      new FramingPlan(List.of(tagged1, tagged2, batch("-r"))), // close without open
      new FramingPlan(List.of(batch("+r"), tagged2, tagged1, batch("-r"))), // reordered
      new FramingPlan(List.of(batch("+r"), tagged1, batch("-r"))), // member dropped
      new FramingPlan(List.of(batch("+r"), tagged1, batch("-r"), tagged2)), // member outside
      new FramingPlan(List.of(tagged2, tagged1, tagged2, tagged1)), // members posing as framing
      new FramingPlan(List.of(batch("+r"), tagged1, tagged2, batch("+s"))), // never closed
      new FramingPlan(List.of(batch("+r"), tagged1, tagged2, batch("-s"))), // wrong reference
    };
    RuntimeSnapshot ok = snapshotOf("b", r -> r.onOutputGroup(Set.of(), (g, ctx) -> good));
    assertThat(dispatcher.outputGroup(ok, Set.of("b"), two, CONTEXT).frames()).hasSize(4);
    assertThat(faults).isEmpty();
    for (FramingPlan plan : bad) {
      RuntimeSnapshot snapshot = snapshotOf("b", r -> r.onOutputGroup(Set.of(), (g, ctx) -> plan));
      assertThat(dispatcher.outputGroup(snapshot, Set.of("b"), two, CONTEXT).frames())
          .isEqualTo(two.members());
    }
    assertThat(faults).hasSize(bad.length);
  }

  // ---- bounds follow the configured limits

  @Test
  void replyBoundsAreEvaluatedAgainstTheConfiguredLimitsNotTheReferenceOnes() {
    RuntimeSnapshot reference =
        snapshotOf(
            "uh",
            r -> r.onNamesIdentity(ReplyLimits::nicknameMax, m -> m.nickname() + "x".repeat(20)));
    RuntimeSnapshot configured = reference.withLimits(ReplyLimits.of(16, 9, 50));
    assertThat(dispatcher.namesIdentity(reference, Set.of("uh"), member()))
        .isEqualTo("nick" + "x".repeat(20));
    assertThat(dispatcher.namesIdentity(configured, Set.of("uh"), member())).isEqualTo("nick");
    assertThat(faults).hasSize(1);
    RuntimeSnapshot fits =
        snapshotOf(
                "fit",
                r -> r.onNamesIdentity(ReplyLimits::nicknameMax, m -> m.nickname() + "xxxxx"))
            .withLimits(ReplyLimits.of(16, 9, 50));
    assertThat(dispatcher.namesIdentity(fits, Set.of("fit"), member())).isEqualTo("nickxxxxx");
  }

  @Test
  void aServerTagValueCarryingNulIsAFault() {
    assertThat(dispatcher.outboundTags(tagHook(Set.of("a"), 16, Map.of("a", "x\0y")), message()))
        .isEmpty();
    assertThat(faults).hasSize(1);
  }
}
