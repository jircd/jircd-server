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
import java.util.ArrayList;
import java.util.List;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;

/** Runs one command through a handler against an in-memory session and parses the wire output. */
final class HandlerHarness {

  final NicknameRegistry nicknames = new NicknameRegistry();

  ClientSession register(String nick) {
    ClientSession session = unregistered(nick);
    session.setNickname(nick);
    nicknames.claim(nick, session);
    session.setIdent(nick);
    assertThat(session.lifecycle().completeRegistration()).isTrue();
    return session;
  }

  static ClientSession unregistered(String id) {
    return new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
  }

  static List<Message> run(CommandHandler handler, ClientSession session, String line)
      throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
      session.attachWriter(writer);
      handler.handle(session, MessageParser.parse(line));
    }
    List<Message> replies = new ArrayList<>();
    for (String raw : out.toString(StandardCharsets.UTF_8).split("\r\n")) {
      if (!raw.isEmpty()) {
        assertThat(raw.getBytes(StandardCharsets.UTF_8).length + 2).isLessThanOrEqualTo(512);
        replies.add(MessageParser.parse(raw));
      }
    }
    return replies;
  }

  static List<String> codes(List<Message> replies) {
    return replies.stream().map(Message::rawCommand).toList();
  }
}
