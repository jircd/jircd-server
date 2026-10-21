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

import java.util.List;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

final class HelpCommandHandlerTest {

  private final HelpCommandHandler handler = new HelpCommandHandler(() -> "server.example");
  private final ClientSession alice = new HandlerHarness().register("alice");

  @Test
  void withoutATopicItListsTheTopicIndex() throws Exception {
    List<Message> replies = HandlerHarness.run(handler, alice, "HELP");

    assertThat(replies.getFirst().rawCommand()).isEqualTo("704");
    assertThat(replies.getFirst().params().get(1)).isEqualTo("index");
    assertThat(replies.subList(1, replies.size() - 1)).allMatch(m -> m.rawCommand().equals("705"));
    assertThat(replies.getLast().rawCommand()).isEqualTo("706");
    assertThat(replies.getLast().params().get(1)).isEqualTo("index");
    assertThat(replies.stream().map(m -> m.params().getLast()))
        .anyMatch(text -> text.contains("MOTD") && text.contains("STATS"));
  }

  @Test
  void aKnownTopicIsStartTextEndWithTheTopicAsSubject() throws Exception {
    List<Message> replies = HandlerHarness.run(handler, alice, "HELP stats");

    assertThat(HandlerHarness.codes(replies).getFirst()).isEqualTo("704");
    assertThat(HandlerHarness.codes(replies).getLast()).isEqualTo("706");
    assertThat(replies).allSatisfy(m -> assertThat(m.params().get(1)).isEqualTo("STATS"));
    assertThat(replies.getFirst().params().getLast()).contains("STATS");
  }

  @Test
  void helpopBehavesTheSame() throws Exception {
    List<Message> help = HandlerHarness.run(handler, alice, "HELP join");
    List<Message> helpop = HandlerHarness.run(handler, alice, "HELPOP join");

    assertThat(helpop).isEqualTo(help);
  }

  @Test
  void anUnknownTopicIsHelpNotFound() throws Exception {
    List<Message> replies = HandlerHarness.run(handler, alice, "HELP nonsense");

    assertThat(replies).hasSize(1);
    assertThat(replies.getFirst().rawCommand()).isEqualTo("524");
    assertThat(replies.getFirst().params()).containsExactly("alice", "nonsense", "Help not found");
  }

  @Test
  void anOverlongUnknownTopicIsEchoedTruncatedSoTheReplyIsNotDropped() throws Exception {
    List<Message> replies = HandlerHarness.run(handler, alice, "HELP " + "x".repeat(2000));

    assertThat(HandlerHarness.codes(replies)).containsExactly("524");
  }

  @Test
  void aTopicStartingWithAColonIsEchoedWithoutIt() throws Exception {
    List<Message> replies = HandlerHarness.run(handler, alice, "HELP ::x");

    assertThat(replies.getFirst().rawCommand()).isEqualTo("524");
    assertThat(replies.getFirst().params()).containsExactly("alice", "_x", "Help not found");
  }

  @Test
  void anEmptyTopicIsTheIndexSoItIsNeverEchoedEmpty() throws Exception {
    assertThat(HandlerHarness.codes(HandlerHarness.run(handler, alice, "HELP :")).getFirst())
        .isEqualTo("704");
  }

  @Test
  void aLeadingSlashOrQuestionMarkIsTolerated() throws Exception {
    assertThat(HandlerHarness.codes(HandlerHarness.run(handler, alice, "HELP ?")).getFirst())
        .isEqualTo("704");
  }
}
