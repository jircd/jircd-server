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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import net.jircd.core.session.OutboundSizeGuard;
import net.jircd.core.session.command.Replies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplyBudgetReloadTest {
  @BeforeEach
  void forgetEarlierWarnings() {
    Replies.resetOversizedWarnings();
    OutboundSizeGuard.resetWarnings();
  }

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void loweredNicknameLimitGrandfathersMembersAndTheGuardHandlesNewLargerChannels()
      throws Exception {
    try (TestServer server =
            TestServer.start("nicknameMaxLength: 150\nchannelNameMaxLength: 50\n");
        RawIrcClient member = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient probe = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      String nickname = "n".repeat(150);
      member.registerAndAwaitWelcome(nickname, "member");
      root.registerAndAwaitWelcome("root", "root");
      member.send("JOIN #old");
      member.readUntil("366", TIMEOUT);
      Files.writeString(
          server.configPath,
          TestServer.baseYaml() + "nicknameMaxLength: 9\nchannelNameMaxLength: 350\n");
      server.application.reloader().reload();
      assertThat(server.application.nicknameRegistry().lookup(nickname)).isPresent();
      assertThat(server.application.channelRegistry().lookup("#old").orElseThrow().memberListing())
          .containsExactly("@" + nickname);
      probe.send("NICK " + nickname + "x");
      assertThat(probe.readUntil("432", TIMEOUT)).contains("Erroneous nickname");
      root.send("NICK " + "r".repeat(10));
      assertThat(root.readUntil("432", TIMEOUT)).contains("Erroneous nickname");
      String channel = "#" + "c".repeat(349);
      root.send("JOIN " + channel);
      root.readUntil("366", TIMEOUT);
      member.send("JOIN " + channel);
      member.readThroughPong(TIMEOUT);
      try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
        root.send("NAMES " + channel);
        var lines = root.readThroughPong(TIMEOUT);
        assertThat(lines.stream().filter(line -> line.contains(" 353 ")))
            .singleElement()
            .asString()
            .endsWith(" @root");
        assertThat(lines.stream().filter(line -> line.contains(" 366 "))).hasSize(1);
        assertThat(logs.messages())
            .singleElement()
            .asString()
            .contains("Dropping oversized listed item", channel, "itemBytes=150", "budget=130");
      }
      assertThat(server.application.channelRegistry().lookup(channel).orElseThrow().memberListing())
          .contains(nickname, "@root");
    }
  }

  @Test
  void loweredChannelLimitKeepsExistingChannelAndBareNamesStillAttemptsItsCloser()
      throws Exception {
    try (TestServer server = TestServer.start("channelNameMaxLength: 350\n");
        RawIrcClient member = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient requester = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      member.registerAndAwaitWelcome("member", "member");
      String channel = "#" + "c".repeat(349);
      member.send("JOIN " + channel);
      member.readUntil("366", TIMEOUT);
      var original = server.application.channelRegistry().lookup(channel).orElseThrow();
      Files.writeString(
          server.configPath,
          TestServer.baseYaml() + "nicknameMaxLength: 150\nchannelNameMaxLength: 50\n");
      server.application.reloader().reload();
      assertThat(server.application.channelRegistry().lookup(channel)).containsSame(original);
      assertThat(original.memberListing()).containsExactly("@member");
      requester.registerAndAwaitWelcome("r".repeat(150), "requester");
      try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
        requester.send("NAMES");
        var lines = requester.readThroughPong(TIMEOUT);
        assertThat(lines.stream().filter(line -> line.contains(" 353 "))).isEmpty();
        assertThat(lines.stream().filter(line -> line.contains(" 366 ")))
            .singleElement()
            .asString()
            .contains(" * :End of /NAMES list");
        assertThat(logs.messages())
            .singleElement()
            .asString()
            .contains("Dropping oversized listed reply header", channel);
      }
      member.send("JOIN " + channel);
      assertThat(member.readUntil("476", TIMEOUT)).contains("Bad Channel Mask");
      assertThat(server.application.channelRegistry().lookup(channel)).containsSame(original);
    }
  }

  @Test
  void retainedNickAndChannelHostmaskRepliesFitOrWarnAndCommandsContinue() throws Exception {
    try (TestServer server =
            TestServer.start("nicknameMaxLength: 150\nchannelNameMaxLength: 50\n");
        RawIrcClient owner = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient member = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient target = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      String memberNick = "m".repeat(150);
      String targetNick = "t".repeat(150);
      String channel = "#" + "c".repeat(349);
      owner.registerAndAwaitWelcome("owner", "owner");
      member.registerAndAwaitWelcome(memberNick, "member");
      target.registerAndAwaitWelcome(targetNick, "target");
      member.send("JOIN #sync");
      member.readThroughPong(TIMEOUT);
      target.send("JOIN #sync");
      target.readThroughPong(TIMEOUT);
      Files.writeString(
          server.configPath,
          TestServer.baseYaml() + "nicknameMaxLength: 9\nchannelNameMaxLength: 350\n");
      server.application.reloader().reload();
      owner.send("JOIN " + channel);
      owner.readThroughPong(TIMEOUT);
      member.send("JOIN " + channel);
      member.readThroughPong(TIMEOUT);
      Files.writeString(
          server.configPath,
          TestServer.baseYaml() + "nicknameMaxLength: 9\nchannelNameMaxLength: 50\n");
      server.application.reloader().reload();
      assertThat(server.application.nicknameRegistry().lookup(memberNick)).isPresent();
      assertThat(server.application.channelRegistry().lookup(channel).orElseThrow().memberListing())
          .contains(memberNick, "@owner");
      member.send("TOPIC " + channel + " :retained topic");
      member.readThroughPong(TIMEOUT);
      Replies.resetOversizedWarnings();
      OutboundSizeGuard.resetWarnings();
      try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
        owner.send("WHO " + channel);
        var who = owner.readThroughPong(TIMEOUT);
        assertWireFits(who);
        assertThat(who).anyMatch(line -> line.contains(" 315 "));
        assertThat(logs.messages()).anyMatch(line -> line.contains("reply=352"));

        target.send("TOPIC " + channel);
        var topic = target.readThroughPong(TIMEOUT);
        assertWireFits(topic);
        assertThat(topic).noneMatch(line -> line.contains(" 333 "));
        assertThat(logs.messages()).anyMatch(line -> line.contains("reply=333"));

        member.send("INVITE " + targetNick + " " + channel);
        var inviting = member.readThroughPong(TIMEOUT);
        assertWireFits(inviting);
        assertThat(inviting).noneMatch(line -> line.contains(" 341 "));
        assertThat(logs.messages())
            .anyMatch(line -> line.contains("reply=341"))
            .anyMatch(line -> line.contains("reply=INVITE"));

        target.send("WHOIS " + memberNick);
        var whois = target.readThroughPong(TIMEOUT);
        assertWireFits(whois);
        assertThat(whois)
            .anyMatch(line -> line.contains(" 311 "))
            .anyMatch(line -> line.contains(" 312 "))
            .anyMatch(line -> line.contains(" 318 "));
        target.send("USERHOST " + memberNick);
        var userhost = target.readThroughPong(TIMEOUT);
        assertWireFits(userhost);
        assertThat(userhost).anyMatch(line -> line.contains(" 302 ") && line.contains(memberNick));

        member.send("QUIT :history");
        target.readUntil("QUIT", TIMEOUT);
        target.send("WHOWAS " + memberNick);
        var whowas = target.readThroughPong(TIMEOUT);
        assertWireFits(whowas);
        assertThat(whowas)
            .anyMatch(line -> line.contains(" 314 "))
            .anyMatch(line -> line.contains(" 369 "));
        assertThat(logs.messages())
            .isNotEmpty()
            .allSatisfy(
                line ->
                    assertThat(line)
                        .contains("Dropping oversized", "budget", "suppressedSinceLast="));
      }
    }
  }

  private static void assertWireFits(List<String> lines) {
    assertThat(lines)
        .isNotEmpty()
        .allSatisfy(
            line ->
                assertThat(line.getBytes(StandardCharsets.UTF_8).length + 2)
                    .as("wire bytes including CRLF")
                    .isLessThanOrEqualTo(512));
  }
}
