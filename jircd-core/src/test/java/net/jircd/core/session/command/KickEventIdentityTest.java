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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.CommittedEvent;
import net.jircd.core.extension.capability.EventKind;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/** The KICKED event names the member by the identity the KICK named, not by a later NICK. */
class KickEventIdentityTest {

  private static ClientSession user(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("ident");
    session.setRealname(nick);
    return session;
  }

  private final ChannelRegistry channels = new ChannelRegistry();
  private final ExtensionRegistry extensions = new ExtensionRegistry();
  private final List<CommittedEvent> events = Collections.synchronizedList(new ArrayList<>());

  private void listenForKicks() {
    extensions.register(
        new AbstractCapabilityExtension("spy") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onEvent(
                EventKind.KICKED,
                event -> {
                  events.add(event);
                  return List.of();
                });
          }
        });
    extensions.enable("spy");
  }

  @Test
  void aNickChangeBetweenCommitAndPublicationDoesNotRenameTheKickedSubject() throws Exception {
    listenForKicks();
    ClientSession alice = user("alice");
    ClientSession bob = user("bob");
    channels.join("#c", alice, ignored -> true);
    channels.join("#c", bob, ignored -> true);
    // The kick has committed; bob's own NICK runs before the event is published.
    KickCommandHandler kick =
        new KickCommandHandler(
            channels, extensions, () -> "server", () -> {}, () -> bob.setNickname("robert"));

    kick.handle(alice, MessageParser.parse("KICK #c bob :out"));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().subject()).isEqualTo("bob!ident@host.example");
    assertThat(bob.nickname()).isEqualTo("robert");
  }

  @Test
  void anRfc1459EquivalentSpellingOfTheTargetStillCapturesItsIdentity() throws Exception {
    listenForKicks();
    ClientSession alice = user("alice");
    ClientSession bob = user("[bob]");
    channels.join("#c", alice, ignored -> true);
    channels.join("#c", bob, ignored -> true);
    KickCommandHandler kick =
        new KickCommandHandler(
            channels, extensions, () -> "server", () -> {}, () -> bob.setNickname("robert"));

    kick.handle(alice, MessageParser.parse("KICK #c {bob} :out"));

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().subject()).isEqualTo("[bob]!ident@host.example");
  }

  @Test
  void aFailingDisplayForTheVictimOnlyNeverAbortsTheKick() throws Exception {
    listenForKicks();
    extensions.register(new VictimHostFails());
    extensions.enable("victim-host-fails");
    ClientSession alice = user("alice");
    ClientSession bob = new ClientSession("bob", "victim.example", RateLimitBucket.withDefaults());
    bob.setNickname("bob");
    bob.setIdent("ident");
    channels.join("#c", alice, ignored -> true);
    channels.join("#c", bob, ignored -> true);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String written;
    try (SessionWriter writer = new SessionWriter(alice, out, TagRenderer.NONE)) {
      alice.attachWriter(writer);

      new KickCommandHandler(channels, extensions, () -> "server")
          .handle(alice, MessageParser.parse("KICK #c bob :out"));
    } finally {
      written = out.toString(StandardCharsets.UTF_8);
    }

    assertThat(channels.lookup("#c").orElseThrow().members()).containsExactly(alice);
    assertThat(written).contains("KICK #c bob out");
    assertThat(events).isEmpty();
  }

  @Test
  void aCaptureThatFailsOnceNeverLetsALaterNickChangeNameTheEvent() throws Exception {
    listenForKicks();
    AtomicInteger lookups = new AtomicInteger();
    extensions.register(new FirstVictimLookupFails(lookups));
    extensions.enable("first-lookup-fails");
    ClientSession alice = user("alice");
    ClientSession bob = new ClientSession("bob", "victim.example", RateLimitBucket.withDefaults());
    bob.setNickname("bob");
    bob.setIdent("ident");
    channels.join("#c", alice, ignored -> true);
    channels.join("#c", bob, ignored -> true);
    KickCommandHandler kick =
        new KickCommandHandler(
            channels, extensions, () -> "server", () -> {}, () -> bob.setNickname("robert"));

    kick.handle(alice, MessageParser.parse("KICK #c bob :out"));

    assertThat(channels.lookup("#c").orElseThrow().members()).containsExactly(alice);
    assertThat(lookups).hasValue(1); // never asked again after the failed capture
    assertThat(events).isEmpty();
  }

  private static final class FirstVictimLookupFails
      implements ServerExtension, HostnameDisplayExtension {
    private final AtomicInteger victimLookups;

    FirstVictimLookupFails(AtomicInteger victimLookups) {
      this.victimLookups = victimLookups;
    }

    @Override
    public String id() {
      return "first-lookup-fails";
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
      if ("victim.example".equals(hostname) && victimLookups.getAndIncrement() == 0) {
        throw new IllegalStateException("display failed");
      }
      return hostname;
    }
  }

  private static final class VictimHostFails implements ServerExtension, HostnameDisplayExtension {
    @Override
    public String id() {
      return "victim-host-fails";
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
      if ("victim.example".equals(hostname)) throw new IllegalStateException("display failed");
      return hostname;
    }
  }
}
