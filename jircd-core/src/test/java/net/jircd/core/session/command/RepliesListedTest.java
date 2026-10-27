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
import java.util.Map;
import java.util.stream.IntStream;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import net.jircd.protocol.MessageSerializer;
import net.jircd.protocol.NumericReply;
import org.junit.jupiter.api.Test;

class RepliesListedTest {
    private static final String SERVER = "test.jircd.local";
    private static final List<String> FIXED = List.of("=", "#ébudget");

    @Test
    void packsNineByteNicknamesInOrderWithoutSplittingTokens() throws Exception {
        List<String> items =
                IntStream.range(0, 60).mapToObj(i -> String.format("nick%05d", i)).toList();
        List<Message> replies = sendListed(items);
        assertThat(replies).hasSizeGreaterThan(1);
        List<String> actual = new ArrayList<>();
        for (Message reply : replies) {
            assertThat(MessageSerializer.commandBytes(reply))
                    .isLessThanOrEqualTo(MessageSerializer.MAX_COMMAND_BYTES);
            assertThat(reply.rawCommand()).isEqualTo("353");
            assertThat(reply.params().subList(0, 3)).containsExactly("requester", "=", "#ébudget");
            actual.addAll(List.of(reply.params().getLast().split(" ")));
        }
        assertThat(actual).containsExactlyElementsOf(items);
    }

    @Test
    void emptyItemsStillEmitOneReplyWithEmptyTrailingParameter() throws Exception {
        assertThat(sendListed(List.of()))
                .singleElement()
                .satisfies(
                        reply ->
                                assertThat(reply.params())
                                        .containsExactly("requester", "=", "#ébudget", ""));
    }

    @Test
    void singletonFitsEvenWithTrailingColonReserved() throws Exception {
        Message empty =
                new Message(
                        Map.of(), SERVER, null, "353", List.of("requester", "=", "#ébudget", ""));
        int budget = MessageSerializer.MAX_COMMAND_BYTES - MessageSerializer.commandBytes(empty);
        String token = "x".repeat(budget);
        assertThat(sendListed(List.of(token)))
                .singleElement()
                .satisfies(
                        reply -> {
                            assertThat(reply.params().getLast()).isEqualTo(token);
                            assertThat(MessageSerializer.commandBytes(reply) + 1)
                                    .as("singleton serialized without colon still has room for it")
                                    .isLessThanOrEqualTo(MessageSerializer.MAX_COMMAND_BYTES);
                        });
    }

    @Test
    void twoTokensExactlyFillingTheBudgetStayOnOneLine() throws Exception {
        Message empty =
                new Message(
                        Map.of(), SERVER, null, "353", List.of("requester", "=", "#ébudget", ""));
        int budget = MessageSerializer.MAX_COMMAND_BYTES - MessageSerializer.commandBytes(empty);
        String first = "x".repeat(budget - 2);
        List<Message> replies = sendListed(List.of(first, "y"));
        assertThat(replies)
                .singleElement()
                .satisfies(
                        reply -> {
                            assertThat(reply.params().getLast()).isEqualTo(first + " y");
                            assertThat(MessageSerializer.commandBytes(reply))
                                    .isEqualTo(MessageSerializer.MAX_COMMAND_BYTES);
                        });
    }

    @Test
    void twoTokensOneByteOverTheBudgetSplitBetweenTokens() throws Exception {
        Message empty =
                new Message(
                        Map.of(), SERVER, null, "353", List.of("requester", "=", "#ébudget", ""));
        int budget = MessageSerializer.MAX_COMMAND_BYTES - MessageSerializer.commandBytes(empty);
        String first = "x".repeat(budget - 2);
        List<Message> replies = sendListed(List.of(first, "yy"));
        assertThat(replies).hasSize(2);
        assertThat(replies.stream().map(reply -> reply.params().getLast()))
                .containsExactly(first, "yy");
    }

    private static List<Message> sendListed(List<String> items) throws Exception {
        ClientSession requester =
                new ClientSession("requester", "host.example", RateLimitBucket.withDefaults());
        requester.setNickname("requester");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(requester, out, TagRenderer.NONE)) {
            requester.attachWriter(writer);
            Replies.sendListed(requester, SERVER, NumericReply.RPL_NAMREPLY, FIXED, items);
        }
        List<Message> replies = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\r\n")) {
            assertThat(MessageSerializer.commandBytes(MessageParser.parse(line)))
                    .isLessThanOrEqualTo(MessageSerializer.MAX_COMMAND_BYTES);
            replies.add(MessageParser.parse(line));
        }
        return replies;
    }
}
