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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.Test;

/**
 * Two disconnect triggers can race for the same session (a client-sent {@code QUIT} racing an
 * abrupt TCP-level close of the same socket, surfaced by a flaky CI failure recording three {@code
 * WhowasEntry} values for only two disconnects) — {@link DisconnectCleanup#cleanup} MUST run its
 * full body exactly once per session, no matter how many times it's called.
 */
class DisconnectCleanupTest {

  private ClientSession registeredSession() {
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    session.setNickname("erin");
    session.setIdent("erin");
    session.setRealname("erin");
    return session;
  }

  @Test
  void secondCleanupCallForTheSameSessionIsANoOp() {
    WhowasHistory whowasHistory = new WhowasHistory(() -> 100);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(), new ChannelRegistry(), whowasHistory, new ExtensionRegistry());
    ClientSession session = registeredSession();

    cleanup.cleanup(session, "first trigger");
    cleanup.cleanup(session, "second, racing trigger");

    assertThat(whowasHistory.mostRecentNFor("erin", 0)).hasSize(1);
  }

  @Test
  void firstCleanupCallClaimsTheClosingTransition() {
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new ExtensionRegistry());
    ClientSession session = registeredSession();

    cleanup.cleanup(session, "reason");

    assertThat(session.lifecycle().isClosing()).isTrue();
  }

  /**
   * The Step 1 join-between-removals interleaving is impossible with leaveAll's registry lock. An
   * admitted join serializes before cleanup and that joiner still receives the QUIT.
   */
  @Test
  void peerAdmittedBeforeCleanupReceivesQuit() throws Exception {
    ClientSession quitter = registeredSession();
    ClientSession newcomer =
        new ClientSession("newcomer", "host.example", RateLimitBucket.withDefaults());
    newcomer.setNickname("newcomer");
    ChannelRegistry registry = new ChannelRegistry();
    registry.join("#one", quitter, ignored -> true);
    registry.join("#two", quitter, ignored -> true);
    CountDownLatch claimed = new CountDownLatch(1);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            registry,
            new WhowasHistory(() -> 100),
            new ExtensionRegistry(),
            claimed::countDown);
    CountDownLatch admitting = new CountDownLatch(1);
    CountDownLatch releaseAdmission = new CountDownLatch(1);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(newcomer, out, TagRenderer.NONE);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      newcomer.attachWriter(writer);
      var joining =
          executor.submit(
              () ->
                  registry.join(
                      "#two",
                      newcomer,
                      channel -> {
                        admitting.countDown();
                        await(releaseAdmission);
                        return true;
                      }));
      var leaving =
          executor.submit(
              () -> {
                await(admitting);
                cleanup.cleanup(quitter, "leaving");
              });
      try {
        // Cleanup has claimed CLOSING and entered its body, while the admitted join still holds the
        // registry: the quitter keeps its memberships until the admission is released.
        await(claimed);
        assertThat(quitter.lifecycle().isClosing()).isTrue();
        assertThat(quitter.channelMemberships()).hasSize(2);
        assertThat(leaving.isDone()).isFalse();
      } finally {
        releaseAdmission.countDown();
      }
      joining.get(5, TimeUnit.SECONDS);
      leaving.get(5, TimeUnit.SECONDS);
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo(":erin!erin@host.example QUIT leaving\r\n");
    assertThat(quitter.channelMemberships()).isEmpty();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void cleanupLeavesThreeChannelsNotifiesEachNeighborOnceAndIsIdempotent() {
    ClientSession quitter = registeredSession();
    ClientSession alice =
        new ClientSession("alice", "host.example", RateLimitBucket.withDefaults());
    alice.setNickname("alice");
    ClientSession bob = new ClientSession("bob", "host.example", RateLimitBucket.withDefaults());
    bob.setNickname("bob");
    ChannelRegistry registry = new ChannelRegistry();
    for (String name : List.of("#shared", "#also-shared", "#solo")) {
      registry.join(name, quitter, ignored -> true);
    }
    for (String name : List.of("#shared", "#also-shared")) {
      registry.join(name, alice, ignored -> true);
      registry.join(name, bob, ignored -> true);
    }
    List<Channel> channels = List.copyOf(quitter.channelMemberships());
    channels.forEach(channel -> channel.grantVoice(quitter));
    NicknameRegistry nicknames = new NicknameRegistry();
    assertThat(nicknames.claim("erin", quitter)).isTrue();
    WhowasHistory history = new WhowasHistory(() -> 100);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(nicknames, registry, history, new ExtensionRegistry());
    ByteArrayOutputStream aliceOut = new ByteArrayOutputStream();
    ByteArrayOutputStream bobOut = new ByteArrayOutputStream();
    try (SessionWriter aliceWriter = new SessionWriter(alice, aliceOut, TagRenderer.NONE);
        SessionWriter bobWriter = new SessionWriter(bob, bobOut, TagRenderer.NONE)) {
      alice.attachWriter(aliceWriter);
      bob.attachWriter(bobWriter);
      cleanup.cleanup(quitter, "finished");
      assertThat(quitter.channelMemberships()).isEmpty();
      assertThat(registry.lookup("#solo")).isEmpty();
      assertThat(registry.all()).hasSize(2);
      for (Channel channel : channels) {
        assertThat(channel.members()).doesNotContain(quitter);
        assertThat(channel.operators()).doesNotContain(quitter);
        assertThat(channel.voiced()).doesNotContain(quitter);
      }
      cleanup.cleanup(quitter, "duplicate");
    }
    assertThat(aliceOut.toString(StandardCharsets.UTF_8))
        .isEqualTo(":erin!erin@host.example QUIT finished\r\n");
    assertThat(bobOut.toString(StandardCharsets.UTF_8))
        .isEqualTo(":erin!erin@host.example QUIT finished\r\n");
    assertThat(history.mostRecentNFor("erin", 0)).hasSize(1);
    assertThat(nicknames.claim("erin", alice)).isTrue();
  }
}
