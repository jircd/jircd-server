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
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.extension.SupportedFeatures;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.CoreChannelModes;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExceptionModesTest {
  private final ChannelRegistry registry = new ChannelRegistry();
  private final ExtensionRegistry extensions = new ExtensionRegistry();
  private final NicknameRegistry nicknames = new NicknameRegistry();
  private final ClientSession op = session("op");
  private final ClientSession user = session("Nick[");
  private final Channel channel = registry.join("#c", op, ignored -> true).orElseThrow().channel();
  private final ModeCommandHandler modes =
      new ModeCommandHandler(registry, nicknames, extensions, () -> "server", () -> 20);
  private final JoinCommandHandler joins =
      new JoinCommandHandler(registry, extensions, () -> "server", () -> 50);

  @Test
  void exceptionsDoNotDeclareRestrictiveGates() {
    assertThat(CoreChannelModes.BAN_EXCEPTION.gates()).isEmpty();
    assertThat(CoreChannelModes.INVITE_EXCEPTION.gates()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"b,368,ban", "e,349,exception", "I,347,invite"})
  void listEndUsesConventionalText(char flag, String numeric, String description) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(op, out, TagRenderer.NONE)) {
      op.attachWriter(writer);
      mode(op, String.valueOf(flag));
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains(" " + numeric + " op #c :End of channel " + description + " list\r\n");
  }

  @ParameterizedTest
  @CsvSource({"e,348,349", "I,346,347"})
  void listsNormalizeFoldRemoveAndEnforcePrivileges(char flag, String entry, String end)
      throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(op, out, TagRenderer.NONE)) {
      op.attachWriter(writer);
      mode(op, "+" + flag + " Nick[");
      mode(op, "+" + flag + " nICK{");
      mode(op, String.valueOf(flag));
      mode(op, "-" + flag + " NICK{");
      mode(op, "+" + flag);
      mode(user, "+" + flag + " outsider");
      mode(op, String.valueOf(flag));
      mode(op, "+z");
    }
    String output = out.toString(StandardCharsets.UTF_8);
    assertThat(output)
        .contains(
            " MODE #c +" + flag + " Nick[!*@*",
            " " + entry + " op #c Nick[!*@* op ",
            " " + end + " op #c",
            " 472 op z ");
    assertThat(output.split(" " + entry + " op #c", -1)).hasSize(2);
  }

  @ParameterizedTest
  @CsvSource({"e,349", "I,347"})
  void capIsPerListAndDuplicatesSucceedAtCapacity(char flag, String end) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(op, out, TagRenderer.NONE)) {
      op.attachWriter(writer);
      for (int i = 0; i < Channel.MAX_BANS; i++) mode(op, "+" + flag + " nick" + i);
      mode(op, "+" + flag + " NICK0");
      mode(op, "+" + flag + " overflow");
      mode(op, "-" + flag + " NICK0");
      mode(op, "+" + flag + " replacement");
      mode(op, "+b independent");
      mode(op, String.valueOf(flag));
    }
    String output = out.toString(StandardCharsets.UTF_8);
    assertThat(output)
        .contains(
            " 478 op #c overflow!*@*",
            " MODE #c +" + flag + " NICK0!*@*",
            " MODE #c +" + flag + " replacement!*@*",
            " MODE #c +b independent!*@*",
            " " + end + " op #c");
    assertThat(output.split(" 478 ", -1)).hasSize(2);
  }

  @Test
  void banExceptionAllowsJoinAndSendButNotModeratedSend() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(op, out, TagRenderer.NONE)) {
      op.attachWriter(writer);
      mode(op, "+b *");
      joins.handle(user, MessageParser.parse("JOIN #c"));
      assertThat(channel.members()).doesNotContain(user);
      mode(op, "+e nick{");
      joins.handle(user, MessageParser.parse("JOIN #c"));
      assertThat(channel.members()).contains(user);
      MessageCommandHandler messages =
          new MessageCommandHandler(registry, nicknames, extensions, () -> "server", false);
      messages.handle(user, MessageParser.parse("PRIVMSG #c :allowed"));
      mode(op, "+m");
      messages.handle(user, MessageParser.parse("PRIVMSG #c :moderated"));
      mode(op, "-m");
      mode(op, "-e NICK{");
      messages.handle(user, MessageParser.parse("PRIVMSG #c :banned"));
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("PRIVMSG #c allowed")
        .doesNotContain("PRIVMSG #c moderated", "PRIVMSG #c banned");
  }

  @Test
  void inviteExceptionBypassesOnlyInviteOnly() throws Exception {
    mode(op, "+iI nick{");
    channel.setKey("secret");
    joins.handle(user, MessageParser.parse("JOIN #c wrong"));
    assertThat(channel.members()).doesNotContain(user);
    channel.setMemberLimit(1);
    joins.handle(user, MessageParser.parse("JOIN #c secret"));
    assertThat(channel.members()).doesNotContain(user);
    channel.setMemberLimit(2);
    mode(op, "+b *");
    joins.handle(user, MessageParser.parse("JOIN #c secret"));
    assertThat(channel.members()).doesNotContain(user);
    mode(op, "+e nick{");
    joins.handle(user, MessageParser.parse("JOIN #c secret"));
    assertThat(channel.members()).contains(user);
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"host.example", "cloak.example"})
  void exceptionsMatchRealOrPresentedHostname(String hostname) throws Exception {
    extensions.register(new TestCloak());
    extensions.enable("test-cloak");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(op, out, TagRenderer.NONE)) {
      op.attachWriter(writer);
      mode(op, "+ib *");
      mode(op, "+eI *!*@" + hostname + " *!*@" + hostname);
      joins.handle(user, MessageParser.parse("JOIN #c"));
      assertThat(channel.members()).contains(user);
      new MessageCommandHandler(registry, nicknames, extensions, () -> "server", false)
          .handle(user, MessageParser.parse("PRIVMSG #c :hostname-match"));
    }
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("PRIVMSG #c hostname-match");
  }

  @Test
  void removingInviteExceptionRestoresInviteOnlyRestriction() throws Exception {
    mode(op, "+iI nick{");
    mode(op, "-I NICK{");
    joins.handle(user, MessageParser.parse("JOIN #c"));
    assertThat(channel.members()).doesNotContain(user);
    channel.invite(user.nickname());
    joins.handle(user, MessageParser.parse("JOIN #c"));
    assertThat(channel.members()).contains(user);
    assertThat(channel.isInvited(user.nickname())).isFalse();
  }

  private static final class TestCloak implements ServerExtension, HostnameDisplayExtension {
    @Override
    public String id() {
      return "test-cloak";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public void start(ServerContext context) {
      // Stateless test cloak: no resources to initialize.
    }

    @Override
    public void stop() {
      // Stateless test cloak: no resources to release.
    }

    @Override
    public String display(String hostname) {
      return "cloak.example";
    }
  }

  @Test
  void outsidersCanQueryButCannotChangeLists() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(user, out, TagRenderer.NONE)) {
      user.attachWriter(writer);
      mode(user, "e");
      mode(user, "+I");
      mode(user, "+e *");
      mode(user, "-I *");
    }
    assertThat(out.toString(StandardCharsets.UTF_8)).contains(" 349 Nick[ #c", " 347 Nick[ #c");
    assertThat(out.toString(StandardCharsets.UTF_8).split(" 482 ", -1)).hasSize(3);
  }

  @Test
  void supportTokensDescribeCatalogAndCaps() {
    assertThat(SupportedFeatures.defaults().tokens())
        .contains("EXCEPTS=e", "INVEX=I", "MAXLIST=Ibe:100", "CHANMODES=Ibe,k,l,imnpst");
  }

  private void mode(ClientSession requester, String value) throws Exception {
    modes.handle(requester, MessageParser.parse("MODE #c " + value));
  }

  private static ClientSession session(String nickname) {
    ClientSession session =
        new ClientSession(nickname, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nickname);
    session.setIdent("user");
    return session;
  }
}
