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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/**
 * Batch groups across runtime changes, over real sockets with the discovered batch provider. A
 * group admitted before a disable keeps its close; a group admitted after message-tags stops being
 * effective for its recipient is plain while batch stays negotiated; a recipient that cannot hold
 * the group is closed alone. DEL is not an output barrier, so no test orders it against a group.
 */
class CapabilityGroupLifecycleTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final String YAML =
      """
      capabilities:
        batch: enabled
        message-tags: enabled
      sendqLimitBytes: 16384
      """;

  private static Message privmsg(String body) {
    return new Message(
        Map.of(), "bot!bot@host", Command.PRIVMSG, "PRIVMSG", List.of("#probe", body));
  }

  private static RawIrcClient client(TestServer server, String nick, String caps) throws Exception {
    RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
    client.send("CAP LS 302");
    if (!caps.isEmpty()) client.send("CAP REQ :" + caps);
    client.send("CAP END");
    client.registerAndAwaitWelcome(nick, nick);
    return client;
  }

  private static ClientSession live(TestServer server, String nick) {
    return server.application.nicknameRegistry().lookup(nick).orElseThrow();
  }

  private static List<String> probe(RawIrcClient client) throws Exception {
    return client.readThroughPong(TIMEOUT).stream()
        .filter(l -> l.contains("PRIVMSG #probe") || l.contains("BATCH"))
        .toList();
  }

  private static void assertFramed(List<String> lines, String... bodies) {
    assertThat(lines).hasSize(bodies.length + 2);
    assertThat(lines.getFirst()).startsWith("BATCH +");
    String ref = lines.getFirst().substring("BATCH +".length(), lines.getFirst().indexOf(' ', 7));
    for (int i = 0; i < bodies.length; i++) {
      assertThat(lines.get(i + 1))
          .startsWith("@batch=" + ref)
          .endsWith("PRIVMSG #probe " + bodies[i]);
    }
    assertThat(lines.getLast()).isEqualTo("BATCH -" + ref);
  }

  @Test
  void aGroupAdmittedBeforeTheDisableIsDeliveredWholeWithItsClose() throws Exception {
    try (TestServer server = TestServer.start(YAML);
        RawIrcClient alice = client(server, "alice", "batch message-tags")) {
      ExtensionRegistry registry = server.application.extensionRegistry();
      live(server, "alice")
          .writer()
          .enqueueGroup("probe", List.of(), List.of(privmsg("one"), privmsg("two")));
      registry.disable("batch");

      assertFramed(probe(alice), "one", "two");
    }
  }

  @Test
  void afterMessageTagsIsDisabledANewGroupIsPlainWhileBatchStaysNegotiated() throws Exception {
    try (TestServer server = TestServer.start(YAML);
        RawIrcClient alice = client(server, "alice", "batch message-tags")) {
      ExtensionRegistry registry = server.application.extensionRegistry();
      ClientSession session = live(server, "alice");

      registry.disable("message-tags");
      session.writer().enqueueGroup("probe", List.of(), List.of(privmsg("plain")));
      assertThat(probe(alice)).containsExactly(":bot!bot@host PRIVMSG #probe plain");
      alice.send("CAP LIST");
      assertThat(alice.readUntil("CAP alice LIST", TIMEOUT)).endsWith("LIST batch");

      // Enabling message-tags restores nothing: framing returns only after a new request.
      registry.enable("message-tags");
      session.writer().enqueueGroup("probe", List.of(), List.of(privmsg("still")));
      assertThat(probe(alice)).containsExactly(":bot!bot@host PRIVMSG #probe still");
      alice.send("CAP REQ :message-tags");
      alice.readUntil("CAP alice ACK", TIMEOUT);
      session.writer().enqueueGroup("probe", List.of(), List.of(privmsg("framed")));
      assertFramed(probe(alice), "framed");
    }
  }

  @Test
  void aRecipientThatCannotHoldTheGroupIsClosedAloneAndOthersStillGetIt() throws Exception {
    try (TestServer server = TestServer.start(YAML);
        RawIrcClient alice = client(server, "alice", "batch message-tags");
        RawIrcClient bob = client(server, "bob", "batch message-tags")) {
      List<Message> large = new ArrayList<>();
      for (int i = 0; i < 60; i++) large.add(privmsg("x".repeat(400)));
      live(server, "bob").writer().enqueueGroup("probe", List.of(), large);
      assertThat(bob.readUntil("ERROR", TIMEOUT)).contains("SendQ exceeded");

      live(server, "alice").writer().enqueueGroup("probe", List.of(), List.of(privmsg("one")));
      assertFramed(probe(alice), "one");
    }
  }
}
