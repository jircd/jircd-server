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
package net.jircd.capabilities.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jircd.core.capability.Capability;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.session.CapabilityTagRenderer;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/** 011-away-notify-batch User Story 2, through the real provider and core's group admission. */
class BatchExtensionTest {

  private static final String BATCH = CapabilityName.BATCH;
  private static final String TAGS = CapabilityName.MESSAGE_TAGS;

  private final ExtensionRegistry extensions = new ExtensionRegistry();

  BatchExtensionTest() {
    extensions.register(new BatchExtension());
    extensions.enable(BatchExtension.ID);
  }

  private static ClientSession session(String... negotiated) {
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(List.of(negotiated));
    return session;
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

  private String[] send(
      ClientSession recipient, String type, List<String> params, Message... members) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(recipient, out, new CapabilityTagRenderer(extensions))) {
      writer.enqueueGroup(type, params, List.of(members));
    }
    return lines(out);
  }

  @Test
  void registersOneGroupShaperGatedOnMessageTagsAsASessionPrerequisite() {
    CapabilityRegistrar registrar = new CapabilityRegistrar("batch", "batch");
    new BatchExtension().register(registrar);
    List<HookRegistration> registrations = registrar.build().registrations();
    assertThat(registrations).hasSize(1);
    assertThat(registrations.getFirst().kind()).isEqualTo(HookKind.OUTPUT_GROUP);
    assertThat(registrations.getFirst().requiresNegotiated()).containsExactly(TAGS);
  }

  @Test
  void bothCapabilitiesFrameTheGroupWithoutAPrefixAndTagEveryMember() {
    String[] lines =
        send(
            session(BATCH, TAGS),
            "type-a",
            List.of("param1"),
            privmsg("#chan", "a1"),
            privmsg("#chan", "a2"));

    assertThat(lines).hasSize(4);
    assertThat(lines[0]).startsWith("BATCH +").endsWith(" type-a param1").doesNotStartWith(":");
    String ref = lines[0].substring("BATCH +".length(), lines[0].indexOf(' ', "BATCH +".length()));
    assertThat(tagValue(lines[1], "batch")).isEqualTo(ref);
    assertThat(lines[1]).contains("a1");
    assertThat(tagValue(lines[2], "batch")).isEqualTo(ref);
    assertThat(lines[2]).contains("a2");
    assertThat(lines[3]).isEqualTo("BATCH -" + ref);
  }

  @Test
  void aRecipientLackingEitherCapabilityGetsMembersCompletelyUnwrapped() {
    for (ClientSession recipient : List.of(session(), session(BATCH), session(TAGS))) {
      String[] lines = send(recipient, "type-a", List.of(), privmsg("#chan", "hello"));
      assertThat(lines).hasSize(1);
      assertThat(lines[0]).doesNotStartWith("@").doesNotContain("BATCH").contains("hello");
    }
  }

  @Test
  void batchAloneStillNegotiatesAndTheOfferSurvives() {
    assertThat(extensions.offeredCapabilities())
        .extracting(Capability::name)
        .containsExactly(BATCH);
  }

  @Test
  void twoConcurrentGroupsAreDistinctAndCorrectlyFramed() {
    ClientSession recipient = session(BATCH, TAGS);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(recipient, out, new CapabilityTagRenderer(extensions))) {
      writer.enqueueGroup(
          "type-a", List.of(), List.of(privmsg("#chan", "a1"), privmsg("#chan", "a2")));
      writer.enqueueGroup("type-b", List.of("param1"), List.of(privmsg("#chan", "b1")));
    }
    String[] lines = lines(out);
    assertThat(lines).hasSize(7);
    String refA = lines[0].substring("BATCH +".length(), lines[0].indexOf(' ', "BATCH +".length()));
    String refB = lines[4].substring("BATCH +".length(), lines[4].indexOf(' ', "BATCH +".length()));
    assertThat(refA).isNotEqualTo(refB);
    assertThat(lines[3]).isEqualTo("BATCH -" + refA);
    assertThat(lines[6]).isEqualTo("BATCH -" + refB);
    assertThat(tagValue(lines[5], "batch")).isEqualTo(refB);
  }

  @Test
  void referencesAreUniqueAsciiTokens() {
    Set<String> refs = new HashSet<>();
    ClientSession recipient = session(BATCH, TAGS);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(recipient, out, new CapabilityTagRenderer(extensions), 1 << 20)) {
      for (int i = 0; i < 200; i++) {
        writer.enqueueGroup("t", List.of(), List.of(privmsg("#c", "x")));
      }
    }
    for (String line : lines(out)) {
      if (line.startsWith("BATCH +")) refs.add(line.substring(7, line.indexOf(' ', 7)));
    }
    assertThat(refs).hasSize(200).allMatch(ref -> ref.matches("[0-9a-f-]{36}"));
  }

  @Test
  void anEmptyGroupIsAnOpenerAndACloserOnlyForABatchRecipient() {
    String[] batched = send(session(BATCH, TAGS), "empty", List.of());
    assertThat(batched).hasSize(2);
    assertThat(batched[0]).startsWith("BATCH +").endsWith(" empty");
    assertThat(batched[1])
        .isEqualTo("BATCH -" + batched[0].substring(7, batched[0].indexOf(' ', 7)));
    assertThat(send(session(), "empty", List.of())).isEmpty();
  }

  @Test
  void aNestedGroupKeepsItsMembersTagsAndTheOuterGroupTagsTheRest() {
    Message innerOpen = new Message(Map.of(), null, null, "BATCH", List.of("+inner", "sub"));
    Message innerMember =
        new Message(
            Map.of("batch", "inner"), "s!i@h", Command.PRIVMSG, "PRIVMSG", List.of("#c", "deep"));
    Message innerClose = new Message(Map.of(), null, null, "BATCH", List.of("-inner"));

    String[] lines =
        send(session(BATCH, TAGS), "outer", List.of(), innerOpen, innerMember, innerClose);

    assertThat(lines).hasSize(5);
    String ref = lines[0].substring(7, lines[0].indexOf(' ', 7));
    assertThat(tagValue(lines[1], "batch"))
        .isEqualTo(ref); // the inner opener joins the outer group
    assertThat(tagValue(lines[2], "batch")).isEqualTo("inner");
    assertThat(lines[2]).contains("deep");
    assertThat(tagValue(lines[3], "batch")).isEqualTo(ref);
    assertThat(lines[4]).isEqualTo("BATCH -" + ref);
  }

  @Test
  void aGroupTooLargeForTheQueueIsRejectedWholeWithoutAnOrphanOpener() {
    ClientSession recipient = session(BATCH, TAGS);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<Message> members = new ArrayList<>();
    for (int i = 0; i < 20; i++) members.add(privmsg("#chan", "x".repeat(100)));
    try (SessionWriter writer =
        new SessionWriter(recipient, out, new CapabilityTagRenderer(extensions), 800)) {
      writer.enqueueGroup("big", List.of(), members);
    }
    String written = out.toString(StandardCharsets.UTF_8);
    assertThat(written).doesNotContain("BATCH +").doesNotContain("PRIVMSG");
  }

  @Test
  void aGroupAdmittedBeforeWithdrawalStillEndsWithItsCloser() {
    ClientSession recipient = session(BATCH, TAGS);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(recipient, out, new CapabilityTagRenderer(extensions))) {
      writer.enqueueGroup("t", List.of(), List.of(privmsg("#c", "x")));
      extensions.disable(BatchExtension.ID);
    }
    String[] lines = lines(out);
    assertThat(lines).hasSize(3);
    assertThat(lines[2]).startsWith("BATCH -");
  }

  @Test
  void withoutTheProviderGroupsAreSentPlain() {
    extensions.disable(BatchExtension.ID);
    String[] lines = send(session(BATCH, TAGS), "t", List.of(), privmsg("#c", "x"));
    assertThat(lines).hasSize(1);
    assertThat(lines[0]).doesNotContain("BATCH");
  }
}
