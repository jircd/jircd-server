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

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MembershipStressTest {
  @Test
  void randomizedConcurrentTransitionsPreserveMembershipAndRegistryInvariants() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    List<ClientSession> sessions = new ArrayList<>();
    Set<Channel> observedChannels =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    for (int i = 0; i < 32; i++) {
      ClientSession session =
          new ClientSession("s" + i, "host.example", RateLimitBucket.withDefaults());
      session.setNickname("s" + i);
      sessions.add(session);
      for (int c = 0; c < 8; c++) {
        observedChannels.add(
            registry.join("#c" + c, session, ignored -> true).orElseThrow().channel());
      }
    }
    // Ensure voice is exercised even if the randomized grants race with departed members.
    registry.lookup("#c0").orElseThrow().voiced().add(sessions.getFirst());
    AtomicInteger successfulJoins = new AtomicInteger();
    AtomicInteger departures = new AtomicInteger();
    Random random = new Random(19);
    List<Operation> operations = new ArrayList<>();
    for (int i = 0; i < 2048; i++) {
      operations.add(
          new Operation(
              random.nextInt(5), random.nextInt(32), random.nextInt(32), "#c" + random.nextInt(8)));
    }
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> pending = new ArrayList<>();
      for (Operation operation : operations) {
        pending.add(
            executor.submit(
                () -> {
                  if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start barrier timed out");
                  }
                  ClientSession member = sessions.get(operation.member());
                  switch (operation.kind()) {
                    case 0 ->
                        registry
                            .join(operation.channel(), member, ignored -> true)
                            .ifPresent(
                                result -> {
                                  observedChannels.add(result.channel());
                                  assertRecipients(result.recipients(), sessions);
                                  if (!result.alreadyMember()) {
                                    assertThat(result.recipients()).contains(member);
                                    successfulJoins.incrementAndGet();
                                  }
                                });
                    case 1 ->
                        registry
                            .lookup(operation.channel())
                            .flatMap(channel -> registry.part(channel, member))
                            .ifPresent(
                                departure -> {
                                  assertDeparture(departure, sessions);
                                  departures.incrementAndGet();
                                });
                    case 2 ->
                        registry
                            .lookup(operation.channel())
                            .ifPresent(
                                channel -> {
                                  KickOutcome result =
                                      registry.kick(
                                          channel,
                                          member,
                                          sessions.get(operation.target()).nickname());
                                  if (result instanceof KickOutcome.Kicked kicked) {
                                    assertDeparture(kicked.departure(), sessions);
                                    departures.incrementAndGet();
                                  }
                                });
                    case 3 -> {
                      // Count under the same registry monitor as leaveAll; its production
                      // signature and the synchronization of the other operations stay intact.
                      synchronized (registry) {
                        int membershipsBefore = member.channelMemberships().size();
                        assertRecipients(registry.leaveAll(member), sessions);
                        departures.addAndGet(membershipsBefore);
                      }
                    }
                    case 4 ->
                        registry
                            .lookup(operation.channel())
                            .ifPresent(
                                channel -> {
                                  // Channel-only operation: never acquire the registry while
                                  // holding this
                                  // monitor. Departure cannot race between membership check and
                                  // voice grant.
                                  synchronized (channel) {
                                    if (channel.members().contains(member)) {
                                      channel.voiced().add(member);
                                    }
                                  }
                                });
                    default -> throw new AssertionError(operation);
                  }
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> result : pending) {
        result.get(10, TimeUnit.SECONDS);
      }
    }
    int finalMemberships =
        sessions.stream().mapToInt(session -> session.channelMemberships().size()).sum();
    assertThat(256 + successfulJoins.get())
        .as("initial memberships plus successful joins equal departures plus final memberships")
        .isEqualTo(departures.get() + finalMemberships);
    assertThat(registry.all()).allSatisfy(channel -> assertThat(channel.members()).isNotEmpty());
    for (Channel channel : observedChannels) {
      assertThat(channel.members()).containsAll(channel.operators()).containsAll(channel.voiced());
      for (ClientSession session : sessions) {
        assertThat(session.channelMemberships().stream().anyMatch(joined -> joined == channel))
            .isEqualTo(channel.members().contains(session));
      }
      if (!channel.isEmpty()) {
        assertThat(registry.lookup(channel.name())).containsSame(channel);
      }
    }
  }

  private static void assertDeparture(Departure departure, List<ClientSession> sessions) {
    assertThat(departure.recipients()).contains(departure.departed());
    assertRecipients(departure.recipients(), sessions);
  }

  private static void assertRecipients(
      Set<ClientSession> recipients, List<ClientSession> sessions) {
    assertThat(sessions).containsAll(recipients);
  }

  private record Operation(int kind, int member, int target, String channel) {}
}
