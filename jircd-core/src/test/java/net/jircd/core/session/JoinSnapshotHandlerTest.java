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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/** JOIN handler output for interleavings forced at the start of the JOIN snapshot. */
class JoinSnapshotHandlerTest {

  @Test
  void joinerKickedDuringTheSnapshotStillReceivesItsOwnJoin() throws Exception {
    PausedSnapshot pause = new PausedSnapshot();
    ChannelRegistry registry = new ChannelRegistry(pause);
    ClientSession operator = session("operator");
    Channel channel = registry.join("#c", operator, ignored -> true).orElseThrow().channel();
    ClientSession joiner = session("joiner");
    ExtensionRegistry extensions = new ExtensionRegistry();
    JoinCommandHandler handler =
        new JoinCommandHandler(registry, extensions, () -> "srv", () -> 50);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(joiner, out, TagRenderer.NONE);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      joiner.attachWriter(writer);
      pause.arm();
      var join = executor.submit(() -> handle(handler, joiner, "JOIN #c"));
      try {
        pause.awaitPaused();
        assertThat(registry.kick(channel, operator, "joiner"))
            .isInstanceOf(KickOutcome.Kicked.class);
      } finally {
        pause.release();
      }
      join.get(5, TimeUnit.SECONDS);
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(":joiner!joiner@host.example JOIN #c\r\n");
  }

  @Test
  void joinLineUsesTheIdentityPresentedAfterTheJoin() throws Exception {
    PausedSnapshot pause = new PausedSnapshot();
    ChannelRegistry registry = new ChannelRegistry(pause);
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new FixedCloak());
    ClientSession joiner = session("joiner");
    JoinCommandHandler handler =
        new JoinCommandHandler(registry, extensions, () -> "srv", () -> 50);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(joiner, out, TagRenderer.NONE);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      joiner.attachWriter(writer);
      pause.arm();
      var join = executor.submit(() -> handle(handler, joiner, "JOIN #c"));
      try {
        pause.awaitPaused();
        // An administrator enables cloaking while the JOIN is past its membership monitors.
        extensions.enable("fixed-cloak");
      } finally {
        pause.release();
      }
      join.get(5, TimeUnit.SECONDS);
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(":joiner!joiner@cloaked.example JOIN #c\r\n")
        .doesNotContain("joiner@host.example");
  }

  private static Void handle(JoinCommandHandler handler, ClientSession session, String line)
      throws Exception {
    handler.handle(session, MessageParser.parse(line));
    return null;
  }

  private static ClientSession session(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent(nick);
    return session;
  }

  /** Pauses the next JOIN snapshot after {@link #arm()} until {@link #release()}. */
  private static final class PausedSnapshot implements Consumer<Channel> {
    private final AtomicBoolean armed = new AtomicBoolean();
    private final CountDownLatch paused = new CountDownLatch(1);
    private final CountDownLatch resume = new CountDownLatch(1);

    void arm() {
      armed.set(true);
    }

    void awaitPaused() throws InterruptedException {
      assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
    }

    void release() {
      resume.countDown();
    }

    @Override
    public void accept(Channel channel) {
      if (armed.compareAndSet(true, false)) {
        paused.countDown();
        try {
          assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
    }
  }

  private static final class FixedCloak implements ServerExtension, HostnameDisplayExtension {
    @Override
    public String id() {
      return "fixed-cloak";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public void start(ServerContext context) {}

    @Override
    public void stop() {}

    @Override
    public String display(String hostname) {
      return "cloaked.example";
    }
  }
}
