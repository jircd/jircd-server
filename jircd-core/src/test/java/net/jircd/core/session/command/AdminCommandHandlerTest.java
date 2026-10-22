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
import net.jircd.core.config.ServerConfiguration.AdminContact;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

final class AdminCommandHandlerTest {

  private final ClientSession alice = new HandlerHarness().register("alice");

  private static AdminCommandHandler handler(AdminContact contact) {
    return new AdminCommandHandler(() -> "server.example", () -> contact);
  }

  @Test
  void configuredContactIsSent256Through259() throws Exception {
    AdminContact contact = new AdminContact("Jane Admin", "Example Org, Earth", "jane@example.org");

    List<Message> replies = HandlerHarness.run(handler(contact), alice, "ADMIN");

    assertThat(HandlerHarness.codes(replies)).containsExactly("256", "257", "258", "259");
    assertThat(replies.get(0).params())
        .containsExactly("alice", "server.example", "Administrative info about server.example");
    assertThat(replies.get(1).params()).containsExactly("alice", "Jane Admin");
    assertThat(replies.get(2).params()).containsExactly("alice", "Example Org, Earth");
    assertThat(replies.get(3).params()).containsExactly("alice", "jane@example.org");
  }

  @Test
  void anUnsetLineIsADash() throws Exception {
    List<Message> replies =
        HandlerHarness.run(handler(new AdminContact(null, null, "a@b.c")), alice, "ADMIN");

    assertThat(replies.get(1).params().getLast()).isEqualTo("-");
    assertThat(replies.get(2).params().getLast()).isEqualTo("-");
    assertThat(replies.get(3).params().getLast()).isEqualTo("a@b.c");
  }

  @Test
  void noContactAtAllIsNoAdminInfo() throws Exception {
    List<Message> replies =
        HandlerHarness.run(handler(new AdminContact(null, null, null)), alice, "ADMIN");

    assertThat(replies).hasSize(1);
    assertThat(replies.getFirst().rawCommand()).isEqualTo("423");
    assertThat(replies.getFirst().params())
        .containsExactly("alice", "server.example", "No administrative info available");
  }

  @Test
  void anotherServerIsNoSuchServer() throws Exception {
    List<Message> replies =
        HandlerHarness.run(handler(new AdminContact("a", "b", "c")), alice, "ADMIN other.example");

    assertThat(HandlerHarness.codes(replies)).containsExactly("402");
  }

  @Test
  void ownServerNameIsAccepted() throws Exception {
    List<Message> replies =
        HandlerHarness.run(handler(new AdminContact("a", "b", "c")), alice, "ADMIN SERVER.example");

    assertThat(HandlerHarness.codes(replies)).containsExactly("256", "257", "258", "259");
  }

  @Test
  void overlongLinesAreCutToFit() throws Exception {
    String huge = "x".repeat(2000);

    List<Message> replies =
        HandlerHarness.run(handler(new AdminContact(huge, huge, huge)), alice, "ADMIN");

    assertThat(replies).hasSize(4); // run() asserts each line fits 512 bytes
  }
}
