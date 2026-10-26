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

import java.util.List;
import java.util.Set;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.MemberSnapshot;
import org.junit.jupiter.api.Test;

/** Member facts carry no capability flag; one rendering service applies the requester's hooks. */
class MemberReplyRenderingTest {

  /** Replaces the status slot with every held symbol, as multi-prefix does. */
  private static final class AllStatuses extends AbstractCapabilityExtension {
    AllStatuses() {
      super("all-statuses");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onMemberStatus(limits -> 2, member -> String.join("", member.heldStatuses()));
    }
  }

  /** Replaces the NAMES identity with a full hostmask, independently of the status slot. */
  private static final class FullIdentity extends AbstractCapabilityExtension {
    FullIdentity() {
      super("full-identity");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onNamesIdentity(
          limits -> 200,
          member -> member.nickname() + "!" + member.ident() + "@" + member.displayHost());
    }
  }

  private final ExtensionRegistry extensions = new ExtensionRegistry();
  private final MemberReplies replies = new MemberReplies(extensions);
  private final Channel channel = new Channel("#c");

  private static ClientSession user(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("ident");
    return session;
  }

  private ClientSession requester(String... negotiated) {
    ClientSession requester = user("requester");
    requester.replaceNegotiatedCapabilities(List.of(negotiated));
    return requester;
  }

  private Channel populated() {
    ClientSession op = user("op");
    ClientSession both = user("both");
    ClientSession voice = user("voice");
    ClientSession plain = user("plain");
    for (ClientSession member : List.of(op, both, voice, plain)) {
      channel.addMember(member);
      member.addMembership(channel);
    }
    channel.grantOperator(both);
    channel.grantVoice(both);
    channel.grantVoice(voice);
    return channel;
  }

  @Test
  void factsListEveryHeldStatusHighestFirstWithoutAnyCapabilityFlag() {
    populated();

    assertThat(channel.memberFacts())
        .extracting(fact -> fact.member().nickname(), MemberFacts::heldStatuses)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("op", List.of("@")),
            org.assertj.core.groups.Tuple.tuple("both", List.of("@", "+")),
            org.assertj.core.groups.Tuple.tuple("voice", List.of("+")),
            org.assertj.core.groups.Tuple.tuple("plain", List.of()));
  }

  @Test
  void withoutAProviderEveryRequesterGetsTheHighestStatusOnly() {
    populated();

    assertThat(replies.namesTokens(requester("multi-prefix"), channel.memberFacts()))
        .containsExactlyInAnyOrder("@op", "@both", "+voice", "plain");
    assertThat(channel.memberListing())
        .containsExactlyInAnyOrder("@op", "@both", "+voice", "plain");
  }

  @Test
  void aStatusProviderReplacesTheSlotOnlyForRequestersThatNegotiatedIt() {
    populated();
    extensions.register(new AllStatuses());
    extensions.enable("all-statuses");

    assertThat(replies.namesTokens(requester("all-statuses"), channel.memberFacts()))
        .containsExactlyInAnyOrder("@op", "@+both", "+voice", "plain");
    assertThat(replies.namesTokens(requester(), channel.memberFacts()))
        .containsExactlyInAnyOrder("@op", "@both", "+voice", "plain");
  }

  @Test
  void whoFlagsAppendTheStatusSlotOnly() {
    populated();
    extensions.register(new AllStatuses());
    extensions.enable("all-statuses");
    ClientSession both = channel.findMember("both").orElseThrow();

    assertThat(replies.whoStatus(requester("all-statuses"), channel.memberFacts(both)))
        .isEqualTo("@+");
    assertThat(replies.whoStatus(requester(), channel.memberFacts(both))).isEqualTo("@");
    assertThat(replies.whoStatus(requester(), channel.memberFacts(user("stranger")))).isEmpty();
  }

  @Test
  void identityAndStatusSlotsComposeIndependently() {
    populated();
    extensions.register(new AllStatuses());
    extensions.enable("all-statuses");
    extensions.register(new FullIdentity());
    extensions.enable("full-identity");

    assertThat(
            replies.namesTokens(requester("all-statuses", "full-identity"), channel.memberFacts()))
        .containsExactlyInAnyOrder(
            "@op!ident@host.example",
            "@+both!ident@host.example",
            "+voice!ident@host.example",
            "plain!ident@host.example");
    assertThat(replies.namesTokens(requester("full-identity"), channel.memberFacts()))
        .containsExactlyInAnyOrder(
            "@op!ident@host.example",
            "@both!ident@host.example",
            "+voice!ident@host.example",
            "plain!ident@host.example");
  }

  @Test
  void aProviderThatFaultsLeavesTheCoreDefault() {
    populated();
    extensions.register(
        new AbstractCapabilityExtension("broken") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onMemberStatus(limits -> 2, member -> "#");
          }
        });
    extensions.enable("broken");

    assertThat(replies.namesTokens(requester("broken"), channel.memberFacts()))
        .containsExactlyInAnyOrder("@op", "@both", "+voice", "plain");
  }

  @Test
  void identityFactsAreOnlyResolvedWhenAProviderNeedsThem() {
    populated();
    java.util.concurrent.atomic.AtomicReference<MemberSnapshot> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    extensions.register(
        new AbstractCapabilityExtension("peek") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onMemberStatus(
                limits -> 2,
                member -> {
                  seen.set(member);
                  return member.highestStatus();
                });
          }
        });
    extensions.enable("peek");

    replies.namesTokens(requester("peek"), channel.memberFacts());

    assertThat(seen.get().displayHost()).isEmpty();
    assertThat(Set.of(seen.get().nickname())).isNotEmpty();
  }

  /** Runs {@code reply} on another thread while the mutex is held; its state once it settles. */
  private Thread.State stateWhileTheMutexIsHeld(Runnable reply) throws InterruptedException {
    java.util.concurrent.atomic.AtomicReference<Thread> worker =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<Thread.State> observed =
        new java.util.concurrent.atomic.AtomicReference<>();
    extensions
        .capabilityControl()
        .runUnderControl(
            () -> {
              Thread thread = Thread.ofPlatform().start(reply);
              worker.set(thread);
              long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
              while (System.nanoTime() < deadline
                  && thread.getState() != Thread.State.BLOCKED
                  && thread.getState() != Thread.State.TERMINATED) {
                Thread.onSpinWait();
              }
              observed.set(thread.getState());
            });
    worker.get().join();
    return observed.get();
  }

  @Test
  void namesAndWhoCaptureTheSnapshotAndTheRequestersNegotiationTogether() throws Exception {
    populated();
    extensions.register(new AllStatuses());
    extensions.enable("all-statuses");
    ClientSession requester = requester("all-statuses");
    MemberFacts both =
        channel.memberFacts().stream()
            .filter(fact -> "both".equals(fact.member().nickname()))
            .findFirst()
            .orElseThrow();

    // A coherent capture waits for the mutex a publication holds; two separate reads would not.
    assertThat(stateWhileTheMutexIsHeld(() -> replies.namesTokens(requester, List.of(both))))
        .isEqualTo(Thread.State.BLOCKED);
    assertThat(stateWhileTheMutexIsHeld(() -> replies.whoStatus(requester, both)))
        .isEqualTo(Thread.State.BLOCKED);
    assertThat(replies.whoStatus(requester, both)).isEqualTo("@+");
  }
}
