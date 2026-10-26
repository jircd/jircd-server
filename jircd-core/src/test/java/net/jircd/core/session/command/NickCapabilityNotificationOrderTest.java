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
package net.jircd.core.session.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.UserStatistics;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/**
 * A nickname change and its own {@code NICK} echo are one step under the administrative mutex, so a
 * concurrent capability publication addresses its {@code CAP DEL}/{@code NEW} to the nickname the
 * client knows at that point in its stream, never to one it has already been told it left.
 */
class NickCapabilityNotificationOrderTest {

  private static final class Offer extends AbstractCapabilityExtension {
    Offer() {
      super("alpha");
    }
  }

  private final HandlerHarness harness = new HandlerHarness();
  private final ExtensionRegistry registry = new ExtensionRegistry();
  private final Set<ClientSession> live = ConcurrentHashMap.newKeySet();

  private NickCommandHandler nick() {
    registry.attachContext(
        new ServerContext(
            null,
            null,
            registry,
            () -> "irc.example",
            null,
            null,
            null,
            registry.capabilityServices()));
    registry.attachLiveSessions(live);
    return new NickCommandHandler(
        harness.nicknames,
        () -> "irc.example",
        () -> 30,
        new RegistrationCompletion(
            () -> "irc.example", "test", Instant.now(), registry, new UserStatistics()),
        registry);
  }

  @Test
  void aNickChangeWaitsForAPublicationHoldingTheMutex() throws Exception {
    NickCommandHandler handler = nick();
    ClientSession alice = harness.register("alice");
    live.add(alice);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread owner =
        Thread.ofVirtual()
            .start(
                () ->
                    registry
                        .capabilityControl()
                        .runUnderControl(
                            () -> {
                              held.countDown();
                              try {
                                release.await(5, TimeUnit.SECONDS);
                              } catch (InterruptedException _) {
                                Thread.currentThread().interrupt();
                              }
                            }));
    assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(alice, out, TagRenderer.NONE)) {
      alice.attachWriter(writer);
      Thread change =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      handler.handle(alice, MessageParser.parse("NICK bob"));
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  });
      // Bounded: while a publication holds the mutex, the change has not happened yet.
      assertThat(change.join(java.time.Duration.ofMillis(200))).isFalse();
      assertThat(alice.nickname()).isEqualTo("alice");
      release.countDown();
      assertThat(change.join(java.time.Duration.ofSeconds(5))).isTrue();
    } finally {
      release.countDown();
      owner.join(5000);
    }
    assertThat(alice.nickname()).isEqualTo("bob");
    assertThat(out.toString(StandardCharsets.UTF_8)).contains(" NICK bob");
  }

  @Test
  void racingPublicationsAlwaysAddressTheNicknameTheClientCurrentlyKnows() throws Exception {
    NickCommandHandler handler = nick();
    registry.register(new Offer());
    registry.enable("alpha");
    ClientSession client = harness.register("n0");
    client.recordCapVersion(302);
    live.add(client);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(client, out, TagRenderer.NONE)) {
      client.attachWriter(writer);
      Thread toggler =
          Thread.ofVirtual()
              .start(
                  () -> {
                    for (int i = 0; i < 200; i++) {
                      registry.disable("alpha");
                      registry.enable("alpha");
                    }
                  });
      for (int i = 1; i <= 200; i++) {
        handler.handle(client, MessageParser.parse("NICK n" + i));
      }
      toggler.join(10_000);
    }
    String known = "n0";
    List<String> lines = List.of(out.toString(StandardCharsets.UTF_8).split("\r\n"));
    assertThat(lines).anyMatch(l -> l.contains(" CAP "));
    for (String line : lines) {
      if (line.contains(" NICK ")) {
        known = line.substring(line.lastIndexOf(' ') + 1);
      } else if (line.contains(" CAP ")) {
        assertThat(line).as("after NICK %s", known).contains(" CAP " + known + " ");
      }
    }
  }
}
