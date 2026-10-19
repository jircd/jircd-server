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

import java.nio.file.Files;
import java.time.Duration;
import net.jircd.core.session.command.Replies;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReplyBudgetReloadTest {
  @BeforeEach
  void forgetEarlierWarnings() {
    Replies.resetOversizedWarnings();
  }

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @Test
  void loweredNicknameLimitGrandfathersMembersAndTheGuardHandlesNewLargerChannels()
      throws Exception {
    try (TestServer server =
            TestServer.start("nicknameMaxLength: 200\nchannelNameMaxLength: 50\n");
        RawIrcClient member = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient probe = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      String nickname = "n".repeat(200);
      member.registerAndAwaitWelcome(nickname, "member");
      root.registerAndAwaitWelcome("root", "root");
      member.send("JOIN #old");
      member.readUntil("366", TIMEOUT);
      Files.writeString(
          server.configPath,
          TestServer.baseYaml() + "nicknameMaxLength: 9\nchannelNameMaxLength: 400\n");
      server.application.reloader().reload();
      assertThat(server.application.nicknameRegistry().lookup(nickname)).isPresent();
      assertThat(server.application.channelRegistry().lookup("#old").orElseThrow().memberListing())
          .containsExactly("@" + nickname);
      probe.send("NICK " + nickname + "x");
      assertThat(probe.readUntil("432", TIMEOUT)).contains("Erroneous nickname");
      root.send("NICK " + "r".repeat(10));
      assertThat(root.readUntil("432", TIMEOUT)).contains("Erroneous nickname");
      String channel = "#" + "c".repeat(399);
      root.send("JOIN " + channel);
      root.readUntil("366", TIMEOUT);
      member.send("JOIN " + channel);
      member.readThroughPong(TIMEOUT);
      try (LogCapture logs = new LogCapture(Replies.class)) {
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
            .contains("Dropping oversized listed item", channel, "itemBytes=200", "budget=80");
      }
      assertThat(server.application.channelRegistry().lookup(channel).orElseThrow().memberListing())
          .contains(nickname, "@root");
    }
  }

  @Test
  void loweredChannelLimitKeepsExistingChannelAndBareNamesStillAttemptsItsCloser()
      throws Exception {
    try (TestServer server = TestServer.start("channelNameMaxLength: 400\n");
        RawIrcClient member = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient requester = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      member.registerAndAwaitWelcome("member", "member");
      String channel = "#" + "c".repeat(399);
      member.send("JOIN " + channel);
      member.readUntil("366", TIMEOUT);
      var original = server.application.channelRegistry().lookup(channel).orElseThrow();
      Files.writeString(
          server.configPath,
          TestServer.baseYaml() + "nicknameMaxLength: 200\nchannelNameMaxLength: 50\n");
      server.application.reloader().reload();
      assertThat(server.application.channelRegistry().lookup(channel)).containsSame(original);
      assertThat(original.memberListing()).containsExactly("@member");
      requester.registerAndAwaitWelcome("r".repeat(200), "requester");
      try (LogCapture logs = new LogCapture(Replies.class)) {
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
}
