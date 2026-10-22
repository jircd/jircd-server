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
import java.util.List;
import java.util.Map;
import net.jircd.core.config.HostmaskReplyBudget;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Drives the real handlers at the widest server name the reply budget accepts, with the widest
 * ident and an extension host far over the cap, and checks that no reply is dropped for size.
 */
class HostmaskReplyFitTest {

  private static final int LIMIT = 512;

  /** A cloak that returns a host far beyond any bound. */
  private static final class LongHost implements ServerExtension, HostnameDisplayExtension {
    @Override
    public String id() {
      return "long-host";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public void start(ServerContext context) {
      // Stateless test extension.
    }

    @Override
    public void stop() {
      // Stateless test extension.
    }

    @Override
    public String display(String hostname) {
      return "h".repeat(250) + ".example";
    }
  }

  private static int widestAcceptedServerBytes(int nick, int channel) {
    int server = 3;
    while (HostmaskReplyBudget.worstCaseBytes(server + 1, nick, channel).values().stream()
        .allMatch(bytes -> bytes <= LIMIT)) {
      server++;
    }
    return server;
  }

  private static ClientSession user(String nick, ByteArrayOutputStream sink) {
    ClientSession session = new ClientSession(nick, "203.0.113.7", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("界".repeat(ServerConfiguration.IDENT_MAX_CHARS));
    session.setRealname("r".repeat(600));
    session.attachWriter(new SessionWriter(session, sink, TagRenderer.NONE));
    return session;
  }

  private static List<String> lines(ByteArrayOutputStream out) {
    String text = out.toString(StandardCharsets.UTF_8);
    return text.isEmpty() ? List.of() : List.of(text.split("\r\n"));
  }

  @ParameterizedTest
  @CsvSource({"9,50", "30,50", "30,64", "100,100", "150,20"})
  void repliesCarryingAHostmaskFitAtTheWidestAcceptedServerName(int nick, int chan)
      throws Exception {
    int serverBytes = widestAcceptedServerBytes(nick, chan);
    String server = "s".repeat(serverBytes - 4) + ".net";
    String channelName = "#" + "c".repeat(chan - 1);
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new LongHost());
    extensions.enable("long-host");
    ByteArrayOutputStream ownerOut = new ByteArrayOutputStream();
    ByteArrayOutputStream askerOut = new ByteArrayOutputStream();
    ClientSession owner = user("o".repeat(nick), ownerOut);
    ClientSession asker = user("a".repeat(nick), askerOut);
    ChannelRegistry channels = new ChannelRegistry();
    NicknameRegistry nicknames = new NicknameRegistry();
    assertThat(nicknames.claim(owner.nickname(), owner)).isTrue();
    assertThat(nicknames.claim(asker.nickname(), asker)).isTrue();
    channels.join(channelName, owner, ignored -> true);
    channels.join(channelName, asker, ignored -> true);

    new TopicCommandHandler(channels, extensions, () -> server, () -> 390)
        .handle(owner, MessageParser.parse("TOPIC " + channelName + " :hello"));
    new TopicCommandHandler(channels, extensions, () -> server, () -> 390)
        .handle(asker, MessageParser.parse("TOPIC " + channelName));
    new WhoCommandHandler(channels, nicknames, extensions, () -> server, () -> true)
        .handle(asker, MessageParser.parse("WHO " + channelName));
    new WhoisCommandHandler(nicknames, extensions, () -> server)
        .handle(asker, MessageParser.parse("WHOIS " + owner.nickname()));
    new UserhostCommandHandler(nicknames, extensions, () -> server)
        .handle(asker, MessageParser.parse("USERHOST " + owner.nickname()));
    asker.writer().close();
    owner.writer().close();

    Map<String, Integer> worst = HostmaskReplyBudget.worstCaseBytes(serverBytes, nick, chan);
    List<String> sent = lines(askerOut);
    assertThat(sent)
        .as("333, 352 (one per member), 311 and 302 must all arrive")
        .anyMatch(line -> line.contains(" 333 "))
        .anyMatch(line -> line.contains(" 352 "))
        .anyMatch(line -> line.contains(" 311 "))
        .anyMatch(line -> line.contains(" 302 "));
    for (String line : sent) {
      int bytes = line.getBytes(StandardCharsets.UTF_8).length + 2;
      assertThat(bytes).as(line).isLessThanOrEqualTo(LIMIT);
      if (line.contains(" 333 ")) {
        assertThat(bytes)
            .as("the model matches the real 333")
            .isEqualTo(worst.get("333 (TOPICWHOTIME)"));
      }
      if (line.contains(" 302 ")) {
        assertThat(bytes).isLessThanOrEqualTo(worst.get("302 (USERHOST)"));
      }
    }
    assertThat(worst.get("333 (TOPICWHOTIME)")).isLessThanOrEqualTo(LIMIT);
    assertThat(
            HostmaskReplyBudget.worstCaseBytes(serverBytes + 1, nick, chan).values().stream()
                .anyMatch(bytes -> bytes > LIMIT))
        .as("one more server byte is rejected")
        .isTrue();
  }

  @Test
  void userhostWithFiveWorstCaseEntriesSplitsAcrossLinesInsteadOfDropping() throws Exception {
    int nick = 100;
    String server = "irc.example.net";
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new LongHost());
    extensions.enable("long-host");
    NicknameRegistry nicknames = new NicknameRegistry();
    ByteArrayOutputStream askerOut = new ByteArrayOutputStream();
    ClientSession asker = user("a".repeat(nick), askerOut);
    StringBuilder command = new StringBuilder("USERHOST");
    List<String> expected = new java.util.ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String name = String.valueOf(i).repeat(1) + "n".repeat(nick - 1);
      ClientSession target = user(name, new ByteArrayOutputStream());
      assertThat(nicknames.claim(name, target)).isTrue();
      command.append(' ').append(name);
      expected.add(
          name
              + "=+"
              + target.ident()
              + "@"
              + "h".repeat(ServerConfiguration.PRESENTED_HOST_MAX_BYTES));
    }
    new UserhostCommandHandler(nicknames, extensions, () -> server)
        .handle(asker, MessageParser.parse(command.toString()));
    asker.writer().close();

    List<String> sent = lines(askerOut);
    assertThat(sent).hasSizeGreaterThan(1);
    List<String> entries = new java.util.ArrayList<>();
    for (String line : sent) {
      assertThat(line.getBytes(StandardCharsets.UTF_8).length + 2).isLessThanOrEqualTo(LIMIT);
      assertThat(line).contains(" 302 ");
      entries.addAll(List.of(MessageParser.parse(line).params().getLast().split(" ")));
    }
    assertThat(entries).containsExactlyElementsOf(expected);
  }
}
