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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.OutboundSizeGuard;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.command.JoinCommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import net.jircd.protocol.MessageSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamesByteBudgetTest {
  private static final String SERVER = "test.jircd.local";
  private static final String CHANNEL = "#ébudget";

  @BeforeEach
  void forgetEarlierWarnings() {
    Replies.resetOversizedWarnings();
    OutboundSizeGuard.resetWarnings();
  }

  @Test
  void repeatedNamesLogOneWarningPerChannelAndFailureTypeAndCountTheRest() throws Exception {
    String oversized = "x".repeat(600);
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      for (int i = 0; i < 5; i++) {
        send("requester", CHANNEL, List.of(oversized));
      }
      send("requester", "#other", List.of(oversized));
      assertThat(logs.messages()).hasSize(2);
      assertThat(logs.messages().get(0))
          .contains(
              "Dropping oversized listed item", CHANNEL, "itemBytes=600", "suppressedSinceLast=0");
      assertThat(logs.messages().get(1)).contains("#other");
    }
  }

  private static int budget(String nickname, String channel) {
    return 512
        - MessageSerializer.commandBytes(
            new Message(Map.of(), SERVER, null, "353", List.of(nickname, "=", channel, "")));
  }

  private static List<Message> send(String nickname, String channel, List<String> items)
      throws Exception {
    ClientSession requester =
        new ClientSession("requester", "host.example", RateLimitBucket.withDefaults());
    requester.setNickname(nickname);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(requester, out, TagRenderer.NONE)) {
      requester.attachWriter(writer);
      JoinCommandHandler.sendNamesReply(requester, new Channel(channel), SERVER, items);
      writer.enqueueRaw(Message.of(Command.PING, "after-names"));
    }
    List<Message> messages = new ArrayList<>();
    for (String line : out.toString(StandardCharsets.UTF_8).split("\r\n")) {
      Message message = MessageParser.parse(line);
      assertThat(MessageSerializer.commandBytes(message)).isLessThanOrEqualTo(512);
      messages.add(message);
    }
    return messages;
  }

  @Test
  void reservedBudgetPlusOneFitsIntactAndPlusTwoIsSkippedBeforeSerialization() throws Exception {
    int budget = budget("requester", CHANNEL);
    String fitting = "x".repeat(budget + 1);
    String oversized = "y".repeat(budget + 2);
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      List<Message> messages = send("requester", CHANNEL, List.of(fitting, oversized, "later"));
      assertThat(
              messages.stream()
                  .filter(m -> "353".equals(m.rawCommand()))
                  .map(m -> m.params().getLast()))
          .containsExactly(fitting, "later");
      assertThat(logs.messages())
          .singleElement()
          .asString()
          .contains(
              "Dropping oversized listed item",
              CHANNEL,
              "itemBytes=" + (budget + 2),
              "budget=" + (budget + 1));
      assertThat(messages.stream().map(Message::rawCommand))
          .containsExactly("353", "353", "366", "PING");
    }
  }

  @Test
  void actualSingletonFitAccountsForARequiredTrailingColon() throws Exception {
    int budget = budget("requester", CHANNEL);
    String item = ":" + "x".repeat(budget);
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      assertThat(
              send("requester", CHANNEL, List.of(item, "later")).stream()
                  .filter(m -> "353".equals(m.rawCommand()))
                  .map(m -> m.params().getLast()))
          .containsExactly("later");
      assertThat(logs.messages())
          .singleElement()
          .asString()
          .contains("itemBytes=" + (budget + 1), "budget=" + budget);
    }
  }

  @Test
  void anExactlyFullReservedHeaderStillAllowsOneColonlessByte() throws Exception {
    String channel = "#" + "x".repeat(budget("requester", "#"));
    assertThat(budget("requester", channel)).isZero();
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      var replies = send("requester", channel, List.of("x"));
      assertThat(replies.stream().map(Message::rawCommand)).containsExactly("353", "366", "PING");
      assertFittedCloser(replies);
      assertThat(logs.messages()).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void oversizedFirstMiddleAndLastItemsPreserveNeighborsAndCloser(int position) throws Exception {
    List<String> items = new ArrayList<>(List.of("before", "after"));
    items.add(position, "x".repeat(budget("requester", CHANNEL) + 2));
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      List<Message> messages = send("requester", CHANNEL, items);
      assertThat(
              messages.stream()
                  .filter(m -> "353".equals(m.rawCommand()))
                  .flatMap(m -> List.of(m.params().getLast().split(" ")).stream()))
          .containsExactly("before", "after");
      assertThat(messages.stream().map(Message::rawCommand)).containsSubsequence("366", "PING");
      assertThat(logs.messages())
          .singleElement()
          .asString()
          .contains("Dropping oversized listed item");
    }
  }

  @Test
  void aListContainingOnlyAnOversizedItemDoesNotInventAnEmptyNamesReply() throws Exception {
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      assertThat(
              send("requester", CHANNEL, List.of("x".repeat(600))).stream()
                  .map(Message::rawCommand))
          .containsExactly("366", "PING");
      assertThat(logs.messages()).singleElement().asString().contains("itemBytes=600");
    }
  }

  @Test
  void anUnrepresentableHeaderAndItsCloserAreDiagnosedSeparately() throws Exception {
    String channel = "#" + "x".repeat(490);
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      assertThat(send("requester", channel, List.of("small")).stream().map(Message::rawCommand))
          .containsExactly("PING");
      assertThat(logs.messages())
          .anySatisfy(
              line ->
                  assertThat(line)
                      .contains("Dropping oversized listed reply header", channel, "headerBytes="))
          .anySatisfy(
              line ->
                  assertThat(line).contains("Dropping oversized reply", "reply=366", "budget=512"));
    }
  }

  @Test
  void aLongGrandfatheredRequesterCanExhaustAnOtherwiseOrdinaryItemsBudget() throws Exception {
    String requester = "r".repeat(400);
    String channel = "#" + "c".repeat(75);
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      assertThat(
              send(requester, channel, List.of("@" + "n".repeat(10), "ok")).stream()
                  .map(Message::rawCommand))
          .containsExactly("353", "366", "PING");
      assertThat(logs.messages())
          .singleElement()
          .asString()
          .contains("Dropping oversized listed item", "itemBytes=11", "budget=8");
    }
  }

  @Test
  void aFitting353AlsoGetsAFitted366WhenOnlyTheClosingTextExceedsTheBudget() throws Exception {
    String channel = "#" + "界".repeat(153);
    try (LogCapture logs = new LogCapture(Replies.class, OutboundSizeGuard.class)) {
      var replies = send("requester", channel, List.of("@nickname"));
      assertThat(replies.stream().map(Message::rawCommand)).containsExactly("353", "366", "PING");
      assertFittedCloser(replies);
      assertThat(logs.messages()).isEmpty();
    }
  }

  private static void assertFittedCloser(List<Message> replies) {
    assertThat(replies.stream().filter(reply -> "366".equals(reply.rawCommand())))
        .singleElement()
        .satisfies(
            reply ->
                assertThat(reply.params().getLast())
                    .isNotEmpty()
                    .matches("End of /NAMES list"::startsWith));
  }
}
