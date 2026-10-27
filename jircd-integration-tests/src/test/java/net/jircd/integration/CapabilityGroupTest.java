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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/**
 * A registered finite-group probe over a real socket: core's generic group admission with the batch
 * provider discovered from the classpath. The probe enqueues a finite group on a live session's
 * writer, the way a future history or label feature would, so the framing seen here is the
 * provider's and the admission is core's.
 */
class CapabilityGroupTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final String BATCH_AND_TAGS_YAML =
            """
      capabilities:
        batch: enabled
        message-tags: enabled
      """;

    private static Message privmsg(String body) {
        return new Message(
                Map.of(), "bot!bot@host", Command.PRIVMSG, "PRIVMSG", List.of("#probe", body));
    }

    private static RawIrcClient client(TestServer server, String nick, String caps)
            throws Exception {
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
        if (!caps.isEmpty()) {
            client.send("CAP REQ :" + caps);
            client.readUntil("CAP * ACK", TIMEOUT);
            client.send("CAP END");
        }
        client.registerAndAwaitWelcome(nick, nick);
        return client;
    }

    private static ClientSession live(TestServer server, String nick) {
        return server.application.nicknameRegistry().lookup(nick).orElseThrow();
    }

    /** Everything the client was sent for the probe, ending at its own barrier. */
    private static List<String> probe(RawIrcClient client) throws Exception {
        List<String> lines = new ArrayList<>(client.readThroughPong(TIMEOUT));
        return lines.stream()
                .filter(l -> l.contains("PRIVMSG #probe") || l.contains("BATCH"))
                .toList();
    }

    @Test
    void aBatchAndMessageTagsClientReceivesTheGroupFramedAndTagged() throws Exception {
        try (TestServer server = TestServer.start(BATCH_AND_TAGS_YAML);
                RawIrcClient alice = client(server, "alice", "batch message-tags")) {
            live(server, "alice")
                    .writer()
                    .enqueueGroup("probe", List.of("p1"), List.of(privmsg("one"), privmsg("two")));

            List<String> lines = probe(alice);

            assertThat(lines).hasSize(4);
            assertThat(lines.get(0)).startsWith("BATCH +").endsWith(" probe p1");
            String ref = lines.get(0).substring("BATCH +".length(), lines.get(0).indexOf(' ', 7));
            assertThat(lines.get(1)).startsWith("@batch=" + ref).contains("PRIVMSG #probe one");
            assertThat(lines.get(2)).startsWith("@batch=" + ref).contains("PRIVMSG #probe two");
            assertThat(lines.get(3)).isEqualTo("BATCH -" + ref);
        }
    }

    @Test
    void aBatchOnlyClientReceivesPlainMembersAndStillNegotiated() throws Exception {
        try (TestServer server = TestServer.start(BATCH_AND_TAGS_YAML);
                RawIrcClient bob = client(server, "bob", "batch")) {
            live(server, "bob")
                    .writer()
                    .enqueueGroup("probe", List.of(), List.of(privmsg("one"), privmsg("two")));

            List<String> lines = probe(bob);

            assertThat(lines)
                    .containsExactly(
                            ":bot!bot@host PRIVMSG #probe one", ":bot!bot@host PRIVMSG #probe two");
        }
    }

    @Test
    void aClientWithNeitherCapabilityReceivesPlainMembers() throws Exception {
        try (TestServer server = TestServer.start(BATCH_AND_TAGS_YAML);
                RawIrcClient carol = client(server, "carol", "")) {
            live(server, "carol")
                    .writer()
                    .enqueueGroup("probe", List.of(), List.of(privmsg("one")));

            assertThat(probe(carol)).containsExactly(":bot!bot@host PRIVMSG #probe one");
        }
    }

    @Test
    void anEmptyGroupIsAnOpenerAndACloserForABatchClient() throws Exception {
        try (TestServer server = TestServer.start(BATCH_AND_TAGS_YAML);
                RawIrcClient dave = client(server, "dave", "batch message-tags")) {
            live(server, "dave").writer().enqueueGroup("empty", List.of(), List.of());

            List<String> lines = probe(dave);

            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).startsWith("BATCH +").endsWith(" empty");
            assertThat(lines.get(1))
                    .isEqualTo("BATCH -" + lines.get(0).substring(7, lines.get(0).indexOf(' ', 7)));
        }
    }

    @Test
    void twoGroupsToOneClientNeverInterleaveAndUseDistinctReferences() throws Exception {
        try (TestServer server = TestServer.start(BATCH_AND_TAGS_YAML);
                RawIrcClient erin = client(server, "erin", "batch message-tags")) {
            ClientSession session = live(server, "erin");
            Thread first =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        for (int i = 0; i < 20; i++) {
                                            session.writer()
                                                    .enqueueGroup(
                                                            "a",
                                                            List.of(),
                                                            List.of(privmsg("a1"), privmsg("a2")));
                                        }
                                    });
            for (int i = 0; i < 20; i++) {
                session.writer()
                        .enqueueGroup("b", List.of(), List.of(privmsg("b1"), privmsg("b2")));
            }
            first.join();

            List<String> lines = probe(erin);

            assertThat(lines).hasSize(40 * 4);
            for (int i = 0; i < lines.size(); i += 4) {
                String ref = lines.get(i).substring(7, lines.get(i).indexOf(' ', 7));
                assertThat(lines.get(i + 1)).startsWith("@batch=" + ref);
                assertThat(lines.get(i + 2)).startsWith("@batch=" + ref);
                assertThat(lines.get(i + 3)).isEqualTo("BATCH -" + ref);
            }
        }
    }

    @Test
    void withTheProviderNotEnabledGroupsAreSentPlain() throws Exception {
        try (TestServer server = TestServer.start("capabilities:\n  message-tags: enabled\n");
                RawIrcClient frank = client(server, "frank", "message-tags")) {
            live(server, "frank")
                    .writer()
                    .enqueueGroup("probe", List.of(), List.of(privmsg("one")));

            assertThat(probe(frank)).containsExactly(":bot!bot@host PRIVMSG #probe one");
        }
    }
}
