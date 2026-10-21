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

final class IsonCommandHandlerTest {

  private final HandlerHarness harness = new HandlerHarness();
  private final IsonCommandHandler handler =
      new IsonCommandHandler(harness.nicknames, () -> "server");

  @Test
  void listsOnlineNicknamesInThePresentedFormAndCaseFoldedLookup() throws Exception {
    ClientSession alice = harness.register("Alice");
    harness.register("Bob");

    List<Message> replies = HandlerHarness.run(handler, alice, "ISON alice BOB carol");

    assertThat(replies).hasSize(1);
    assertThat(replies.getFirst().rawCommand()).isEqualTo("303");
    assertThat(replies.getFirst().params()).containsExactly("Alice", "Alice Bob");
  }

  @Test
  void acceptsTheNicknamesAsOneSpaceSeparatedTrailingParameter() throws Exception {
    ClientSession alice = harness.register("alice");
    harness.register("bob");

    List<Message> replies = HandlerHarness.run(handler, alice, "ISON :bob nobody alice");

    assertThat(replies.getFirst().params().getLast()).isEqualTo("bob alice");
  }

  @Test
  void anEmptyResultIsStillAReply() throws Exception {
    ClientSession alice = harness.register("alice");

    List<Message> replies = HandlerHarness.run(handler, alice, "ISON ghost");

    assertThat(replies.getFirst().params()).containsExactly("alice", "");
  }

  @Test
  void nicknameHoldersThatHaveNotRegisteredAreNotOnline() throws Exception {
    ClientSession alice = harness.register("alice");
    ClientSession pending = HandlerHarness.unregistered("pending");
    pending.setNickname("pending");
    harness.nicknames.claim("pending", pending);

    List<Message> replies = HandlerHarness.run(handler, alice, "ISON pending");

    assertThat(replies.getFirst().params().getLast()).isEmpty();
  }

  @Test
  void noParametersIsNeedMoreParams() throws Exception {
    ClientSession alice = harness.register("alice");

    List<Message> replies = HandlerHarness.run(handler, alice, "ISON");

    assertThat(replies.getFirst().rawCommand()).isEqualTo("461");
    assertThat(replies.getFirst().params()).contains("ISON");
  }

  @Test
  void aLongListSplitsIntoLinesThatFit() throws Exception {
    ClientSession alice = harness.register("alice");
    StringBuilder line = new StringBuilder("ISON");
    for (int i = 0; i < 200; i++) {
      String nick = "user" + i;
      harness.register(nick);
      line.append(' ').append(nick);
    }

    List<Message> replies = HandlerHarness.run(handler, alice, line.toString());

    assertThat(HandlerHarness.codes(replies)).allMatch("303"::equals);
  }
}
