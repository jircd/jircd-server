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
package net.jircd.capabilities.messagetags;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import net.jircd.core.session.OutboundMessage;
import org.junit.jupiter.api.Test;

class MessageTagsExtensionPurityTest {
  @Test
  void contributionDependsOnlyOnTheMessage() {
    var extension = new MessageTagsExtension();
    var message =
        new OutboundMessage(
            "n!u@h",
            "PRIVMSG",
            "#c",
            "hello",
            Map.of(),
            Instant.parse("2020-01-02T03:04:05.006Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000123"));
    var equal =
        new OutboundMessage(
            message.senderPresentedForm(),
            message.command(),
            message.target(),
            message.body(),
            message.clientTags(),
            message.sentAt(),
            message.messageId());
    for (int i = 0; i < 10; i++) {
      assertThat(extension.contributeTags(message))
          .isEqualTo(Map.of("msgid", message.messageId().toString()));
      assertThat(new MessageTagsExtension().contributeTags(equal))
          .isEqualTo(extension.contributeTags(message));
    }
  }
}
