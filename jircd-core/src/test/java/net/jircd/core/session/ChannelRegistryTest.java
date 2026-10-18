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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.command.KickCommandHandler;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

class ChannelRegistryTest {

  @Test
  void joiningDuringLastMemberRemovalKeepsOneRegisteredChannel() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession departing = newSession("departing");
    Channel original = registry.join("#lobby", departing, ignored -> true).orElseThrow().channel();
    ClientSession joining = newSession("joining");
    CountDownLatch retrieved = new CountDownLatch(1);
    CountDownLatch resumeJoin = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pendingJoin =
          executor.submit(
              () -> {
                retrieved.countDown();
                if (!resumeJoin.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting to resume JOIN");
                }
                return registry.join("#lobby", joining, ignored -> true).orElseThrow();
              });
      try {
        assertThat(retrieved.await(5, TimeUnit.SECONDS)).isTrue();
        registry.part(original, departing);
      } finally {
        resumeJoin.countDown();
      }
      Channel joined = pendingJoin.get(5, TimeUnit.SECONDS).channel();
      ClientSession laterJoiner = newSession("later");
      Channel next = registry.join("#LOBBY", laterJoiner, ignored -> true).orElseThrow().channel();

      assertThat(registry.lookup("#lobby")).containsSame(joined);
      assertThat(next).isSameAs(joined);
      assertThat(next.members()).containsExactlyInAnyOrder(joining, laterJoiner);
      assertThat(joining.channelMemberships()).containsExactly(joined);
    }
  }

  /**
   * Removal during admission now waits for the registry monitor; the admitted join stays
   * registered.
   */
  @Test
  void lastMemberRemovalDuringAdmissionDoesNotDetachTheJoinedChannel() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession departing = newSession("departing");
    Channel original = registry.join("#lobby", departing, ignored -> true).orElseThrow().channel();
    ClientSession joining = newSession("joining");
    CountDownLatch admitting = new CountDownLatch(1);
    CountDownLatch attemptingRemoval = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var removal =
          executor.submit(
              () -> {
                await(admitting);
                attemptingRemoval.countDown();
                return registry.part(original, departing);
              });
      Channel joined =
          registry
              .join(
                  "#lobby",
                  joining,
                  channel -> {
                    admitting.countDown();
                    await(attemptingRemoval);
                    return true;
                  })
              .orElseThrow()
              .channel();
      assertThat(removal.get(5, TimeUnit.SECONDS)).isPresent();
      assertThat(joined).isSameAs(original);
      assertThat(registry.lookup("#lobby")).containsSame(joined);
      assertThat(joined.members()).containsExactly(joining);
    }
  }

  @Test
  void staleRemovalCannotRemoveAReplacementChannel() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession member = newSession("member");
    Channel old = registry.join("#lobby", member, ignored -> true).orElseThrow().channel();
    registry.part(old, member);
    Channel replacement = registry.join("#LOBBY", member, ignored -> true).orElseThrow().channel();

    registry.removeIfEmpty(old);

    assertThat(registry.lookup("#lobby")).containsSame(replacement);
  }

  @Test
  void rejectedAdmissionDoesNotInsertMembership() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession session = newSession("rejected");

    assertThat(registry.join("#lobby", session, ignored -> false)).isEmpty();

    assertThat(session.channelMemberships()).isEmpty();
    assertThat(registry.lookup("#lobby")).isEmpty();
  }

  private ClientSession newSession(String id) {
    ClientSession session = new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(id);
    return session;
  }

  @Test
  void getOrCreateResolvesToOneChannelRegardlessOfCasing() {
    ChannelRegistry registry = new ChannelRegistry();
    Channel first = registry.getOrCreate("#Foo");
    Channel second = registry.getOrCreate("#foo");
    assertThat(second).isSameAs(first);
    assertThat(first.name()).isEqualTo("#Foo");
  }

  @Test
  void lookupByAnyCasingFindsTheChannel() {
    ChannelRegistry registry = new ChannelRegistry();
    registry.getOrCreate("#Lobby");
    assertThat(registry.lookup("#lobby")).isPresent();
    assertThat(registry.lookup("#LOBBY")).isPresent();
  }

  @Test
  void firstJoinerBecomesOperator() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession first = newSession("a");
    ClientSession second = newSession("b");

    JoinResult firstJoin = registry.join("#lobby", first, ignored -> true).orElseThrow();
    Channel channel = firstJoin.channel();
    boolean firstGrantedOp = firstJoin.grantedOperator();
    boolean secondGrantedOp =
        registry.join("#lobby", second, ignored -> true).orElseThrow().grantedOperator();

    assertThat(firstGrantedOp).isTrue();
    assertThat(secondGrantedOp).isFalse();
    assertThat(channel.operators()).containsExactly(first);
    assertThat(channel.members()).containsExactlyInAnyOrder(first, second);
  }

  @Test
  void twoKickHandlersForOneTargetBroadcastOnlyOneDeparture() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession operator = newSession("operator");
    operator.setNickname("operator");
    operator.setIdent("operator");
    ClientSession target = newSession("target");
    target.setNickname("target");
    registry.join("#c", operator, ignored -> true);
    registry.join("#c", target, ignored -> true);
    CountDownLatch deliveringFirst = new CountDownLatch(1);
    CountDownLatch resumeFirst = new CountDownLatch(1);
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new PausingHostname(deliveringFirst, resumeFirst));
    extensions.enable("pausing-hostname");
    KickCommandHandler handler = new KickCommandHandler(registry, extensions, () -> "server");
    ByteArrayOutputStream notices = new ByteArrayOutputStream();
    ByteArrayOutputStream replies = new ByteArrayOutputStream();
    try (SessionWriter targetWriter = new SessionWriter(target, notices, TagRenderer.NONE);
        SessionWriter operatorWriter = new SessionWriter(operator, replies, TagRenderer.NONE);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      target.attachWriter(targetWriter);
      operator.attachWriter(operatorWriter);
      var first =
          executor.submit(
              () -> {
                handler.handle(operator, MessageParser.parse("KICK #c target"));
                return null;
              });
      try {
        assertThat(deliveringFirst.await(5, TimeUnit.SECONDS)).isTrue();
        handler.handle(operator, MessageParser.parse("KICK #c target"));
      } finally {
        resumeFirst.countDown();
      }
      first.get(5, TimeUnit.SECONDS);
    }
    assertThat(
            notices
                .toString(StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.contains(" KICK #c target")))
        .hasSize(1);
    assertThat(replies.toString(StandardCharsets.UTF_8)).contains(" 441 operator target ");
  }

  @Test
  void joinCapturesRecipientsAndNamesAndSkipsAdmissionForAnExistingMember() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession alice = newSession("alice");
    ClientSession bob = newSession("bob");
    JoinResult first = registry.join("#c", alice, ignored -> true).orElseThrow();
    JoinResult second = registry.join("#C", bob, ignored -> true).orElseThrow();
    assertThat(first.grantedOperator()).isTrue();
    assertThat(first.alreadyMember()).isFalse();
    assertThat(first.recipients()).containsExactly(alice);
    assertThat(first.memberListing()).containsExactly("@alice");
    assertThat(second.channel()).isSameAs(first.channel());
    assertThat(second.grantedOperator()).isFalse();
    assertThat(second.recipients()).containsExactlyInAnyOrder(alice, bob);
    assertThat(second.memberListing()).containsExactlyInAnyOrder("@alice", "bob");
    JoinResult repeated =
        registry
            .join(
                "#c",
                alice,
                channel -> {
                  throw new AssertionError("Repeated membership must skip admission");
                })
            .orElseThrow();
    assertThat(repeated.alreadyMember()).isTrue();
    assertThat(repeated.grantedOperator()).isFalse();
    assertThat(repeated.recipients()).containsExactlyInAnyOrder(alice, bob);
    assertThat(alice.channelMemberships()).containsExactly(first.channel());
    Set<ClientSession> joinRecipients = first.recipients();
    List<String> joinListing = first.memberListing();
    assertThatThrownBy(joinRecipients::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(joinListing::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void partCapturesPreRemovalRecipientsClearsPrivilegesAndDropsTheLastMember() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession alice = newSession("alice");
    ClientSession bob = newSession("bob");
    Channel channel = registry.join("#c", alice, ignored -> true).orElseThrow().channel();
    registry.join("#c", bob, ignored -> true);
    channel.grantVoice(alice);
    assertThat(registry.part(channel, newSession("outsider"))).isEmpty();
    Departure departure = registry.part(channel, alice).orElseThrow();
    assertThat(departure.channel()).isSameAs(channel);
    assertThat(departure.departed()).isSameAs(alice);
    assertThat(departure.recipients()).containsExactlyInAnyOrder(alice, bob);
    assertThat(channel.members()).containsExactly(bob);
    assertThat(channel.operators()).doesNotContain(alice);
    assertThat(channel.voiced()).doesNotContain(alice);
    assertThat(alice.channelMemberships()).isEmpty();
    assertThat(registry.part(channel, alice)).isEmpty();
    registry.part(channel, bob).orElseThrow();
    assertThat(registry.lookup("#c")).isEmpty();
    assertThat(departure.recipients()).containsExactlyInAnyOrder(alice, bob);
    Set<ClientSession> departureRecipients = departure.recipients();
    assertThatThrownBy(departureRecipients::clear)
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void kickChecksOperatorThenFoldedTargetAndCapturesPreRemovalRecipients() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession operator = newSession("operator");
    ClientSession target = newSession("a[b");
    Channel channel = registry.join("#c", operator, ignored -> true).orElseThrow().channel();
    registry.join("#c", target, ignored -> true);
    channel.grantVoice(target);
    assertThat(registry.kick(channel, target, "missing"))
        .isInstanceOf(KickOutcome.NotOperator.class);
    assertThat(registry.kick(channel, operator, "missing"))
        .isInstanceOf(KickOutcome.NoSuchMember.class);
    KickOutcome.Kicked result = (KickOutcome.Kicked) registry.kick(channel, operator, "A{B");
    assertThat(result.departure().departed()).isSameAs(target);
    assertThat(result.departure().recipients()).containsExactlyInAnyOrder(operator, target);
    assertThat(target.channelMemberships()).isEmpty();
    assertThat(channel.voiced()).doesNotContain(target);
    KickOutcome.Kicked self = (KickOutcome.Kicked) registry.kick(channel, operator, "operator");
    assertThat(self.departure().recipients()).containsExactly(operator);
    assertThat(registry.all()).isEmpty();
  }

  @Test
  void leaveAllDeduplicatesNeighborsAndClearsEveryMembership() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession quitter = newSession("quitter");
    ClientSession alice = newSession("alice");
    ClientSession bob = newSession("bob");
    for (String name : List.of("#a", "#b", "#solo")) {
      registry.join(name, quitter, ignored -> true);
    }
    registry.join("#a", alice, ignored -> true);
    registry.join("#b", alice, ignored -> true);
    registry.join("#b", bob, ignored -> true);
    Set<ClientSession> neighbors = registry.leaveAll(quitter);
    assertThat(neighbors).hasSize(2).containsExactlyInAnyOrder(alice, bob).doesNotContain(quitter);
    assertThat(quitter.channelMemberships()).isEmpty();
    assertThat(registry.lookup("#solo")).isEmpty();
    assertThat(registry.leaveAll(quitter)).isEmpty();
    registry.leaveAll(alice);
    assertThat(neighbors).containsExactlyInAnyOrder(alice, bob);
    assertThatThrownBy(neighbors::clear).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void membershipAccessorsAreUnmodifiableLiveViews() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession member = newSession("member");
    Channel channel = registry.join("#c", member, ignored -> true).orElseThrow().channel();
    Set<ClientSession> members = channel.members();
    Set<Channel> memberships = member.channelMemberships();
    assertThat(channel.members()).isSameAs(members);
    assertThat(member.channelMemberships()).isSameAs(memberships);
    assertThatThrownBy(members::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(memberships::clear).isInstanceOf(UnsupportedOperationException.class);
    registry.part(channel, member);
    assertThat(members).isEmpty();
    assertThat(memberships).isEmpty();
  }

  @Test
  void rejectedAdmissionKeepsAnExistingPopulatedChannel() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession existing = newSession("existing");
    Channel channel = registry.join("#c", existing, ignored -> true).orElseThrow().channel();
    ClientSession rejected = newSession("rejected");
    assertThat(registry.join("#c", rejected, ignored -> false)).isEmpty();
    assertThat(registry.lookup("#c")).containsSame(channel);
    assertThat(channel.members()).containsExactly(existing);
    assertThat(rejected.channelMemberships()).isEmpty();
  }

  @Test
  void twoConcurrentKicksHaveExactlyOneSuccessfulDeparture() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession operator = newSession("operator");
    ClientSession target = newSession("target");
    Channel channel = registry.join("#c", operator, ignored -> true).orElseThrow().channel();
    registry.join("#c", target, ignored -> true);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return registry.kick(channel, operator, "target");
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return registry.kick(channel, operator, "TARGET");
              });
      try {
        await(ready);
      } finally {
        start.countDown();
      }
      List<KickOutcome> results =
          List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
      assertThat(results.stream().filter(KickOutcome.Kicked.class::isInstance)).hasSize(1);
      assertThat(results.stream().filter(KickOutcome.NoSuchMember.class::isInstance)).hasSize(1);
    }
  }

  @Test
  void concurrentPartAndKickHaveExactlyOneDeparture() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession operator = newSession("operator");
    ClientSession target = newSession("target");
    Channel channel = registry.join("#c", operator, ignored -> true).orElseThrow().channel();
    registry.join("#c", target, ignored -> true);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var part =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return registry.part(channel, target);
              });
      var kick =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                KickOutcome result = registry.kick(channel, operator, "target");
                return result instanceof KickOutcome.Kicked kicked
                    ? Optional.of(kicked.departure())
                    : Optional.<Departure>empty();
              });
      try {
        await(ready);
      } finally {
        start.countDown();
      }
      List<Optional<Departure>> results =
          List.of(part.get(5, TimeUnit.SECONDS), kick.get(5, TimeUnit.SECONDS));
      assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
      assertThat(target.channelMemberships()).isEmpty();
      assertThat(channel.members()).containsExactly(operator);
    }
  }

  @Test
  void disconnectRacingAdmissionLeavesNoMembershipBehind() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession session = newSession("closing");
    CountDownLatch admitting = new CountDownLatch(1);
    CountDownLatch closed = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var join =
          executor.submit(
              () ->
                  registry.join(
                      "#c",
                      session,
                      channel -> {
                        admitting.countDown();
                        await(closed);
                        return true;
                      }));
      var leave =
          executor.submit(
              () -> {
                await(admitting);
                try {
                  session.lifecycle().closeIfNotAlreadyClosing();
                } finally {
                  closed.countDown();
                }
                return registry.leaveAll(session);
              });
      join.get(5, TimeUnit.SECONDS);
      leave.get(5, TimeUnit.SECONDS);
    }
    assertThat(session.channelMemberships()).isEmpty();
    assertThat(registry.all()).isEmpty();
  }

  @Test
  void closingSessionCannotJoinOrRunAdmission() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession session = newSession("closing");
    session.lifecycle().closeIfNotAlreadyClosing();
    assertThat(
            registry.join(
                "#new",
                session,
                channel -> {
                  throw new AssertionError("admission ran");
                }))
        .isEmpty();
    assertThat(registry.lookup("#new")).isEmpty();
  }

  /**
   * A JOIN's O(members) snapshot runs after the registry and channel monitors are released, so a
   * large channel's join never blocks lookups or transitions on other channels.
   */
  @Test
  void joinSnapshotDoesNotBlockLookupsOrTransitionsOnOtherChannels() throws Exception {
    CountDownLatch inSnapshot = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    AtomicBoolean hookHeldMonitor = new AtomicBoolean();
    AtomicReference<ChannelRegistry> self = new AtomicReference<>();
    ChannelRegistry registry =
        new ChannelRegistry(
            channel -> {
              if ("#storm".equals(channel.name()) && first.compareAndSet(true, false)) {
                hookHeldMonitor.set(Thread.holdsLock(self.get()) || Thread.holdsLock(channel));
                inSnapshot.countDown();
                await(release);
              }
            });
    self.set(registry);
    ClientSession quietMember = newSession("quiet");
    Channel quiet = registry.join("#quiet", quietMember, ignored -> true).orElseThrow().channel();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var stormJoin =
          executor.submit(() -> registry.join("#storm", newSession("joiner"), ignored -> true));
      try {
        assertThat(inSnapshot.await(5, TimeUnit.SECONDS)).isTrue();
        // One bounded budget for every operation, well inside the hook's own 5 s release wait.
        var progress =
            executor.submit(
                () -> {
                  assertThat(registry.lookup("#quiet")).containsSame(quiet);
                  ClientSession other = newSession("other");
                  assertThat(registry.join("#quiet", other, ignored -> true)).isPresent();
                  assertThat(registry.part(quiet, other)).isPresent();
                  // A second JOIN on the paused channel needs both monitors.
                  assertThat(registry.join("#storm", newSession("second"), ignored -> true))
                      .isPresent();
                  return null;
                });
        assertThat(progress).succeedsWithin(Duration.ofSeconds(2));
      } finally {
        release.countDown();
      }
      assertThat(stormJoin.get(5, TimeUnit.SECONDS)).isPresent();
    }
    assertThat(hookHeldMonitor).isFalse();
  }

  /** The joiner is always a recipient of its own JOIN, even if it is kicked before the snapshot. */
  @Test
  void joinerKickedBeforeTheSnapshotIsStillARecipient() throws Exception {
    CountDownLatch inSnapshot = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean first = new AtomicBoolean(true);
    ChannelRegistry registry =
        new ChannelRegistry(
            channel -> {
              if (first.compareAndSet(true, false)) {
                inSnapshot.countDown();
                await(release);
              }
            });
    ClientSession operator = newSession("operator");
    ClientSession joiner = newSession("joiner");
    // Joining without a hook pause: the first hook call is the joiner's.
    first.set(false);
    Channel channel = registry.join("#c", operator, ignored -> true).orElseThrow().channel();
    first.set(true);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var join = executor.submit(() -> registry.join("#c", joiner, ignored -> true));
      try {
        assertThat(inSnapshot.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.kick(channel, operator, "joiner"))
            .isInstanceOf(KickOutcome.Kicked.class);
      } finally {
        release.countDown();
      }
      JoinResult result = join.get(5, TimeUnit.SECONDS).orElseThrow();
      assertThat(result.recipients()).contains(joiner, operator);
      assertThat(result.memberListing()).containsExactly("@operator");
    }
  }

  /** NAMES listings read the concurrent member sets without taking the channel monitor. */
  @Test
  void memberListingDoesNotWaitForTheChannelMonitor() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    Channel channel =
        registry.join("#c", newSession("alice"), ignored -> true).orElseThrow().channel();
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var holder =
          executor.submit(
              () -> {
                synchronized (channel) {
                  holding.countDown();
                  await(release);
                }
                return null;
              });
      try {
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(channel::memberListing))
            .succeedsWithin(Duration.ofSeconds(2))
            .isEqualTo(List.of("@alice"));
      } finally {
        release.countDown();
      }
      holder.get(5, TimeUnit.SECONDS);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static final class PausingHostname implements ServerExtension, HostnameDisplayExtension {
    private final CountDownLatch entered;
    private final CountDownLatch resume;
    private final AtomicBoolean first = new AtomicBoolean(true);

    private PausingHostname(CountDownLatch entered, CountDownLatch resume) {
      this.entered = entered;
      this.resume = resume;
    }

    @Override
    public String id() {
      return "pausing-hostname";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public void start(ServerContext context) {
      // Stateless test extension: nothing to set up.
    }

    @Override
    public void stop() {
      // Stateless test extension: nothing to release.
    }

    @Override
    public String display(String hostname) {
      if (first.compareAndSet(true, false)) {
        entered.countDown();
        try {
          assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return hostname;
    }
  }
}
