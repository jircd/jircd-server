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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.core.session.command.MessageCommandHandler;
import net.jircd.core.session.command.NamesCommandHandler;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies byte budgets for NAMES replies and outgoing session messages. */
class OutboundMessageBudgetTest {
  private static ClientSession session(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("ident");
    return session;
  }

  @ParameterizedTest
  @ValueSource(strings = {"JOIN #ébudget", "NAMES #ébudget", "NAMES"})
  void namesMustFitTheCommandBudget(String command) throws Exception {
    ClientSession requester = session("requester");
    ChannelRegistry channels = new ChannelRegistry();
    for (int i = 0; i < 60; i++) {
      channels.join("#ébudget", session(String.format("nick%05d", i)), ignored -> true);
    }
    Channel channel = channels.lookup("#ébudget").orElseThrow();
    channel.voiced().add(channel.findMember("nick00001").orElseThrow());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(requester, out, TagRenderer.NONE)) {
      requester.attachWriter(writer);
      if (command.startsWith("JOIN")) {
        new JoinCommandHandler(
                channels, new ExtensionRegistry(), () -> "test.jircd.local", () -> 50)
            .handle(requester, MessageParser.parse(command));
      } else {
        new NamesCommandHandler(channels, new ExtensionRegistry(), () -> "test.jircd.local")
            .handle(requester, MessageParser.parse(command));
      }
    }
    String[] lines = out.toString(StandardCharsets.UTF_8).split("\r\n");
    List<String> tokens = new ArrayList<>();
    int endings = 0;
    int chunks = 0;
    for (String line : lines) {
      Message parsed = MessageParser.parse(line);
      if ("353".equals(parsed.rawCommand())) {
        chunks++;
        tokens.addAll(List.of(parsed.params().getLast().split(" ")));
      } else if ("366".equals(parsed.rawCommand())) {
        endings++;
      }
    }
    assertThat(chunks).isGreaterThan(1);
    assertThat(endings).isEqualTo(1);
    assertThat(tokens).contains("@nick00000", "+nick00001").doesNotHaveDuplicates();
    assertThat(tokens.stream().map(token -> token.replaceFirst("^[@+]", "")))
        .containsExactlyInAnyOrderElementsOf(
            channel.members().stream().map(ClientSession::nickname).toList());
    for (String line : lines) {
      assertThat(line.getBytes(StandardCharsets.UTF_8).length + 2)
          .as("NAMES wire bytes including CRLF")
          .isLessThanOrEqualTo(512);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"PRIVMSG", "NOTICE"})
  void forwardingAnAcceptedUtf8MessageMustFitAfterAddingTheHostmask(String command)
      throws Exception {
    ClientSession sender = session("sender");
    ClientSession recipient = session("bob");
    NicknameRegistry nicknames = new NicknameRegistry();
    assertThat(nicknames.claim("bob", recipient)).isTrue();
    String header = command + " bob :";
    int bodyBytes = 510 - header.length();
    String incoming = header + "😀".repeat(bodyBytes / 4) + "x".repeat(bodyBytes % 4);
    assertThat(incoming.getBytes(StandardCharsets.UTF_8).length + 2).isEqualTo(512);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(recipient, out, TagRenderer.NONE)) {
      recipient.attachWriter(writer);
      new MessageCommandHandler(
              new ChannelRegistry(),
              nicknames,
              new ExtensionRegistry(),
              () -> "test.jircd.local",
              command.equals("NOTICE"))
          .handle(sender, MessageParser.parse(incoming));
    }
    assertThat(out.toString(StandardCharsets.UTF_8))
        .startsWith(":sender!ident@host.example " + command + " bob ");
    assertThat(out.size()).as("forwarded wire bytes including CRLF").isLessThanOrEqualTo(512);
  }

  @Test
  void rawWriterMustEnforceTheBudgetIncludingCrLf() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session("bob"), out, TagRenderer.NONE)) {
      writer.enqueueRaw(
          new Message(Map.of(), null, Command.NOTICE, "NOTICE", List.of("bob", "x".repeat(500))));
    }
    assertThat(out.size()).as("raw wire bytes including CRLF").isLessThanOrEqualTo(512);
  }

  @Test
  void exactly512WireBytesArePreserved() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session("bob"), out, TagRenderer.NONE)) {
      writer.enqueueRaw(
          new Message(Map.of(), null, Command.NOTICE, "NOTICE", List.of("bob", "x".repeat(499))));
    }
    assertThat(out.size()).isEqualTo(512);
    assertThat(out.toString(StandardCharsets.UTF_8)).endsWith("x".repeat(499) + "\r\n");
  }

  @Test
  void validTagsHaveASeparateAllowance() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session("bob"), out, TagRenderer.NONE)) {
      writer.enqueueRaw(
          new Message(
              Map.of("example/tag", "x".repeat(1000)),
              null,
              Command.NOTICE,
              "NOTICE",
              List.of("bob", "x".repeat(499))));
    }
    String wire = out.toString(StandardCharsets.UTF_8);
    assertThat(out.size()).isGreaterThan(512);
    assertThat(wire.substring(wire.indexOf(' ') + 1).getBytes(StandardCharsets.UTF_8)).hasSize(512);
  }

  @Test
  void rejectedRawMessagesDoNotStopTheWriter() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session("bob"), out, TagRenderer.NONE)) {
      writer.enqueueRaw(Message.of(Command.JOIN, "#" + "x".repeat(600)));
      writer.enqueueRaw(
          new Message(
              Map.of("key", "x".repeat(8190)),
              null,
              Command.NOTICE,
              "NOTICE",
              List.of("bob", "hello")));
      writer.enqueueRaw(Message.of(Command.PRIVMSG, "x".repeat(600), "hello"));
      writer.enqueueRaw(Message.of(Command.PING, "still-alive"));
    }
    assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("PING still-alive\r\n");
  }

  @Test
  void rawServerTagsHaveA4094ByteEscapedDataAllowance() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session("bob"), out, TagRenderer.NONE)) {
      writer.enqueueRaw(
          new Message(
              Map.of("s", "x".repeat(4092)),
              null,
              Command.NOTICE,
              "NOTICE",
              List.of("bob", "boundary")));
      writer.enqueueRaw(
          new Message(
              Map.of("s", "x".repeat(4093)),
              null,
              Command.NOTICE,
              "NOTICE",
              List.of("bob", "too-large")));
      writer.enqueueRaw(Message.of(Command.PING, "after-tags"));
    }
    String wire = out.toString(StandardCharsets.UTF_8);
    assertThat(wire)
        .contains("boundary")
        .doesNotContain("too-large")
        .endsWith("PING after-tags\r\n");
    assertThat(wire.substring(0, wire.indexOf(' ')).getBytes(StandardCharsets.UTF_8).length + 1)
        .isEqualTo(4096);
  }
}
