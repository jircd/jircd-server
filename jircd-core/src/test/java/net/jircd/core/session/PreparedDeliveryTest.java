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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class PreparedDeliveryTest {

    private static ClientSession session() {
        return new ClientSession("prepared", "host", RateLimitBucket.withDefaults());
    }

    private static Message frame(String text) {
        return new Message(Map.of(), "srv", Command.NOTICE, "NOTICE", List.of("#c", text));
    }

    private static PreparedDelivery delivery(Message... frames) {
        return new PreparedDelivery(FrameSequence.of(List.of(frames)), RuntimeSnapshot.EMPTY);
    }

    @Test
    void sequenceMaterializesExactBytesIncludingTagsAndCrlf() {
        Message tagged =
                new Message(
                        Map.of("a", "b c;\\"),
                        "srv",
                        Command.NOTICE,
                        "NOTICE",
                        List.of("#é", "€ text"));
        Message bare = new Message(Map.of(), null, null, "BATCH", List.of("+ref", "type"));
        FrameSequence sequence = FrameSequence.of(List.of(bare, tagged));
        String taggedLine = MessageSerializer.serializeText(tagged);
        String bareLine = MessageSerializer.serializeText(bare);
        assertThat(sequence.lines()).containsExactly(bareLine, taggedLine);
        assertThat(sequence.bytes())
                .isEqualTo(
                        (long) bareLine.getBytes(StandardCharsets.UTF_8).length
                                + 2
                                + taggedLine.getBytes(StandardCharsets.UTF_8).length
                                + 2);
    }

    @Test
    void sequenceIsImmutableAndIndependentOfItsSource() {
        List<Message> source = new ArrayList<>(List.of(frame("one")));
        FrameSequence sequence = FrameSequence.of(source);
        source.add(frame("two"));
        assertThat(sequence.lines()).hasSize(1);
        List<String> lines = sequence.lines();
        assertThatThrownBy(() -> lines.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEmptyAndUnserializableSequencesAtPreparation() {
        assertThatThrownBy(() -> FrameSequence.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Message oversized =
                new Message(Map.of(), null, Command.TOPIC, "TOPIC", List.of("#c", "x".repeat(600)));
        List<Message> frames = List.of(frame("ok"), oversized);
        assertThatThrownBy(() -> FrameSequence.of(frames))
                .isInstanceOf(MessageSerializer.MessageTooLongException.class);
    }

    @Test
    void rejectsFramesWhoseSyntaxWouldSplitOrCorruptTheWireLine() {
        Message[] bad = {
            new Message(Map.of(), "srv", Command.NOTICE, "NOTICE", List.of("#c", "a\r\nQUIT :x")),
            new Message(Map.of(), "srv", Command.NOTICE, "NOTICE", List.of("#c\nQUIT", "text")),
            new Message(Map.of(), "srv", Command.NOTICE, "NOTICE", List.of("#c", "nul\0")),
            new Message(Map.of(), "sr v", Command.NOTICE, "NOTICE", List.of("#c", "x")),
            new Message(Map.of(), "srv", Command.NOTICE, "NOTICE\r\nQUIT", List.of("#c", "x")),
            new Message(Map.of(), "srv", Command.TOPIC, "TOPIC", List.of("has space", "x")),
            new Message(Map.of("a b", "v"), "srv", Command.NOTICE, "NOTICE", List.of("#c", "x")),
            new Message(Map.of("a;b", "v"), "srv", Command.NOTICE, "NOTICE", List.of("#c", "x")),
            new Message(Map.of("a", "v\0"), "srv", Command.NOTICE, "NOTICE", List.of("#c", "x")),
        };
        for (Message message : bad) {
            List<Message> frames = List.of(message);
            assertThatThrownBy(() -> FrameSequence.of(frames))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void enforcesTheServerOriginTagBudgetNotJustTheCombinedSection() {
        Message oneServerTag =
                new Message(
                        Map.of("big", "x".repeat(4090)),
                        "srv",
                        Command.NOTICE,
                        "NOTICE",
                        List.of("#c", "x"));
        assertThat(FrameSequence.of(List.of(oneServerTag)).lines()).hasSize(1);
        Message overBudget =
                new Message(
                        Map.of("big", "x".repeat(4091), "+c", "y".repeat(3000)),
                        "srv",
                        Command.NOTICE,
                        "NOTICE",
                        List.of("#c", "x"));
        List<Message> frames = List.of(overBudget);
        assertThatThrownBy(() -> FrameSequence.of(frames))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4094");
    }

    @Test
    void admittedSequenceIsWrittenInOrderAndReleasesItsReservationOnce() throws Exception {
        try (GatedOutput out = new GatedOutput();
                SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, 16384)) {
            writer.enqueueRaw(Message.of(Command.PING, "block"));
            out.awaitWrite();
            PreparedDelivery prepared = delivery(frame("one"), frame("two"), frame("three"));
            writer.enqueue(prepared);
            assertThat(writer.queuedBytes()).isEqualTo(prepared.frames().bytes());
            out.releaseAll();
            out.awaitWrites(4); // the PING plus the three frames
            assertThat(writer.queuedBytes()).isZero();
            String text = out.text();
            assertThat(text.indexOf("one")).isLessThan(text.indexOf("two"));
            assertThat(text.indexOf("two")).isLessThan(text.indexOf("three"));
        }
    }

    @Test
    void aSequenceThatDoesNotFitIsRejectedWholeWithNoPartialAdmission() throws Exception {
        AtomicInteger overflows = new AtomicInteger();
        PreparedDelivery tooBig =
                delivery(frame("a".repeat(100)), frame("b".repeat(100)), frame("c".repeat(100)));
        long perFrame = tooBig.frames().bytes() / 3;
        int limit = (int) (perFrame * 2 + 10); // each frame fits, the whole sequence does not
        try (GatedOutput out = new GatedOutput();
                SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, limit)) {
            writer.setOnSendqOverflow(overflows::incrementAndGet);
            writer.enqueueRaw(Message.of(Command.PING, "block"));
            out.awaitWrite();
            writer.enqueue(tooBig);
            assertThat(overflows).hasValue(1);
            assertThat(writer.queuedBytes()).isZero();
            out.releaseAll();
            writer.close();
            assertThat(out.text())
                    .doesNotContain("aaaa")
                    .doesNotContain("bbbb")
                    .doesNotContain("cccc");
            assertThat(out.text()).contains("SendQ exceeded");
        }
    }

    @Test
    void overflowAfterAdmissionDiscardsTheWholeBacklogAndReleasesEveryReservation()
            throws Exception {
        AtomicInteger overflows = new AtomicInteger();
        PreparedDelivery first = delivery(frame("a".repeat(100)), frame("b".repeat(100)));
        int limit = (int) first.frames().bytes() + 20;
        try (GatedOutput out = new GatedOutput();
                SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, limit)) {
            writer.setOnSendqOverflow(overflows::incrementAndGet);
            writer.enqueueRaw(Message.of(Command.PING, "block"));
            out.awaitWrite();
            writer.enqueue(first);
            assertThat(writer.queuedBytes()).isEqualTo(first.frames().bytes());
            writer.enqueue(delivery(frame("d".repeat(100))));
            assertThat(overflows).hasValue(1);
            assertThat(writer.queuedBytes()).isZero();
            out.releaseAll();
        }
    }

    @Test
    void admissionAfterCloseIsIgnored() throws Exception {
        try (GatedOutput out = new GatedOutput();
                SessionWriter writer = new SessionWriter(session(), out, TagRenderer.NONE, 16384)) {
            out.releaseAll();
            writer.close();
            writer.enqueue(delivery(frame("late")));
            assertThat(writer.queuedBytes()).isZero();
            assertThat(out.text()).doesNotContain("late");
        }
    }

    @Test
    void queuedSequenceHoldsNoProviderCallbackOrMutableState() {
        // A PreparedDelivery is only strings, byte counts and the immutable captured view.
        for (var component : PreparedDelivery.class.getRecordComponents()) {
            assertThat(component.getType()).isIn(FrameSequence.class, RuntimeSnapshot.class);
        }
        for (var field : FrameSequence.class.getDeclaredFields()) {
            assertThat(field.getType()).isIn(List.class, long.class);
        }
    }

    private static final class GatedOutput extends OutputStream {
        private final Semaphore started = new Semaphore(0);
        private final Semaphore permits = new Semaphore(0);
        private final Semaphore completed = new Semaphore(0);
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
            synchronized (bytes) {
                bytes.write(data, offset, length);
            }
            completed.release();
        }

        void awaitWrites(int count) throws InterruptedException {
            assertThat(completed.tryAcquire(count, 5, TimeUnit.SECONDS)).isTrue();
        }

        void awaitWrite() throws InterruptedException {
            assertThat(started.tryAcquire(5, TimeUnit.SECONDS)).isTrue();
        }

        void releaseAll() {
            permits.release(100000);
        }

        String text() {
            synchronized (bytes) {
                return bytes.toString(StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    void tagKeysMustMatchTheIrcv3KeyGrammar() {
        for (String key :
                List.of("a\tb", "a\u0001b", "a\u007fb", "a_b", "+", "vendor/", "/name", "a/b/c")) {
            List<Message> frames =
                    List.of(
                            new Message(
                                    Map.of(key, "v"),
                                    "srv",
                                    Command.NOTICE,
                                    "NOTICE",
                                    List.of("#c", "x")));
            assertThatThrownBy(() -> FrameSequence.of(frames))
                    .as("key %s", key.replace("\t", "\\t"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String key :
                List.of("time", "msgid", "+draft/reply", "example.com/foo-bar", "+typing")) {
            List<Message> frames =
                    List.of(
                            new Message(
                                    Map.of(key, "v"),
                                    "srv",
                                    Command.NOTICE,
                                    "NOTICE",
                                    List.of("#c", "x")));
            assertThat(FrameSequence.of(frames).lines()).hasSize(1);
        }
    }
}
