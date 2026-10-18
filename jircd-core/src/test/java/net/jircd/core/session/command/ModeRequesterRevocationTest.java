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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.BanEntry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The requester's operator status is re-checked under the channel monitor for every change, so a
 * requester who is de-opped or departs after the whole-command check changes nothing and gets 482.
 */
class ModeRequesterRevocationTest {

  private enum Revocation {
    DEOP,
    PART
  }

  private final ChannelRegistry registry = new ChannelRegistry();
  private final NicknameRegistry nicknames = new NicknameRegistry();
  private final ClientSession requester = session("requester");
  private final ClientSession target = session("target");
  private Channel channel;

  private void setUpChannel() {
    channel = registry.join("#c", requester, ignored -> true).orElseThrow().channel();
    registry.join("#c", target, ignored -> true);
    nicknames.claim("target", target);
  }

  @ParameterizedTest
  @ValueSource(strings = {"+m", "+k key", "+l 5", "+b bad!*@*", "+o target", "+v target"})
  void requesterDeoppedBeforeTheChangeAppliesNothing(String change) throws Exception {
    String output = runWithRevocation(change, Revocation.DEOP, 1);
    assertRefused(output);
    assertThat(channel.operators()).doesNotContain(requester);
    assertUnchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"+m", "+k key", "+l 5", "+b bad!*@*", "+o target", "+v target"})
  void requesterWhoPartedBeforeTheChangeAppliesNothing(String change) throws Exception {
    String output = runWithRevocation(change, Revocation.PART, 1);
    assertRefused(output);
    assertThat(channel.members()).doesNotContain(requester);
    assertUnchanged();
  }

  @ParameterizedTest
  @ValueSource(strings = {"-m", "-k key", "-l", "-b bad!*@*", "-o target", "-v target"})
  void unsetsAreRefusedToo(String change) throws Exception {
    setUpChannel();
    channel.setMode(CoreChannelModes.MODERATED);
    channel.setKey("key");
    channel.setMemberLimit(5);
    BanEntry ban = new BanEntry("bad!*@*", "requester", Instant.now());
    channel.addBan(ban);
    channel.grantOperator(target);
    channel.grantVoice(target);
    String output = runHandler(change, Revocation.DEOP, 1);
    assertRefused(output);
    assertThat(channel.activeModes()).containsExactly(CoreChannelModes.MODERATED);
    assertThat(channel.key()).isEqualTo("key");
    assertThat(channel.memberLimit()).isEqualTo(5);
    assertThat(channel.bans()).containsExactly(ban);
    assertThat(channel.operators()).contains(target);
    assertThat(channel.voiced()).contains(target);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DEOP", "PART"})
  void changesAfterTheFirstRefusalAreNotApplied(String how) throws Exception {
    // The requester loses operator status before the second change: the first change keeps its
    // echo, the second is refused, and the third is never attempted.
    setUpChannel();
    String output = runHandler("+mtn", Revocation.valueOf(how), 2);
    assertThat(channel.activeModes())
        .contains(CoreChannelModes.MODERATED)
        .doesNotContain(CoreChannelModes.TOPIC_LOCK, CoreChannelModes.MEMBERS_ONLY);
    assertThat(output).contains(" 482 requester #c :You're not channel operator");
    assertThat(output).doesNotContain("+mt");
    if (how.equals("DEOP")) {
      // A departed requester is no longer a recipient of the echo; a de-opped one still is.
      assertThat(output).contains(" MODE #c +m");
    }
  }

  private String runWithRevocation(String change, Revocation how, int onCall) throws Exception {
    setUpChannel();
    return runHandler(change, how, onCall);
  }

  /** Runs MODE with the requester revoked just before mutation number {@code onCall}. */
  private String runHandler(String change, Revocation how, int onCall) throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch checked = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    ModeCommandHandler handler =
        new ModeCommandHandler(
            registry,
            nicknames,
            new ExtensionRegistry(),
            () -> "server",
            () -> 20,
            () -> {
              if (calls.incrementAndGet() == onCall) {
                checked.countDown();
                await(resume);
              }
            });
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(requester, out, TagRenderer.NONE);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      requester.attachWriter(writer);
      var pending =
          executor.submit(
              () -> {
                handler.handle(requester, MessageParser.parse("MODE #c " + change));
                return null;
              });
      try {
        await(checked);
        switch (how) {
          case DEOP -> channel.revokeOperator(requester);
          case PART -> registry.part(channel, requester).orElseThrow();
        }
      } finally {
        resume.countDown();
      }
      pending.get(5, TimeUnit.SECONDS);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private void assertRefused(String output) {
    assertThat(output)
        .contains(" 482 requester #c :You're not channel operator")
        .doesNotContain(" MODE #c ");
  }

  private void assertUnchanged() {
    assertThat(channel.activeModes()).isEmpty();
    assertThat(channel.key()).isNull();
    assertThat(channel.memberLimit()).isZero();
    assertThat(channel.bans()).isEmpty();
    assertThat(channel.voiced()).doesNotContain(target);
    assertThat(channel.operators()).doesNotContain(target);
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
