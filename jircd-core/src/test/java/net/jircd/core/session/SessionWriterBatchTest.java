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
import java.util.List;
import java.util.Map;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/** 011-away-notify-batch User Story 2: {@link SessionWriter#enqueueBatch}. */
class SessionWriterBatchTest {

  private static ClientSession newSession() {
    return new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
  }

  private static Message privmsg(String target, String body) {
    return new Message(
        Map.of(), "sender!ident@host", Command.PRIVMSG, "PRIVMSG", List.of(target, body));
  }

  private static String[] lines(ByteArrayOutputStream out) {
    String text = out.toString(StandardCharsets.UTF_8);
    return text.isEmpty() ? new String[0] : text.split("\r\n");
  }

  private static String tagValue(String line, String key) {
    assertThat(line).startsWith("@");
    String tagSection = line.substring(1, line.indexOf(' '));
    for (String tag : tagSection.split(";")) {
      int eq = tag.indexOf('=');
      if (eq > 0 && tag.substring(0, eq).equals(key)) {
        return tag.substring(eq + 1);
      }
    }
    throw new AssertionError("Tag '" + key + "' not found in: " + line);
  }

  @Test
  void twoConcurrentBatchesAreDistinctAndCorrectlyFramed() throws Exception {
    ClientSession session = newSession();
    session.negotiatedCapabilities().add(CapabilityName.BATCH);
    session.negotiatedCapabilities().add(CapabilityName.MESSAGE_TAGS);
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
      writer.enqueueBatch(
          "type-a", List.of(), List.of(privmsg("#chan", "a1"), privmsg("#chan", "a2")));
      writer.enqueueBatch("type-b", List.of("param1"), List.of(privmsg("#chan", "b1")));
    }

    String[] lines = lines(out);
    assertThat(lines).hasSize(7);

    // Group A: open, two tagged members, close.
    assertThat(lines[0]).startsWith("BATCH +").contains(" type-a");
    int prefixLenA = "BATCH +".length();
    String refA = lines[0].substring(prefixLenA, lines[0].indexOf(' ', prefixLenA));
    assertThat(tagValue(lines[1], "batch")).isEqualTo(refA);
    assertThat(lines[1]).contains("a1");
    assertThat(tagValue(lines[2], "batch")).isEqualTo(refA);
    assertThat(lines[2]).contains("a2");
    assertThat(lines[3]).isEqualTo("BATCH -" + refA);

    // Group B: open (with its type-param), one tagged member, close.
    assertThat(lines[4]).startsWith("BATCH +").contains(" type-b param1");
    int prefixLenB = "BATCH +".length();
    String refB = lines[4].substring(prefixLenB, lines[4].indexOf(' ', prefixLenB));
    assertThat(tagValue(lines[5], "batch")).isEqualTo(refB);
    assertThat(lines[5]).contains("b1");
    assertThat(lines[6]).isEqualTo("BATCH -" + refB);

    // Group boundary count check above already required exactly 7 lines total.
    assertThat(refA).isNotEqualTo(refB);
  }

  @Test
  void recipientLackingEitherCapabilityGetsMembersCompletelyUnwrapped() throws Exception {
    ClientSession neitherCapability = newSession();
    ByteArrayOutputStream outNeither = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(neitherCapability, outNeither, TagRenderer.NONE)) {
      writer.enqueueBatch("type-a", List.of(), List.of(privmsg("#chan", "hello")));
    }
    String[] neitherLines = lines(outNeither);
    assertThat(neitherLines).hasSize(1);
    assertThat(neitherLines[0]).doesNotStartWith("@").doesNotContain("BATCH").contains("hello");

    ClientSession batchOnly = newSession();
    batchOnly.negotiatedCapabilities().add(CapabilityName.BATCH);
    ByteArrayOutputStream outBatchOnly = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(batchOnly, outBatchOnly, TagRenderer.NONE)) {
      writer.enqueueBatch("type-a", List.of(), List.of(privmsg("#chan", "hello")));
    }
    String[] batchOnlyLines = lines(outBatchOnly);
    assertThat(batchOnlyLines).hasSize(1);
    assertThat(batchOnlyLines[0]).doesNotStartWith("@").doesNotContain("BATCH").contains("hello");
  }
}
