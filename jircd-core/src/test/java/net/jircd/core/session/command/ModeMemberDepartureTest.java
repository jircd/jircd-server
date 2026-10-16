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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModeMemberDepartureTest {
  @ParameterizedTest
  @ValueSource(strings = {"+o", "+v", "-o", "-v"})
  void departedResolvedTargetGets441WithoutAnEcho(String change) throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession operator = session("operator");
    ClientSession target = session("target");
    Channel channel = registry.join("#c", operator, ignored -> true).orElseThrow().channel();
    registry.join("#c", target, ignored -> true);
    NicknameRegistry nicknames = new NicknameRegistry();
    nicknames.claim("target", target);
    CountDownLatch resolved = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    ModeCommandHandler handler =
        new ModeCommandHandler(
            registry,
            nicknames,
            new ExtensionRegistry(),
            () -> "server",
            () -> 20,
            () -> {
              resolved.countDown();
              await(resume);
            });
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(operator, out, TagRenderer.NONE);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      operator.attachWriter(writer);
      var pending =
          executor.submit(
              () -> {
                handler.handle(operator, MessageParser.parse("MODE #c " + change + " target"));
                return null;
              });
      try {
        await(resolved);
        registry.part(channel, target).orElseThrow();
      } finally {
        resume.countDown();
      }
      pending.get(5, TimeUnit.SECONDS);
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(" 441 operator target :They aren't on that channel")
        .doesNotContain(" MODE #c ");
  }

  @ParameterizedTest
  @ValueSource(strings = {"+o", "+v", "-o", "-v"})
  void validMemberStillGetsAnEchoForAnIdempotentChange(String change) throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession operator = session("operator");
    ClientSession target = session("target");
    Channel channel = registry.join("#c", operator, ignored -> true).orElseThrow().channel();
    registry.join("#c", target, ignored -> true);
    if (change.startsWith("+")) {
      channel.grantOperator(target);
      channel.grantVoice(target);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(operator, out, TagRenderer.NONE)) {
      operator.attachWriter(writer);
      new ModeCommandHandler(
              registry, new NicknameRegistry(), new ExtensionRegistry(), () -> "server", () -> 20)
          .handle(operator, MessageParser.parse("MODE #c " + change + " target"));
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(" MODE #c " + change + " target")
        .doesNotContain(" 441 ");
  }

  private static ClientSession session(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent(nick);
    return session;
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
