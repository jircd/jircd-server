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
package net.jircd.capabilities.messagetags;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.extension.CommandRegistrar;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.session.CapabilityTagRenderer;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** TAGMSG through the real provider, registry, shared delivery and tag renderer. */
class TagmsgCommandHandlerTest {
  private final NicknameRegistry nicknames = new NicknameRegistry();
  private final ChannelRegistry channels = new ChannelRegistry();
  private final ExtensionRegistry extensions = new ExtensionRegistry();
  private final Map<Command, CommandHandler> commands = new EnumMap<>(Command.class);
  private final Map<ClientSession, ByteArrayOutputStream> outputs = new HashMap<>();
  private final List<SessionWriter> writers = new ArrayList<>();

  @BeforeEach
  void startProvider() {
    CommandRegistrar registrar =
        new CommandRegistrar() {
          @Override
          public void register(Command command, CommandHandler handler) {
            commands.put(command, handler);
          }

          @Override
          public boolean isRegistered(Command command) {
            return commands.containsKey(command);
          }

          @Override
          public void unregister(Command command, CommandHandler installed) {
            commands.remove(command, installed);
          }
        };
    extensions.attachContext(
        new ServerContext(
            nicknames,
            channels,
            extensions,
            () -> "test.jircd.local",
            null,
            null,
            registrar,
            extensions.capabilityServices()));
    CapabilityExtension provider = new MessageTagsExtension();
    extensions.register(provider);
    extensions.enable(provider.id());
  }

  @AfterEach
  void closeWriters() {
    writers.forEach(SessionWriter::close);
  }

  private ClientSession user(String nick, String... negotiated) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("ident");
    session.replaceNegotiatedCapabilities(List.of(negotiated));
    nicknames.claim(nick, session);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    outputs.put(session, out);
    @SuppressWarnings("PMD.CloseResource") // closed by closeWriters()
    SessionWriter writer = new SessionWriter(session, out, new CapabilityTagRenderer(extensions));
    writers.add(writer);
    session.attachWriter(writer);
    return session;
  }

  private void tagmsg(ClientSession sender, String line) throws Exception {
    commands.get(Command.TAGMSG).handle(sender, MessageParser.parse(line));
  }

  private String received(ClientSession session) {
    writers.forEach(SessionWriter::close);
    return outputs.get(session).toString(StandardCharsets.UTF_8);
  }

  @Test
  void theProviderInstallsTagmsgAndWithdrawalRemovesIt() {
    assertThat(commands).containsKey(Command.TAGMSG);
    extensions.disable("message-tags");
    assertThat(commands).doesNotContainKey(Command.TAGMSG);
  }

  @Test
  void deliversClientOnlyTagsAndAMsgidToRecipientsWithMessageTags() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob", "message-tags");

    tagmsg(alice, "@+typing=active;msgid=forged TAGMSG bob");

    String line = received(bob);
    assertThat(line)
        .startsWith("@")
        .contains("+typing=active")
        .contains("msgid=")
        .doesNotContain("msgid=forged")
        .endsWith(":alice!ident@host.example TAGMSG bob\r\n");
    assertThat(received(alice)).isEmpty();
  }

  @Test
  void aSenderWithoutMessageTagsMayStillSendTagmsg() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob", "message-tags");
    tagmsg(alice, "@+x=y TAGMSG bob");
    assertThat(received(bob)).contains("+x=y");
  }

  @Test
  void recipientsWithoutMessageTagsGetNothing() throws Exception {
    ClientSession alice = user("alice", "message-tags");
    ClientSession bob = user("bob");
    ClientSession carol = user("carol", "message-tags");
    channels.join("#c", alice, ch -> true);
    channels.join("#c", bob, ch -> true);
    channels.join("#c", carol, ch -> true);

    tagmsg(alice, "@+x=y TAGMSG #c");

    assertThat(received(bob)).isEmpty();
    assertThat(received(carol)).contains("TAGMSG #c");
    assertThat(received(alice)).isEmpty();
  }

  @Test
  void channelRecipientsShareOneMsgid() throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob", "message-tags");
    ClientSession carol = user("carol", "message-tags");
    channels.join("#c", alice, ch -> true);
    channels.join("#c", bob, ch -> true);
    channels.join("#c", carol, ch -> true);

    tagmsg(alice, "@+x=y TAGMSG #c");

    String bobLine = received(bob);
    String msgid = bobLine.substring(bobLine.indexOf("msgid=") + 6).split("[; ]")[0];
    assertThat(received(carol)).contains("msgid=" + msgid);
  }

  @Test
  void missingParametersAndMissingTagsAreRejected() throws Exception {
    ClientSession alice = user("alice");
    tagmsg(alice, "TAGMSG");
    tagmsg(alice, "TAGMSG bob");
    assertThat(received(alice))
        .contains(" 461 alice TAGMSG ")
        .contains(" 421 alice TAGMSG :Malformed message");
  }

  @Test
  void unknownTargetsAndBlockedChannelsReportTheSharedErrors() throws Exception {
    ClientSession alice = user("alice", "message-tags");
    ClientSession bob = user("bob", "message-tags");
    channels.join("#c", bob, ch -> true);
    channels
        .lookup("#c")
        .orElseThrow()
        .setMode(net.jircd.core.session.CoreChannelModes.MEMBERS_ONLY);

    tagmsg(alice, "@+x=y TAGMSG ghost");
    tagmsg(alice, "@+x=y TAGMSG #c");

    assertThat(received(alice)).contains(" 401 alice ghost ").contains(" 404 alice #c ");
    assertThat(received(bob)).isEmpty();
  }

  @Test
  void anOversizeClientTagSetIsDroppedForTheRecipientWithoutBreakingLaterMessages()
      throws Exception {
    ClientSession alice = user("alice");
    ClientSession bob = user("bob", "message-tags");
    String huge = "x".repeat(5000);

    tagmsg(alice, "@+big=" + huge + " TAGMSG bob");
    tagmsg(alice, "@+ok=1 TAGMSG bob");

    String received = received(bob);
    assertThat(received).doesNotContain("+big=");
    assertThat(received.lines()).hasSize(1);
    assertThat(received).contains("+ok=1");
  }
}
