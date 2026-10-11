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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChannelRegistryTest {

  @Test
  void joiningDuringLastMemberRemovalKeepsOneRegisteredChannel() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    Channel original = registry.getOrCreate("#lobby");
    ClientSession departing = newSession("departing");
    original.addMember(departing);
    departing.channelMemberships().add(original);
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
        original.removeMember(departing);
        departing.channelMemberships().remove(original);
        registry.removeIfEmpty(original);
      } finally {
        resumeJoin.countDown();
      }
      Channel joined = pendingJoin.get(5, TimeUnit.SECONDS);
      ClientSession laterJoiner = newSession("later");
      Channel next = registry.join("#LOBBY", laterJoiner, ignored -> true).orElseThrow();

      assertThat(registry.lookup("#lobby")).containsSame(joined);
      assertThat(next).isSameAs(joined);
      assertThat(next.members()).containsExactlyInAnyOrder(joining, laterJoiner);
      assertThat(joining.channelMemberships()).containsExactly(joined);
    }
  }

  @Test
  void lastMemberRemovalDuringAdmissionDoesNotDetachTheJoinedChannel() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession departing = newSession("departing");
    Channel original = registry.join("#lobby", departing, ignored -> true).orElseThrow();
    ClientSession joining = newSession("joining");
    CountDownLatch admitting = new CountDownLatch(1);
    CountDownLatch memberRemoved = new CountDownLatch(1);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var removal =
          executor.submit(
              () -> {
                if (!admitting.await(5, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting for admission");
                }
                original.removeMember(departing);
                departing.channelMemberships().remove(original);
                memberRemoved.countDown();
                registry.removeIfEmpty(original);
                return null;
              });
      Channel joined =
          registry
              .join(
                  "#lobby",
                  joining,
                  channel -> {
                    admitting.countDown();
                    try {
                      assertThat(memberRemoved.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IllegalStateException(e);
                    }
                    return true;
                  })
              .orElseThrow();
      removal.get(5, TimeUnit.SECONDS);

      assertThat(joined).isSameAs(original);
      assertThat(registry.lookup("#lobby")).containsSame(joined);
      assertThat(joined.members()).containsExactly(joining);
    }
  }

  @Test
  void staleRemovalCannotRemoveAReplacementChannel() {
    ChannelRegistry registry = new ChannelRegistry();
    Channel old = registry.getOrCreate("#lobby");
    registry.removeIfEmpty(old);
    Channel replacement = registry.getOrCreate("#LOBBY");

    registry.removeIfEmpty(old);

    assertThat(registry.lookup("#lobby")).containsSame(replacement);
  }

  @Test
  void rejectedAdmissionDoesNotInsertMembership() {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession session = newSession("rejected");

    assertThat(registry.join("#lobby", session, ignored -> false)).isEmpty();

    assertThat(session.channelMemberships()).isEmpty();
    assertThat(registry.lookup("#lobby").orElseThrow().members()).isEmpty();
  }

  private ClientSession newSession(String id) {
    return new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
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
    Channel channel = registry.getOrCreate("#lobby");
    ClientSession first = newSession("a");
    ClientSession second = newSession("b");

    boolean firstGrantedOp = channel.addMember(first);
    boolean secondGrantedOp = channel.addMember(second);

    assertThat(firstGrantedOp).isTrue();
    assertThat(secondGrantedOp).isFalse();
    assertThat(channel.operators()).containsExactly(first);
    assertThat(channel.members()).containsExactlyInAnyOrder(first, second);
  }
}
