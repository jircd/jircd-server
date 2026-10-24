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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(15)
class SessionWriterByteLimitTest {
  private static final int LIMIT = 16384;

  private static ClientSession session() {
    return new ClientSession("bytes", "host", RateLimitBucket.withDefaults());
  }

  private static Message blockLine() {
    return Message.of(Command.PING, "block");
  }

  private static Message fullLine() {
    return new Message(Map.of(), null, Command.ERROR, "ERROR", List.of("x".repeat(504)));
  }

  @Test
  void rejectsNonpositiveWriterLimits() throws IOException {
    ClientSession session = session();
    try (OutputStream out = OutputStream.nullOutputStream()) {
      assertThatThrownBy(() -> new SessionWriter(session, out, TagRenderer.NONE, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("limitBytes");
    }
  }

  @Test
  void contributionMetadataDoesNotChangeMessageValueEquality() {
    OutboundMessage first = OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hello");
    OutboundMessage equal =
        new OutboundMessage(
            first.senderPresentedForm(),
            first.command(),
            first.target(),
            first.body(),
            first.clientTags(),
            first.sentAt(),
            first.messageId());
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(capability("large", Map.of("s", "x".repeat(4092))));
    registry.enable("large");
    ClientSession recipient = session();
    recipient.negotiatedCapabilities().add("large");
    new CapabilityTagRenderer(registry).prepare(recipient, first);
    assertThat(first)
        .isEqualTo(equal)
        .hasSameHashCodeAs(equal)
        .isNotEqualTo(null)
        .isNotEqualTo("other")
        .isEqualTo(first)
        .isNotEqualTo(OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "different"));
  }

  @Test
  void manySmallEntriesFitUntilTheByteLimit() throws Exception {
    AtomicInteger overflows = new AtomicInteger();
    try (GatedOutput out = new GatedOutput();
        SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, LIMIT)) {
      writer.setOnSendqOverflow(overflows::incrementAndGet);
      writer.enqueueRaw(blockLine());
      out.awaitWrite();
      try {
        Message small = Message.of(Command.PING, "x");
        for (int i = 0; i < 1000; i++) writer.enqueueRaw(small);
        assertThat(writer.queuedBytes()).isEqualTo(8000);
        assertThat(overflows).hasValue(0);
      } finally {
        out.releaseAll();
      }
    }
  }

