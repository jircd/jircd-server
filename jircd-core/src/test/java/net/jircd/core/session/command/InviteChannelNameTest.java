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
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/** An INVITE to a nonexistent channel validates the name as JOIN does before sending anything. */
class InviteChannelNameTest {

  private final ByteArrayOutputStream inviterOut = new ByteArrayOutputStream();
  private final ByteArrayOutputStream targetOut = new ByteArrayOutputStream();
  private final ClientSession inviter = session("inviter", inviterOut);
  private final ClientSession target = session("target", targetOut);
  private final NicknameRegistry nicknames = new NicknameRegistry();
  private final InviteCommandHandler handler =
      new InviteCommandHandler(
          new ChannelRegistry(),
          nicknames,
          new ExtensionRegistry(),
          () -> "irc.example.net",
          () -> 50);

  private static ClientSession session(String nick, ByteArrayOutputStream sink) {
    ClientSession session = new ClientSession(nick, "203.0.113.7", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent("ident");
    session.attachWriter(new SessionWriter(session, sink, TagRenderer.NONE));
    return session;
  }

  private String invite(String channel, ByteArrayOutputStream out) throws Exception {
    nicknames.claim("inviter", inviter);
    nicknames.claim("target", target);
    handler.handle(inviter, MessageParser.parse("INVITE target " + channel));
    inviter.writer().close();
    target.writer().close();
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void anOverlongNameGets476AndNothingIsSentToTheTarget() throws Exception {
    String reply = invite("#" + "c".repeat(450), inviterOut);

    assertThat(reply)
        .contains(" 476 inviter ")
        .contains("Bad Channel Mask")
        .doesNotContain(" 341 ");
    for (String line : reply.split("\r\n")) {
      assertThat(line.getBytes(StandardCharsets.UTF_8).length + 2).isLessThanOrEqualTo(512);
    }
    assertThat(targetOut.size()).isZero();
  }

  @Test
  void aNameWithoutAChannelPrefixGets476() throws Exception {
    assertThat(invite("nochannel", inviterOut)).contains(" 476 inviter ").doesNotContain(" 341 ");
    assertThat(targetOut.size()).isZero();
  }

  @Test
  void aValidNameAtTheLimitStillInvites() throws Exception {
    String channel = "#" + "c".repeat(49);

    assertThat(invite(channel, inviterOut)).contains(" 341 inviter target " + channel);
    assertThat(targetOut.toString(StandardCharsets.UTF_8)).contains(" INVITE target " + channel);
  }
}
