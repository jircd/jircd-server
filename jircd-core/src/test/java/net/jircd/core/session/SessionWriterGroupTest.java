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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.FramingPlan;
import net.jircd.core.extension.capability.OutputGroup;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionWriter#enqueueGroup}: generic framing, with a stub shaper standing in for batch.
 */
class SessionWriterGroupTest {

  /**
   * Frames a group with BEGIN/END lines, only for recipients that negotiated "framer" and "tags".
   */
  private static final class Framer extends AbstractCapabilityExtension {
    final List<OutputGroup> seen = new ArrayList<>();

    Framer() {
      super("framer");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onOutputGroup(
          Set.of("tags"),
          (group, context) -> {
            seen.add(group);
            List<Message> frames = new ArrayList<>();
            frames.add(new Message(Map.of(), null, null, "BATCH", List.of("+g", group.type())));
            group.members().forEach(frames::add);
            frames.add(new Message(Map.of(), null, null, "BATCH", List.of("-g")));
            return new FramingPlan(frames);
          });
    }
  }

  private static Message privmsg(String body) {
    return new Message(
        Map.of(), "sender!ident@host", Command.PRIVMSG, "PRIVMSG", List.of("#chan", body));
  }

  private static ClientSession session(String... negotiated) {
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(List.of(negotiated));
    return session;
  }

  private static ExtensionRegistry registryWithFramer(Framer framer) {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(framer);
    registry.enable("framer");
    return registry;
  }

  private static String[] lines(ByteArrayOutputStream out) {
    String text = out.toString(StandardCharsets.UTF_8);
    return text.isEmpty() ? new String[0] : text.split("\r\n");
  }

  @Test
  void withoutAShaperTheMembersAreSentPlain() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE)) {
      writer.enqueueGroup("type", List.of(), List.of(privmsg("a"), privmsg("b")));
    }
    assertThat(lines(out))
        .containsExactly(
            ":sender!ident@host PRIVMSG #chan a", ":sender!ident@host PRIVMSG #chan b");
  }

  @Test
  void aValidPlanIsAppendedAsOneRunWithItsOpenerAndCloser() {
    Framer framer = new Framer();
    ExtensionRegistry registry = registryWithFramer(framer);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(session("framer", "tags"), out, new CapabilityTagRenderer(registry))) {
      writer.enqueueGroup("type-a", List.of("p"), List.of(privmsg("a1")));
    }
    assertThat(lines(out))
        .containsExactly("BATCH +g type-a", ":sender!ident@host PRIVMSG #chan a1", "BATCH -g");
    assertThat(framer.seen).hasSize(1);
    assertThat(framer.seen.getFirst().typeParams()).containsExactly("p");
  }

  @Test
  void aMissingOwnerOrPrerequisiteMeansPlainMembersAndNoShaperCall() {
    Framer framer = new Framer();
    ExtensionRegistry registry = registryWithFramer(framer);
    for (ClientSession recipient : List.of(session(), session("framer"), session("tags"))) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (SessionWriter writer =
          new SessionWriter(recipient, out, new CapabilityTagRenderer(registry))) {
        writer.enqueueGroup("type", List.of(), List.of(privmsg("x")));
      }
      assertThat(lines(out)).containsExactly(":sender!ident@host PRIVMSG #chan x");
    }
    assertThat(framer.seen).isEmpty();
  }

  @Test
  void anEmptyGroupWithoutFramingSendsNothing() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE)) {
      writer.enqueueGroup("type", List.of(), List.of());
    }
    assertThat(lines(out)).isEmpty();
  }

  @Test
  void framingOverheadThatExceedsTheQueueOverflowsTheRecipientInsteadOfFallingBackToPlain() {
    Framer framer = new Framer();
    ExtensionRegistry registry = registryWithFramer(framer);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // The plain member is 37 bytes with CRLF; the framed group adds 25 more, so 50 holds only
    // plain.
    try (SessionWriter writer =
        new SessionWriter(
            session("framer", "tags"), out, new CapabilityTagRenderer(registry), 50)) {
      writer.enqueueGroup("type", List.of(), List.of(privmsg("x")));
    }
    String written = out.toString(StandardCharsets.UTF_8);
    assertThat(written)
        .doesNotContain("PRIVMSG")
        .doesNotContain("BATCH")
        .contains("SendQ exceeded");
  }

  @Test
  void aGroupWithAnUnrepresentableMemberIsDroppedWholeAndLaterOutputStillFlows() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Message injected =
        new Message(Map.of(), "s!i@h", Command.PRIVMSG, "PRIVMSG", List.of("#chan", "a\r\nQUIT"));
    try (SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE)) {
      writer.enqueueGroup("type", List.of(), List.of(privmsg("ok"), injected));
      writer.enqueueGroup("type", List.of(), List.of(privmsg("later")));
    }
    assertThat(lines(out)).containsExactly(":sender!ident@host PRIVMSG #chan later");
  }

  @Test
  void aGroupThatCannotFitTheQueueIsRejectedWholeAndNeverPartlyWritten() {
    Framer framer = new Framer();
    ExtensionRegistry registry = registryWithFramer(framer);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ClientSession recipient = session("framer", "tags");
    List<Message> members = new ArrayList<>();
    for (int i = 0; i < 20; i++) members.add(privmsg("x".repeat(100)));
    // Room for a few members but not the whole group plus its opener and closer.
    try (SessionWriter writer =
        new SessionWriter(recipient, out, new CapabilityTagRenderer(registry), 600)) {
      writer.enqueueGroup("type", List.of(), members);
    }
    String written = out.toString(StandardCharsets.UTF_8);
    assertThat(written)
        .doesNotContain("BATCH +g")
        .doesNotContain("PRIVMSG")
        .contains("SendQ exceeded");
  }

  @Test
  void anAdmittedGroupKeepsItsCloserWhenTheShaperIsWithdrawnBeforeTheDrain() {
    Framer framer = new Framer();
    ExtensionRegistry registry = registryWithFramer(framer);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(session("framer", "tags"), out, new CapabilityTagRenderer(registry))) {
      writer.enqueueGroup("type", List.of(), List.of(privmsg("a")));
      registry.disable("framer");
    }
    String[] lines = lines(out);
    assertThat(lines).hasSize(3).endsWith("BATCH -g");
  }

  @Test
  void concurrentGroupsToOneRecipientNeverInterleave() throws Exception {
    Framer framer = new Framer();
    ExtensionRegistry registry = registryWithFramer(framer);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(
            session("framer", "tags"), out, new CapabilityTagRenderer(registry), 1 << 20)) {
      List<Thread> threads = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        int id = t;
        threads.add(
            Thread.ofVirtual()
                .start(
                    () -> {
                      for (int i = 0; i < 25; i++) {
                        writer.enqueueGroup(
                            "t" + id, List.of(), List.of(privmsg("a"), privmsg("b"), privmsg("c")));
                      }
                    }));
      }
      for (Thread thread : threads) thread.join();
    }
    String[] lines = lines(out);
    assertThat(lines).hasSize(8 * 25 * 5);
    for (int i = 0; i < lines.length; i += 5) {
      assertThat(lines[i]).startsWith("BATCH +g t");
      assertThat(lines[i + 1]).contains("PRIVMSG #chan a");
      assertThat(lines[i + 3]).contains("PRIVMSG #chan c");
      assertThat(lines[i + 4]).isEqualTo("BATCH -g");
    }
  }
}
