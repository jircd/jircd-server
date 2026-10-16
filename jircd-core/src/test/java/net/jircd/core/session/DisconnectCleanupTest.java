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
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

  @Test
  void peerJoiningBeforeRemovalReceivesQuitEvenAfterAnEarlierChannelWasRemoved() throws Exception {
    ClientSession quitter = registeredSession();
    ClientSession newcomer =
        new ClientSession("newcomer", "host.example", RateLimitBucket.withDefaults());
    newcomer.setNickname("newcomer");
    ChannelRegistry registry = new ChannelRegistry();
    registry.join("#one", quitter, ignored -> true);
    registry.join("#two", quitter, ignored -> true);
    List<Channel> removalOrder = List.copyOf(Set.copyOf(quitter.channelMemberships()));
    Channel first = removalOrder.getFirst();
    Channel second = removalOrder.getLast();
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            registry,
            new WhowasHistory(() -> 100),
            new ExtensionRegistry());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(newcomer, out, TagRenderer.NONE);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      newcomer.attachWriter(writer);
      Future<?> pending;
      synchronized (registry) {
        pending = executor.submit(() -> cleanup.cleanup(quitter, "leaving"));
        // Cleanup removes the first membership, then blocks at removeIfEmpty on our registry
        // monitor. The second membership cannot be removed until we release that monitor.
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (first.members().contains(quitter) && System.nanoTime() < deadline) {
          Thread.onSpinWait();
        }
        assertThat(first.members()).doesNotContain(quitter);
        assertThat(second.members()).contains(quitter);
        registry.join(second.name(), newcomer, ignored -> true);
        assertThat(second.memberListing()).contains("@erin", "newcomer");
      }
      pending.get(5, TimeUnit.SECONDS);
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo(":erin!erin@host.example QUIT leaving\r\n");
  }
}
