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
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import org.junit.jupiter.api.Test;

/** {@code sendFittedNotice} and {@code fitsInNotice} bound their work by the notice budget. */
class RepliesFittedNoticeTest {
    private static final String SERVER = "test.jircd.local";

    private static int budget() {
        Message empty =
                new Message(Map.of(), SERVER, Command.NOTICE, "NOTICE", List.of("requester", ""));
        return MessageSerializer.MAX_COMMAND_BYTES - MessageSerializer.commandBytes(empty);
    }

    private static ClientSession requester() {
        ClientSession requester =
                new ClientSession("requester", "host.example", RateLimitBucket.withDefaults());
        requester.setNickname("requester");
        return requester;
    }

    @Test
    void aMillionCharTextWithASurrogatePairAtTheCutFitsAndStaysValidUtf8() throws Exception {
        int budget = budget();
        // The pair straddles the budget in chars: high surrogate at budget - 1, low at budget.
        String text = "a".repeat(budget - 1) + "😀" + "b".repeat(1_000_000);
        ClientSession session = requester();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
            session.attachWriter(writer);

            assertThat(Replies.fitsInNotice(session, SERVER, text)).isFalse();
            Replies.sendFittedNotice(session, SERVER, text);
        }

        byte[] line = out.toByteArray();
        assertThat(line).hasSizeLessThanOrEqualTo(MessageSerializer.MAX_COMMAND_BYTES);
        // Java's encoder turns a lone surrogate into '?', so compare the exact notice: the pair
        // does not fit, and the high surrogate before the cut must be dropped, not sent as '?'.
        String expected = ":" + SERVER + " NOTICE requester " + "a".repeat(budget - 1) + "\r\n";
        String decoded =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(line))
                        .toString();
        assertThat(decoded).isEqualTo(expected).doesNotContain("?", "\uFFFD");
    }

    @Test
    void aTextExactlyTheBudgetIsSentWholeAndFits() {
        String text = "x".repeat(budget());
        ClientSession session = requester();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
            session.attachWriter(writer);

            assertThat(Replies.fitsInNotice(session, SERVER, text)).isTrue();
            assertThat(Replies.fitsInNotice(session, SERVER, text + "x")).isFalse();
            Replies.sendFittedNotice(session, SERVER, text);
        }

        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo(":" + SERVER + " NOTICE requester " + text + "\r\n");
    }
}
