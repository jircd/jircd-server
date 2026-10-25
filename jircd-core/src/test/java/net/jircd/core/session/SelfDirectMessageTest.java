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
package net.jircd.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.DeliveryRole;
import net.jircd.core.session.command.MessageCommandHandler;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Issue #18: a direct message to one's own nick is delivered exactly once, echo-message or not. */
class SelfDirectMessageTest {

  /** Stand-in for echo-message, which lives in a module core cannot depend on. */
  private static final class EchoStub extends AbstractCapabilityExtension {
    EchoStub() {
      super("echo-message");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onRecipients((intent, context) -> Set.of(DeliveryRole.SENDER_COPY));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "PRIVMSG, false",
    "PRIVMSG, true",
    "NOTICE, false",
    "NOTICE, true",
  })
  void selfTargetedMessageIsDeliveredExactlyOnce(String command, boolean echoNegotiated)
      throws Exception {
    ClientSession alice = new ClientSession("a", "host.example", RateLimitBucket.withDefaults());
    alice.setNickname("alice");
    alice.setIdent("ident");
    if (echoNegotiated) {
      TestNegotiation.add(alice, "echo-message");
    }
    NicknameRegistry nicknames = new NicknameRegistry();
    assertThat(nicknames.claim("alice", alice)).isTrue();
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new EchoStub());
    extensions.enable("echo-message");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(alice, out, TagRenderer.NONE)) {
      alice.attachWriter(writer);
      new MessageCommandHandler(
              new ChannelRegistry(),
              nicknames,
              extensions,
              () -> "test.jircd.local",
              command.equals("NOTICE"))
          .handle(alice, MessageParser.parse(command + " alice :hello"));
    }

    assertThat(out.toString(StandardCharsets.UTF_8))
        .isEqualTo(":alice!ident@host.example " + command + " alice hello\r\n");
  }
}
