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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.OutboundSizeGuard;
import net.jircd.core.session.Recipients;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.WhowasEntry;
import net.jircd.core.session.WhowasHistory;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class GrandfatheredReplyTest {
  private static final String SERVER = "test.jircd.local";
  private final HandlerHarness harness = new HandlerHarness();
  private final ChannelRegistry channels = new ChannelRegistry();
  private final ExtensionRegistry extensions = new ExtensionRegistry();

  @BeforeEach
  void resetWarnings() {
    Replies.resetOversizedWarnings();
    OutboundSizeGuard.resetWarnings();
  }

  private ClientSession session(String nick) {
    ClientSession session = harness.register(nick);
    session.setIdent("ident");
    session.setRealname("😀".repeat(300));
    return session;
  }

  @Test
  void whoSkipsImpossibleHeaderAndStillCloses() throws Exception {
    ClientSession member = session("n".repeat(150));
    String channel = "#" + "c".repeat(350);
    channels.join(channel, member, ignored -> true);
    try (Warnings warnings = new Warnings()) {
      var replies =
          HandlerHarness.run(
              new WhoCommandHandler(
                  channels, harness.nicknames, extensions, () -> SERVER, () -> true),
              session("requester"),
              "WHO " + channel);
      assertThat(HandlerHarness.codes(replies)).containsExactly("315");
      warnings.assertReply("352");
    }
  }

  @Test
  void whoisSkipsImpossibleIdentitiesButFitsOtherNickLinesAndCloses() throws Exception {
    ClientSession target = session("n".repeat(240));
    target.setAwayReason("😀".repeat(300));
    target.grantAdministratorPrivilege();
    try (Warnings warnings = new Warnings()) {
      var replies =
          HandlerHarness.run(
              new WhoisCommandHandler(harness.nicknames, extensions, () -> SERVER),
              session("r".repeat(240)),
              "WHOIS " + target.nickname());
      assertThat(HandlerHarness.codes(replies)).containsExactly("301", "313", "318");
      assertThat(replies)
          .isNotEmpty()
          .allSatisfy(reply -> assertThat(reply.params().get(1)).isEqualTo(target.nickname()));
      assertThat(replies.stream().filter(reply -> "301".equals(reply.rawCommand())))
          .singleElement()
          .satisfies(
              reply ->
                  assertThat(reply.params().getLast())
                      .isNotEmpty()
                      .satisfies(text -> assertThat(target.awayReason()).startsWith(text)));
      warnings.assertReply("311");
      warnings.assertReply("312");
    }
  }

  @Test
  void whowasSkipsImpossibleIdentityAndStillCloses() throws Exception {
    WhowasHistory history = new WhowasHistory(() -> 10);
    String nick = "n".repeat(240);
    history.record(
        new WhowasEntry(nick, "ident", "host.example", "host.example", "real", Instant.EPOCH));
    try (Warnings warnings = new Warnings()) {
      var replies =
          HandlerHarness.run(
              new WhowasCommandHandler(history, () -> SERVER),
              session("r".repeat(240)),
              "WHOWAS " + nick);
      assertThat(HandlerHarness.codes(replies)).containsExactly("369");
      warnings.assertReply("314");
    }
  }

  @Test
  void userhostSkipsIndivisibleEntryAndKeepsLaterEntry() throws Exception {
    ClientSession target = session("n".repeat(260));
    session("small");
    try (Warnings warnings = new Warnings()) {
      var replies =
          HandlerHarness.run(
              new UserhostCommandHandler(harness.nicknames, extensions, () -> SERVER),
              session("r".repeat(230)),
              "USERHOST " + target.nickname() + " small");
      assertThat(replies)
          .singleElement()
          .satisfies(
              reply -> {
                assertThat(reply.rawCommand()).isEqualTo("302");
                assertThat(reply.params().getLast()).isEqualTo("small=+ident@host.example");
              });
      warnings.assertReply("302");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"TOPIC", "JOIN"})
  void topicMetadataIsSkippedWhileTopicTextIsFitted(String command) throws Exception {
    String name = "#" + "c".repeat(350);
    var channel = channels.join(name, session("owner"), ignored -> true).orElseThrow().channel();
    channel.setTopic("😀".repeat(100), "n".repeat(150) + "!ident@host.example", 1700000000L);
    CommandHandler handler =
        command.equals("TOPIC")
            ? new TopicCommandHandler(channels, extensions, () -> SERVER, () -> 390)
            : new JoinCommandHandler(channels, extensions, () -> SERVER, () -> 351);
    try (Warnings warnings = new Warnings()) {
      var replies = HandlerHarness.run(handler, session("requester"), command + " " + name);
      assertThat(HandlerHarness.codes(replies)).contains("332").doesNotContain("333");
      assertThat(replies.stream().filter(reply -> "332".equals(reply.rawCommand())))
          .singleElement()
          .satisfies(
              reply ->
                  assertThat(reply.params().getLast())
                      .isNotEmpty()
                      .satisfies(text -> assertThat(channel.topic()).startsWith(text)));
      warnings.assertReply("333");
    }
  }

  @Test
  void inviteSkipsBothUnrepresentableLinesButRetainsInvitation() throws Exception {
    ClientSession inviter = session("n".repeat(150));
    ClientSession target = session("t".repeat(150));
    String name = "#" + "c".repeat(350);
    var channel = channels.join(name, inviter, ignored -> true).orElseThrow().channel();
    ByteArrayOutputStream targetOut = new ByteArrayOutputStream();
    try (Warnings warnings = new Warnings();
        SessionWriter writer = new SessionWriter(target, targetOut, TagRenderer.NONE)) {
      target.attachWriter(writer);
      var replies =
          HandlerHarness.run(
              new InviteCommandHandler(
                  channels, harness.nicknames, extensions, () -> SERVER, () -> 50),
              inviter,
              "INVITE " + target.nickname() + " " + name);
      assertThat(replies).isEmpty();
      warnings.assertReply("341");
      warnings.assertReply("INVITE");
    }
    assertThat(targetOut.size()).isZero();
    assertThat(channel.isInvited(target.nickname())).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"JOIN", "PART", "KICK", "MODE", "TOPIC", "NICK"})
  void broadcastsSkipWithOneSpecificWarningAndKeepDelivering(String command) throws Exception {
    ClientSession recipient = session("recipient");
    try (Warnings warnings = new Warnings()) {
      var replies =
          HandlerHarness.run(
              (session, ignored) -> {
                Message oversized =
                    new Message(
                        Map.of(),
                        "n".repeat(200) + "!ident@host.example",
                        null,
                        command,
                        List.of("#" + "c".repeat(350)));
                Recipients.deliver(List.of(session), oversized);
                Recipients.deliver(List.of(session), oversized);
                Recipients.deliver(List.of(session), Message.of(Command.PING, "alive"));
              },
              recipient,
              "PING test");
      assertThat(HandlerHarness.codes(replies)).containsExactly("PING");
      warnings.assertReply(command);
      assertThat(warnings.appender.list).hasSize(1);
    }
  }

  @Test
  void unknownLongWhoisTargetStillGetsFittedErrorAndCloser() throws Exception {
    String unknown = "u".repeat(240);
    var replies =
        HandlerHarness.run(
            new WhoisCommandHandler(harness.nicknames, extensions, () -> SERVER),
            session("r".repeat(240)),
            "WHOIS " + unknown);
    assertThat(HandlerHarness.codes(replies)).containsExactly("401", "318");
    assertThat(replies)
        .isNotEmpty()
        .allSatisfy(reply -> assertThat(reply.params().get(1)).isEqualTo(unknown));
    assertThat(replies.getLast().params().getLast())
        .isNotEmpty()
        .matches("End of /WHOIS list"::startsWith);
  }

  @Test
  void unknownLongWhowasTargetStillGetsFittedErrorAndCloser() throws Exception {
    String unknown = "u".repeat(240);
    var replies =
        HandlerHarness.run(
            new WhowasCommandHandler(new WhowasHistory(() -> 10), () -> SERVER),
            session("r".repeat(240)),
            "WHOWAS " + unknown);
    assertThat(HandlerHarness.codes(replies)).containsExactly("406", "369");
    assertThat(replies)
        .isNotEmpty()
        .allSatisfy(reply -> assertThat(reply.params().get(1)).isEqualTo(unknown));
  }

  @ParameterizedTest
  @ValueSource(strings = {"PRIVMSG", "NOTICE"})
  void directMessageFitsAwayReasonWithoutLosingItsText(String command) throws Exception {
    ClientSession target = session("n".repeat(240));
    target.setAwayReason("😀".repeat(300));
    var replies =
        HandlerHarness.run(
            new MessageCommandHandler(
                channels, harness.nicknames, extensions, () -> SERVER, command.equals("NOTICE")),
            session("r".repeat(240)),
            command + " " + target.nickname() + " :hello");
    assertThat(replies)
        .singleElement()
        .satisfies(
            reply -> {
              assertThat(reply.rawCommand()).isEqualTo("301");
              assertThat(reply.params().get(1)).isEqualTo(target.nickname());
              assertThat(reply.params().getLast())
                  .isNotEmpty()
                  .satisfies(text -> assertThat(target.awayReason()).startsWith(text));
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"PRIVMSG", "NOTICE"})
  void textFitFailureReportsThatNoCompleteCodePointFits(String command) throws Exception {
    String prefix = "sender!ident@host.example";
    Message empty = new Message(Map.of(), prefix, null, command, List.of("r", ""));
    String target = "r".repeat(1 + 510 - MessageSerializer.commandBytes(empty));
    try (Warnings warnings = new Warnings()) {
      var replies =
          HandlerHarness.run(
              (session, ignored) -> {
                session
                    .writer()
                    .enqueueRaw(
                        new Message(
                            Map.of(), prefix, null, command, List.of(target, "😀".repeat(100))));
                session.writer().enqueueRaw(Message.of(Command.PING, "alive"));
              },
              session("recipient"),
              "PING test");
      assertThat(HandlerHarness.codes(replies)).containsExactly("PING");
      assertThat(warnings.appender.list)
          .singleElement()
          .satisfies(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains(
                        "reply=" + command,
                        "reason=text has no room for a complete code point",
                        "fixedFieldsBytes=510",
                        "budget=512")
                    .doesNotContain("fields exceed command budget");
              });
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"PRIVMSG", "NOTICE"})
  void textWithOversizedFixedFieldsUsesTheFixedFieldDiagnosis(String command) throws Exception {
    Message oversized =
        new Message(
            Map.of(),
            "sender!ident@host.example",
            null,
            command,
            List.of("r".repeat(600), "😀".repeat(100)));
    try (Warnings warnings = new Warnings()) {
      var replies =
          HandlerHarness.run(
              (session, ignored) -> session.writer().enqueueRaw(oversized),
              session("recipient"),
              "PING test");
      assertThat(replies).isEmpty();
      assertThat(warnings.appender.list)
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.getFormattedMessage())
                      .contains("reply=" + command, "reason=fields exceed command budget")
                      .doesNotContain("reason=text"));
    }
  }

  @Test
  void aFittableTextBodyDoesNotHideAnOversizedTagFailure() throws Exception {
    Message oversized =
        new Message(
            Map.of("s", "x".repeat(4093)),
            "sender!ident@host.example",
            Command.PRIVMSG,
            "PRIVMSG",
            List.of("target", "😀".repeat(300)));
    try (Warnings warnings = new Warnings()) {
      var replies =
          HandlerHarness.run(
              (session, ignored) -> session.writer().enqueueRaw(oversized),
              session("recipient"),
              "PING test");
      assertThat(replies).isEmpty();
      assertThat(warnings.appender.list)
          .singleElement()
          .satisfies(
              event ->
                  assertThat(event.getFormattedMessage())
                      .contains("Outbound server tags exceed 4094 data bytes")
                      .doesNotContain("fields exceed command budget", "reason=text"));
    }
  }

  private static final class Warnings implements AutoCloseable {
    private final List<Logger> loggers =
        List.of(
            (Logger) LoggerFactory.getLogger(Replies.class),
            (Logger) LoggerFactory.getLogger(OutboundSizeGuard.class),
            (Logger) LoggerFactory.getLogger(SessionWriter.class));
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    Warnings() {
      appender.start();
      loggers.forEach(logger -> logger.addAppender(appender));
    }

    void assertReply(String code) {
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                    .contains("Dropping oversized", "=" + code, "budget");
              });
    }

    @Override
    public void close() {
      loggers.forEach(logger -> logger.detachAppender(appender));
      appender.stop();
    }
  }
}
