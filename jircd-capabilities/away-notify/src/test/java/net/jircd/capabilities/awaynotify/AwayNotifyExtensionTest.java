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
package net.jircd.capabilities.awaynotify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.extension.capability.Audience;
import net.jircd.core.extension.capability.CapabilityDescriptor;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.CommittedEvent;
import net.jircd.core.extension.capability.EventIntent;
import net.jircd.core.extension.capability.EventKind;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.command.AwayCommandHandler;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AwayNotifyExtensionTest {
  private final NicknameRegistry nicknames = new NicknameRegistry();
  private final ChannelRegistry channels = new ChannelRegistry();
  private final ExtensionRegistry extensions = new ExtensionRegistry();
  private final Map<ClientSession, ByteArrayOutputStream> outputs = new HashMap<>();
  private final List<SessionWriter> writers = new ArrayList<>();
  private final AwayCommandHandler away = new AwayCommandHandler(() -> "server", extensions);

  @AfterEach
  void closeWriters() {
    writers.forEach(SessionWriter::close);
  }

  private void enable() {
    extensions.register(new AwayNotifyExtension());
    extensions.enable(AwayNotifyExtension.ID);
  }

  private ClientSession user(String nick, boolean awayNotify) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("ident");
    if (awayNotify) session.replaceNegotiatedCapabilities(List.of(AwayNotifyExtension.ID));
    nicknames.claim(nick, session);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    outputs.put(session, out);
    @SuppressWarnings("PMD.CloseResource") // closed by closeWriters()
    SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE);
    writers.add(writer);
    session.attachWriter(writer);
    return session;
  }

  private String received(ClientSession session) {
    writers.forEach(SessionWriter::close);
    return outputs.get(session).toString(StandardCharsets.UTF_8);
  }

  @Test
  void registersOnlyAnAwayChangedEventHook() {
    CapabilityRegistrar registrar = new CapabilityRegistrar("away-notify", "away-notify");
    new AwayNotifyExtension().register(registrar);
    CapabilityDescriptor descriptor = registrar.build();
    assertThat(descriptor.registrations()).hasSize(1);
    assertThat(descriptor.registrations().getFirst().kind()).isEqualTo(HookKind.EVENT);
    assertThat(descriptor.registrations().getFirst().eventKind()).isEqualTo(EventKind.AWAY_CHANGED);
  }

  @Test
  void theNoticeCarriesTheSubjectAndReasonAndAClearHasNoParameter() {
    ClientSession peer = user("peer", true);
    Audience audience = new Audience(() -> List.of(peer, peer));

    List<EventIntent> set =
        AwayNotifyExtension.awayNotice(
            new CommittedEvent(EventKind.AWAY_CHANGED, 1, "a!i@h", "", "lunch", audience));
    List<EventIntent> cleared =
        AwayNotifyExtension.awayNotice(
            new CommittedEvent(EventKind.AWAY_CHANGED, 2, "a!i@h", "lunch", "", audience));

    assertThat(set).hasSize(1);
    assertThat(set.getFirst().recipients()).containsExactly(peer);
    assertThat(set.getFirst().message().prefix()).isEqualTo("a!i@h");
    assertThat(set.getFirst().message().params()).containsExactly("lunch");
    assertThat(cleared.getFirst().message().params()).isEmpty();
  }

  @Test
  void noPeersMeansNoIntent() {
    assertThat(
            AwayNotifyExtension.awayNotice(
                new CommittedEvent(
                    EventKind.AWAY_CHANGED, 1, "a!i@h", "", "x", new Audience(List::of))))
        .isEmpty();
  }

  @Test
  void onlyPeersThatNegotiatedItSeeTheNoticeOnceAndNeverTheUser() throws Exception {
    enable();
    ClientSession alice = user("alice", true);
    ClientSession bob = user("bob", true);
    ClientSession carol = user("carol", false);
    for (String channel : List.of("#a", "#b")) {
      for (ClientSession member : List.of(alice, bob, carol)) {
        channels.join(channel, member, ch -> true);
      }
    }

    away.handle(alice, MessageParser.parse("AWAY :at lunch"));

    assertThat(received(bob)).isEqualTo(":alice!ident@host.example AWAY :at lunch\r\n");
    assertThat(received(carol)).isEmpty();
    assertThat(received(alice)).contains(" 306 ").doesNotContain(" AWAY ");
  }

  @Test
  void aClearIsSentWithoutParametersAndARepeatedReasonIsSentAgain() throws Exception {
    enable();
    ClientSession alice = user("alice", false);
    ClientSession bob = user("bob", true);
    channels.join("#c", alice, ch -> true);
    channels.join("#c", bob, ch -> true);

    away.handle(alice, MessageParser.parse("AWAY :x"));
    away.handle(alice, MessageParser.parse("AWAY :x"));
    away.handle(alice, MessageParser.parse("AWAY"));

    assertThat(received(bob))
        .isEqualTo(
            String.join(
                "\r\n",
                ":alice!ident@host.example AWAY x",
                ":alice!ident@host.example AWAY x",
                ":alice!ident@host.example AWAY",
                ""));
  }

  @Test
  void theNoticeUsesThePresentedIdentity() throws Exception {
    extensions.register(new Cloak());
    extensions.enable("test-cloak");
    enable();
    ClientSession alice = user("alice", false);
    ClientSession bob = user("bob", true);
    channels.join("#c", alice, ch -> true);
    channels.join("#c", bob, ch -> true);

    away.handle(alice, MessageParser.parse("AWAY :x"));

    assertThat(received(bob)).isEqualTo(":alice!ident@cloak.example AWAY x\r\n");
  }

  @Test
  void withoutTheProviderAwayStillWorksButNobodyIsNotified() throws Exception {
    ClientSession alice = user("alice", false);
    ClientSession bob = user("bob", true);
    channels.join("#c", alice, ch -> true);
    channels.join("#c", bob, ch -> true);

    away.handle(alice, MessageParser.parse("AWAY :x"));

    assertThat(alice.awayReason()).isEqualTo("x");
    assertThat(received(alice)).contains(" 306 ");
    assertThat(received(bob)).isEmpty();
  }

  private static final class Cloak implements ServerExtension, HostnameDisplayExtension {
    @Override
    public String id() {
      return "test-cloak";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public String display(String hostname) {
      return "cloak.example";
    }

    @Override
    public void start(ServerContext context) {
      // Stateless test cloak: no resources to initialize.
    }

    @Override
    public void stop() {
      // Stateless test cloak: no resources to release.
    }
  }
}