  @Test
  void exactLimitFitsAndTheNextEntryOverflowsOnce() throws Exception {
    AtomicInteger overflows = new AtomicInteger();
    try (GatedOutput out = new GatedOutput();
        SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, LIMIT)) {
      writer.setOnSendqOverflow(overflows::incrementAndGet);
      writer.enqueueRaw(blockLine());
      out.awaitWrite();
      try {
        for (int i = 0; i < LIMIT / 512; i++) writer.enqueueRaw(fullLine());
        assertThat(writer.queuedBytes()).isEqualTo(LIMIT);
        assertThat(overflows).hasValue(0);
        writer.enqueueRaw(Message.of(Command.PING, "x"));
        assertThat(overflows).hasValue(1);
        assertThat(writer.queuedBytes()).isZero();
      } finally {
        out.releaseAll();
      }
    }
  }

  @Test
  void takingAnEntryReleasesItsReservationBeforeTheSocketWrite() throws Exception {
    AtomicInteger overflows = new AtomicInteger();
    try (GatedOutput out = new GatedOutput();
        SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, LIMIT)) {
      writer.setOnSendqOverflow(overflows::incrementAndGet);
      writer.enqueueRaw(blockLine());
      out.awaitWrite();
      try {
        for (int i = 0; i < LIMIT / 512; i++) writer.enqueueRaw(fullLine());
        out.allowOne();
        out.awaitWrite();
        assertThat(writer.queuedBytes()).isEqualTo(LIMIT - 512);
        writer.enqueueRaw(fullLine());
        assertThat(writer.queuedBytes()).isEqualTo(LIMIT);
        assertThat(overflows).hasValue(0);
      } finally {
        out.releaseAll();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void chargesMatchRecipientWireBytesAndFilterClientTags(int capabilities) throws Exception {
    ExtensionRegistry registry = new ExtensionRegistry();
    var first = capability("message-tags", Map.of("msgid", "fixed"));
    var second = capability("server-time", Map.of("time", "now"));
    registry.register(first);
    registry.enable(first.id());
    registry.register(second);
    registry.enable(second.id());
    ClientSession recipient = session();
    if ((capabilities & 1) != 0) recipient.negotiatedCapabilities().add("message-tags");
    if ((capabilities & 2) != 0) recipient.negotiatedCapabilities().add("server-time");
    OutboundMessage message =
        ForwardedTags.selectedBy(
            "message-tags",
            OutboundMessage.now(
                "n!u@h",
                "PRIVMSG",
                "#é",
                "€ body",
                Map.of("+test", "é; \\\r\n", "+empty", "", "hidden", "filtered")));
    assertCharge(recipient, new CapabilityTagRenderer(registry), message, true);
  }

  @Test
  void legalLargeServerContributionIsChargedInFull() throws Exception {
    ExtensionRegistry registry = new ExtensionRegistry();
    var large = capability("large", Map.of("s", "x".repeat(4092)));
    registry.register(large);
    registry.enable(large.id());
    ClientSession recipient = session();
    recipient.negotiatedCapabilities().add("large");
    assertCharge(
        recipient,
        new CapabilityTagRenderer(registry),
        OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hello"),
        true);
  }

  @Test
  void manyLargeContributionsStillAdmitTheLegalLineAtTheFloor() throws Exception {
    // Each owner has its own key, so together they overflow one origin: the later ones are dropped
    // at enqueue and the charge never exceeds what the queue can hold.
    ExtensionRegistry registry = new ExtensionRegistry();
    ClientSession recipient = session();
    for (int i = 0; i < 8; i++) {
      var contributor = capability("overlap" + i, Map.of("s" + i, "x".repeat(4090)));
      registry.register(contributor);
      registry.enable(contributor.id());
      recipient.negotiatedCapabilities().add(contributor.id());
    }
    assertCharge(
        recipient,
        new CapabilityTagRenderer(registry),
        OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hello"),
        false);
  }

  @Test
  void sameNamedClientAndServerTagsAndTruncatedTextHaveConservativeCharges() throws Exception {
    ExtensionRegistry registry = new ExtensionRegistry();
    var first = capability("message-tags", Map.of("same", "one"));
    var second = capability("server-time", Map.of("time", "two"));
    registry.register(first);
    registry.enable(first.id());
    registry.register(second);
    registry.enable(second.id());
    ClientSession recipient = session();
    recipient.negotiatedCapabilities().addAll(List.of("message-tags", "server-time"));
    assertCharge(
        recipient,
        new CapabilityTagRenderer(registry),
        ForwardedTags.selectedBy(
            "message-tags",
            OutboundMessage.now(
                "n!u@h", "PRIVMSG", "#c", "é".repeat(600), Map.of("+same", "client"))),
        false);
  }

  @Test
  void maximumLegalLineFitsTheFloor() throws Exception {
    ExtensionRegistry registry = new ExtensionRegistry();
    var large = capability("message-tags", Map.of("s", "x".repeat(4092)));
    registry.register(large);
    registry.enable(large.id());
    ClientSession recipient = session();
    recipient.negotiatedCapabilities().add("message-tags");
    OutboundMessage message =
        ForwardedTags.selectedBy(
            "message-tags",
            OutboundMessage.now(
                "n!u@h", "PRIVMSG", "#c", " ".repeat(491), Map.of("+c", "x".repeat(4091))));
    var renderer = new CapabilityTagRenderer(registry);
    assertCharge(recipient, renderer, message, true);
  }

  @Test
  void contributionsAreMemoizedPerMessageAndCapabilityIdentity() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var capability =
        new AbstractCapabilityExtension("message-tags") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onOutboundTags(
                Set.of("msgid"),
                42,
                message -> {
                  calls.incrementAndGet();
                  return Map.of("msgid", message.messageId().toString());
                });
          }
        };
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(capability);
    registry.enable(capability.id());
    var renderer = new CapabilityTagRenderer(registry);
    ClientSession recipient = session();
    recipient.negotiatedCapabilities().add("message-tags");
    OutboundMessage message = OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hello");
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 64; i++)
        tasks.add(threads.submit(() -> renderer.prepare(recipient, message)));
      for (var task : tasks) task.get(5, TimeUnit.SECONDS);
    }
    assertThat(calls).hasValue(1);
    renderer.prepare(recipient, OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hello"));
    assertThat(calls).hasValue(2);
    ExtensionRegistry other = new ExtensionRegistry();
    var different =
        new AbstractCapabilityExtension("message-tags") {
          @Override
          public void register(CapabilityRegistrar registrar) {
            registrar.onOutboundTags(
                Set.of("msgid"),
                42,
                input -> {
                  calls.incrementAndGet();
                  return Map.of("msgid", "different");
                });
          }
        };
    other.register(different);
    other.enable(different.id());
    new CapabilityTagRenderer(other).prepare(recipient, message);
    assertThat(calls).hasValue(3);
  }

  @Test
  void queuedSelectionCannotAcquireUnchargedTagsAfterNegotiationOrReload() throws Exception {
    ExtensionRegistry registry = new ExtensionRegistry();
    var capability = capability("message-tags", Map.of("s", "x".repeat(4092)));
    registry.register(capability);
    ClientSession recipient = session();
    var renderer = new CapabilityTagRenderer(registry);
    GatedOutput out = new GatedOutput();
    try (out;
        SessionWriter writer = new SessionWriter(recipient, out, renderer, LIMIT)) {
      writer.enqueueRaw(blockLine());
      out.awaitWrite();
      try {
        writer.enqueue(OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "before"));
        long before = writer.queuedBytes();
        registry.enable(capability.id());
        recipient.negotiatedCapabilities().add("message-tags");
        writer.enqueue(OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "after"));
        long after = writer.queuedBytes() - before;
        assertThat(after).isGreaterThan(4096);
        registry.disable(capability.id());
        recipient.negotiatedCapabilities().clear();
      } finally {
        out.releaseAll();
      }
    }
    // The first entry is untagged; the second retains the selection it reserves.
    String[] lines = out.text().split("\r\n");
    assertThat(lines).hasSize(3);
    assertThat(lines[1]).doesNotStartWith("@");
    assertThat(lines[2]).startsWith("@s=");
  }

  @Test
  void rawEscapesUtf8AndParameterSyntaxMatchTheWire() throws Exception {
    Message message =
        new Message(
            Map.of("s", "é; \\\r\n", "empty", ""),
            "n!🙂@h\uD800",
            null,
            "123",
            List.of("", ":trailing"));
    GatedOutput out = new GatedOutput();
    long charge;
    try (out;
        SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, LIMIT)) {
      writer.enqueueRaw(blockLine());
      out.awaitWrite();
      try {
        writer.enqueueRaw(message);
        charge = writer.queuedBytes();
      } finally {
        out.releaseAll();
      }
    }
    String text = out.text();
    assertThat(charge)
        .isEqualTo(
            text.substring(text.indexOf("\r\n") + 2).getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  void customRendererWithoutMetadataStaysDeferredAndCannotUndercount() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    TagRenderer renderer =
        (recipient, message) -> {
          calls.incrementAndGet();
          return Map.of("s", "x".repeat(4092));
        };
    GatedOutput out = new GatedOutput();
    long charge;
    try (out;
        SessionWriter writer = new SessionWriter(session(), out, renderer, LIMIT)) {
      writer.enqueueRaw(blockLine());
      out.awaitWrite();
      try {
        writer.enqueue(OutboundMessage.now("n!u@h", "PRIVMSG", "#c", "hello"));
        charge = writer.queuedBytes();
        assertThat(calls).hasValue(0);
      } finally {
        out.releaseAll();
      }
    }
    assertThat(calls).hasValue(1);
    String text = out.text();
    assertThat(charge)
        .isGreaterThanOrEqualTo(
            text.substring(text.indexOf("\r\n") + 2).getBytes(StandardCharsets.UTF_8).length);
  }

  @Test
  void rawBroadcastReservesTheFullWireChargeForEveryRecipient() throws Exception {
    ClientSession first = session();
    ClientSession second = session();
    Message message =
        new Message(
            Map.of("s", "x".repeat(4092)), "n!u@h", Command.QUIT, "QUIT", List.of("é; closing"));
    GatedOutput firstOut = new GatedOutput();
    GatedOutput secondOut = new GatedOutput();
    try (firstOut;
        secondOut;
        SessionWriter firstWriter = new SessionWriter(first, firstOut, TagRenderer.NONE, LIMIT);
        SessionWriter secondWriter =
            new SessionWriter(second, secondOut, TagRenderer.NONE, LIMIT)) {
      first.attachWriter(firstWriter);
      second.attachWriter(secondWriter);
      firstWriter.enqueueRaw(blockLine());
      secondWriter.enqueueRaw(blockLine());
      firstOut.awaitWrite();
      secondOut.awaitWrite();
      try {
        Recipients.deliver(List.of(first, second), message);
        long wireBytes =
            net.jircd.protocol.MessageSerializer.serialize(message)
                    .getBytes(StandardCharsets.UTF_8)
                    .length
                + 2L;
        assertThat(firstWriter.queuedBytes()).isEqualTo(wireBytes);
        assertThat(secondWriter.queuedBytes()).isEqualTo(wireBytes);
      } finally {
        firstOut.releaseAll();
        secondOut.releaseAll();
      }
    }
    assertThat(firstOut.text()).isEqualTo(secondOut.text());
  }

  private static AbstractCapabilityExtension capability(String id, Map<String, String> tags) {
    return new TagProvider(id, tags);
  }

  private static void assertCharge(
      ClientSession recipient, TagRenderer renderer, OutboundMessage message, boolean exact)
      throws Exception {
    GatedOutput out = new GatedOutput();
    long charge;
    try (out;
        SessionWriter writer = new SessionWriter(recipient, out, renderer, LIMIT)) {
      writer.enqueueRaw(blockLine());
      out.awaitWrite();
      try {
        writer.enqueue(message);
        charge = writer.queuedBytes();
      } finally {
        out.releaseAll();
      }
    }
    String text = out.text();
    String line = text.substring(text.indexOf("\r\n") + 2);
    long wireBytes = line.getBytes(StandardCharsets.UTF_8).length;
    assertThat(line).isNotEmpty();
    assertThat(charge).isLessThanOrEqualTo(LIMIT);
    if (exact) assertThat(charge).isEqualTo(wireBytes);
    else assertThat(charge).isGreaterThanOrEqualTo(wireBytes);
  }

  private static final class GatedOutput extends OutputStream {
    private final Semaphore started = new Semaphore(0);
    private final Semaphore permits = new Semaphore(0);
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @Override
    public void write(int b) throws IOException {
      write(new byte[] {(byte) b});
    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException {
      started.release();
      try {
        permits.acquire();
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
      bytes.write(data, offset, length);
    }

    void awaitWrite() throws InterruptedException {
      assertThat(started.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
    }

    void allowOne() {
      permits.release();
    }

    void releaseAll() {
      permits.release(100000);
    }

    String text() {
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }
}
