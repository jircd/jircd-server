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
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.command.TagmsgCommandHandler;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Issue #18 follow-up: direct TAGMSG delivery and echo follow the same rules as PRIVMSG. */
class SelfDirectTagmsgTest {

  private static final class EchoStub extends AbstractCapabilityExtension {
    EchoStub() {
      super("echo-message");
    }

    @Override
    public boolean includeSenderInFanOut(ClientSession sender) {
      return sender.negotiatedCapabilities().contains("echo-message");
    }
  }

  private static final class Fixture implements AutoCloseable {
    final ClientSession alice = session("alice", "a");
    final ClientSession bob = session("bob", "b");
    final ByteArrayOutputStream aliceOut = new ByteArrayOutputStream();
    final ByteArrayOutputStream bobOut = new ByteArrayOutputStream();
    final SessionWriter aliceWriter;
    final SessionWriter bobWriter;
    final TagmsgCommandHandler handler;

    Fixture(boolean echo) {
      NicknameRegistry nicknames = new NicknameRegistry();
      nicknames.claim("alice", alice);
      nicknames.claim("bob", bob);
      ExtensionRegistry extensions = new ExtensionRegistry();
      extensions.register(new EchoStub());
      extensions.enable("echo-message");
      if (echo) {
        alice.negotiatedCapabilities().add("echo-message");
      }
      aliceWriter = new SessionWriter(alice, aliceOut, TagRenderer.NONE);
      bobWriter = new SessionWriter(bob, bobOut, TagRenderer.NONE);
      alice.attachWriter(aliceWriter);
      bob.attachWriter(bobWriter);
      handler =
          new TagmsgCommandHandler(
              new ChannelRegistry(), nicknames, extensions, () -> "test.jircd.local");
    }

    private static ClientSession session(String nick, String id) {
      ClientSession s = new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
      s.setNickname(nick);
      s.setIdent("ident");
      s.negotiatedCapabilities().add("message-tags");
      return s;
    }

    void send(String target) throws Exception {
      handler.handle(alice, MessageParser.parse("@+typing=active TAGMSG " + target));
      aliceWriter.close();
      bobWriter.close();
    }

    String alice() {
      return aliceOut.toString(StandardCharsets.UTF_8);
    }

    String bob() {
      return bobOut.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws Exception {
      aliceWriter.close();
      bobWriter.close();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void selfTargetedTagmsgIsDeliveredExactlyOnce(boolean echo) throws Exception {
    try (Fixture f = new Fixture(echo)) {
      f.send("alice");
      assertThat(f.alice().lines()).hasSize(1);
      assertThat(f.alice()).contains("TAGMSG alice");
    }
  }

  @Test
  void directTagmsgIsEchoedOnceWhenEchoMessageNegotiated() throws Exception {
    try (Fixture f = new Fixture(true)) {
      f.send("bob");
      assertThat(f.bob().lines()).hasSize(1);
      assertThat(f.alice().lines()).hasSize(1);
      assertThat(f.alice()).contains("TAGMSG bob");
    }
  }

  @Test
  void directTagmsgIsNotEchoedWithoutEchoMessage() throws Exception {
    try (Fixture f = new Fixture(false)) {
      f.send("bob");
      assertThat(f.bob().lines()).hasSize(1);
      assertThat(f.alice()).isEmpty();
    }
  }

  @Test
  void echoRequiresMessageTagsOnTheSender() throws Exception {
    try (Fixture f = new Fixture(true)) {
      f.alice.negotiatedCapabilities().remove("message-tags");
      f.send("bob");
      assertThat(f.bob().lines()).hasSize(1);
      assertThat(f.alice()).isEmpty();
    }
  }
}
