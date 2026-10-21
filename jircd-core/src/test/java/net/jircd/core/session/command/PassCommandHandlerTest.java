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

final class PassCommandHandlerTest {

  private final PassCommandHandler handler = new PassCommandHandler(() -> "server");

  @Test
  void beforeRegistrationItIsAcceptedSilently() throws Exception {
    ClientSession session = HandlerHarness.unregistered("c1");

    assertThat(HandlerHarness.run(handler, session, "PASS secret")).isEmpty();
  }

  @Test
  void afterRegistrationItIsAlreadyRegistered() throws Exception {
    ClientSession alice = new HandlerHarness().register("alice");

    List<Message> replies = HandlerHarness.run(handler, alice, "PASS secret");

    assertThat(replies).hasSize(1);
    assertThat(replies.getFirst().rawCommand()).isEqualTo("462");
    assertThat(replies.getFirst().params()).containsExactly("alice", "You may not reregister");
  }

  @Test
  void withoutAParameterItNeedsMoreParams() throws Exception {
    ClientSession session = HandlerHarness.unregistered("c1");

    List<Message> replies = HandlerHarness.run(handler, session, "PASS");

    assertThat(replies.getFirst().rawCommand()).isEqualTo("461");
    assertThat(replies.getFirst().params()).containsExactly("*", "PASS", "Not enough parameters");
  }
}
