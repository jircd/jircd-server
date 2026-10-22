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
import net.jircd.core.session.WhowasEntry;
import net.jircd.core.session.WhowasHistory;
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
    return user(nick, sink, "r".repeat(600));
  }

  private static ClientSession user(String nick, ByteArrayOutputStream sink, String realname) {
    ClientSession session = new ClientSession(nick, "203.0.113.7", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("界".repeat(ServerConfiguration.IDENT_MAX_CHARS));
    session.setRealname(realname);
    session.attachWriter(new SessionWriter(session, sink, TagRenderer.NONE));
    return session;
  }

  private static List<String> lines(ByteArrayOutputStream out) {
    String text = out.toString(StandardCharsets.UTF_8);
    return text.isEmpty() ? List.of() : List.of(text.split("\r\n"));
  }

  private static int wireBytes(String line) {
    return line.getBytes(StandardCharsets.UTF_8).length + 2;
  }

  private static String only(List<String> sent, String numeric) {
    List<String> matching =
        sent.stream().filter(line -> line.contains(" " + numeric + " ")).toList();
    assertThat(matching).as(numeric + " lines in " + sent).hasSize(1);
    return matching.getFirst();
  }

  /**
   * Every reply in the table, at the widest accepted server name and with the widest ident, host,
   * flags and an empty real name, must serialize to exactly the size the model predicts.
   */
  @ParameterizedTest
  @CsvSource({"9,50", "30,50", "30,64", "100,100", "150,20"})
  void theModelEqualsTheRealSerializedLineForEveryReply(int nick, int chan) throws Exception {
    int serverBytes = widestAcceptedServerBytes(nick, chan);
    String server = "s".repeat(serverBytes - 4) + ".net";
    String channelName = "#" + "c".repeat(chan - 1);
    String inviteChannel = "#" + "d".repeat(chan - 1);
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new LongHost());
    extensions.enable("long-host");
    ByteArrayOutputStream ownerOut = new ByteArrayOutputStream();
    ByteArrayOutputStream askerOut = new ByteArrayOutputStream();
    ClientSession owner = user("o".repeat(nick), ownerOut, "");
    ClientSession asker = user("a".repeat(nick), askerOut, "");
    owner.setAwayReason("x");
    owner.grantAdministratorPrivilege();
    ChannelRegistry channels = new ChannelRegistry();
    NicknameRegistry nicknames = new NicknameRegistry();
    assertThat(nicknames.claim(owner.nickname(), owner)).isTrue();
    assertThat(nicknames.claim(asker.nickname(), asker)).isTrue();
    channels.join(channelName, owner, ignored -> true);
    channels.join(channelName, asker, ignored -> true);
    String presentedHost = "h".repeat(ServerConfiguration.PRESENTED_HOST_MAX_BYTES);
    WhowasHistory history = new WhowasHistory(() -> 10);
    history.record(
        new WhowasEntry(
            "w".repeat(nick), owner.ident(), "203.0.113.7", presentedHost, "", Instant.now()));

    new TopicCommandHandler(channels, extensions, () -> server, () -> 390)
        .handle(owner, MessageParser.parse("TOPIC " + channelName + " :hello"));
    new TopicCommandHandler(channels, extensions, () -> server, () -> 390)
        .handle(asker, MessageParser.parse("TOPIC " + channelName));
    new WhoCommandHandler(channels, nicknames, extensions, () -> server, () -> true)
        .handle(asker, MessageParser.parse("WHO " + channelName));
    new WhoisCommandHandler(nicknames, extensions, () -> server)
        .handle(asker, MessageParser.parse("WHOIS " + owner.nickname()));
    new WhowasCommandHandler(history, () -> server)
        .handle(asker, MessageParser.parse("WHOWAS " + "w".repeat(nick)));
    new UserhostCommandHandler(nicknames, extensions, () -> server)
        .handle(asker, MessageParser.parse("USERHOST " + owner.nickname()));
    new InviteCommandHandler(channels, nicknames, extensions, () -> server, () -> chan)
        .handle(owner, MessageParser.parse("INVITE " + asker.nickname() + " " + inviteChannel));
    asker.writer().close();
    owner.writer().close();

    Map<String, Integer> worst = HostmaskReplyBudget.worstCaseBytes(serverBytes, nick, chan);
    List<String> sent = lines(askerOut);
    assertThat(wireBytes(only(sent, "333")))
        .as(only(sent, "333"))
        .isEqualTo(worst.get("333 (TOPICWHOTIME)"));
    assertThat(wireBytes(only(sent, "311")))
        .as(only(sent, "311"))
        .isEqualTo(worst.get("311/314 (WHOIS/WHOWAS)"));
    assertThat(wireBytes(only(sent, "314")))
        .as(only(sent, "314"))
        .isEqualTo(worst.get("311/314 (WHOIS/WHOWAS)"));
    assertThat(wireBytes(only(sent, "302")))
        .as(only(sent, "302"))
        .isEqualTo(worst.get("302 (USERHOST)"));
    assertThat(wireBytes(only(sent, "INVITE")))
        .as(only(sent, "INVITE"))
        .isEqualTo(worst.get("INVITE"));
    List<String> who = sent.stream().filter(line -> line.contains(" 352 ")).toList();
    assertThat(who).hasSize(2);
    assertThat(who.stream().mapToInt(HostmaskReplyFitTest::wireBytes).max().orElseThrow())
        .isEqualTo(worst.get("352 (WHO)"));
    assertThat(worst.values()).allMatch(bytes -> bytes <= LIMIT);
    assertThat(
            HostmaskReplyBudget.worstCaseBytes(serverBytes + 1, nick, chan).values().stream()
                .anyMatch(bytes -> bytes > LIMIT))
        .as("one more server byte is rejected")
        .isTrue();
  }

  @Test
  void aLongRealNameIsCutSoWhoAndWhoisStillArrive() throws Exception {
    int nick = 30;
    int chan = 50;
    int serverBytes = widestAcceptedServerBytes(nick, chan);
    String server = "s".repeat(serverBytes - 4) + ".net";
    String channelName = "#" + "c".repeat(chan - 1);
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new LongHost());
    extensions.enable("long-host");
    ByteArrayOutputStream askerOut = new ByteArrayOutputStream();
    ClientSession owner = user("o".repeat(nick), new ByteArrayOutputStream());
    ClientSession asker = user("a".repeat(nick), askerOut);
    ChannelRegistry channels = new ChannelRegistry();
    NicknameRegistry nicknames = new NicknameRegistry();
    assertThat(nicknames.claim(owner.nickname(), owner)).isTrue();
    assertThat(nicknames.claim(asker.nickname(), asker)).isTrue();
    channels.join(channelName, owner, ignored -> true);
    channels.join(channelName, asker, ignored -> true);
    new WhoCommandHandler(channels, nicknames, extensions, () -> server, () -> true)
        .handle(asker, MessageParser.parse("WHO " + channelName));
    new WhoisCommandHandler(nicknames, extensions, () -> server)
        .handle(asker, MessageParser.parse("WHOIS " + owner.nickname()));
    asker.writer().close();
    owner.writer().close();
    List<String> sent = lines(askerOut);
    assertThat(sent.stream().filter(line -> line.contains(" 352 "))).hasSize(2);
    assertThat(sent)
        .anyMatch(line -> line.contains(" 311 "))
        .allMatch(line -> wireBytes(line) <= LIMIT);
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
