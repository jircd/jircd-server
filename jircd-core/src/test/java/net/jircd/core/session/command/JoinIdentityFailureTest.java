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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.BanEntry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelModeChange;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A hostname-display failure only matters to a JOIN that actually needs the displayed identity. */
class JoinIdentityFailureTest {

  private final ChannelRegistry registry = new ChannelRegistry();
  private final CountingFailingHostname hostname = new CountingFailingHostname();
  private final ClientSession owner = session("owner");
  private final ClientSession joiner = session("joiner");
  private final ByteArrayOutputStream joinerOut = new ByteArrayOutputStream();
  private final SessionWriter joinerWriter = new SessionWriter(joiner, joinerOut, TagRenderer.NONE);
  private final Channel channel =
      registry.join("#c", owner, ignored -> true).orElseThrow().channel();
  private final JoinCommandHandler handler = handler();

  private JoinCommandHandler handler() {
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(hostname);
    extensions.enable("failing-hostname");
    joiner.attachWriter(joinerWriter);
    return new JoinCommandHandler(registry, extensions, () -> "server", () -> 50);
  }

  @AfterEach
  void closeWriter() {
    joinerWriter.close();
  }

  @Test
  void repeatedJoinNeverResolvesTheDisplayedIdentity() throws Exception {
    registry.join("#c", joiner, ignored -> true);

    handler.handle(joiner, MessageParser.parse("JOIN #c"));

    assertThat(hostname.calls.get()).isZero();
    assertThat(output()).isEmpty();
    assertThat(channel.members()).containsExactlyInAnyOrder(owner, joiner);
  }

  @Test
  void rejectionWithoutBansDoesNotNeedTheDisplayedIdentity() throws Exception {
    channel.setMode(CoreChannelModes.INVITE_ONLY);

    handler.handle(joiner, MessageParser.parse("JOIN #c"));

    assertThat(output()).contains(" 473 joiner #c :Cannot join channel (+i)");
    assertThat(channel.members()).containsExactly(owner);
  }

  @Test
  void failedIdentitySampleGrantsNoInviteExceptionAndReturns473() throws Exception {
    channel.setMode(CoreChannelModes.INVITE_ONLY);
    channel.applyAsOperator(
        owner,
        new ChannelModeChange.ListAdd(
            CoreChannelModes.INVITE_EXCEPTION,
            new BanEntry("*!*@host.example", "owner", Instant.EPOCH)));

    handler.handle(joiner, MessageParser.parse("JOIN #c"));

    assertThat(output()).contains(" 473 joiner #c :Cannot join channel (+i)");
    assertThat(channel.members()).containsExactly(owner);
    assertThat(joiner.channelMemberships()).isEmpty();
    assertThat(hostname.calls.get()).isEqualTo(1);
  }

  @Test
  void failedIdentitySampleWithBansIsNeverTreatedAsAdmission() {
    channel.addBan(new BanEntry("*!*@nobody.example", "owner", Instant.EPOCH));

    assertThatThrownBy(() -> handler.handle(joiner, MessageParser.parse("JOIN #c")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(channel.members()).containsExactly(owner);
    assertThat(joiner.channelMemberships()).isEmpty();
  }

  private String output() {
    joinerWriter.close();
    return joinerOut.toString(StandardCharsets.UTF_8);
  }

  private static ClientSession session(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent(nick);
    return session;
  }

  private static final class CountingFailingHostname
      implements ServerExtension, HostnameDisplayExtension {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String id() {
      return "failing-hostname";
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
      calls.incrementAndGet();
      throw new IllegalStateException("display failed");
    }
  }
}
