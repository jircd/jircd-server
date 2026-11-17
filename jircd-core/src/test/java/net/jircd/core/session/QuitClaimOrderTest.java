/*
 * Copyright 2026 JIRCD Maintainers
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
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.command.QuitCommandHandler;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/**
 * #151: QUIT claims CLOSING (which stops the connection counting for LUSERS) before it queues the
 * ERROR, so a client that has read the ERROR can never still be counted. The ordering is pinned
 * through its observable consequence: a QUIT that loses the claim queues no ERROR at all, which the
 * old enqueue-then-claim order did not guarantee.
 */
class QuitClaimOrderTest {

    private static DisconnectCleanup cleanup() {
        return new DisconnectCleanup(
                new NicknameRegistry(),
                new ChannelRegistry(),
                new WhowasHistory(() -> 100),
                new ExtensionRegistry());
    }

    private static Message quit() {
        return new Message(Map.of(), null, Command.QUIT, "QUIT", List.of("gone"));
    }

    @Test
    void quitAfterAnotherTriggerClaimedTheSessionQueuesNoError() throws Exception {
        ClientSession session = new ClientSession("c1", "h", RateLimitBucket.withDefaults());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
            session.attachWriter(writer);
            session.lifecycle().claimClosing();

            new QuitCommandHandler(cleanup()).handle(session, quit());

            assertThat(writer.queuedBytes()).isZero();
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("ERROR");
    }

    @Test
    void quitStopsTheConnectionCountingAndSendsErrorOnce() throws Exception {
        ClientSession session = new ClientSession("c2", "h", RateLimitBucket.withDefaults());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
            session.attachWriter(writer);

            new QuitCommandHandler(cleanup()).handle(session, quit());

            assertThat(session.lifecycle().isClosing()).isTrue();
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("ERROR gone\r\n");
    }

    /**
     * The longest QUIT the input limit allows is one byte too long as an ERROR (the command is a
     * byte longer): the session must still be fully released, with an ERROR that fits.
     */
    @Test
    void maxLengthQuitReasonStillReleasesTheSessionAndSendsAFittingError() throws Exception {
        NicknameRegistry nicknames = new NicknameRegistry();
        ChannelRegistry channels = new ChannelRegistry();
        UserStatistics statistics = new UserStatistics();
        DisconnectCleanup cleanup =
                new DisconnectCleanup(
                        nicknames,
                        channels,
                        new WhowasHistory(() -> 100),
                        new ExtensionRegistry(),
                        statistics);
        ClientSession session = new ClientSession("c3", "h", RateLimitBucket.withDefaults());
        session.setNickname("erin");
        session.setIdent("erin");
        session.setRealname("erin");
        nicknames.claim("erin", session);
        session.lifecycle().completeRegistration();
        statistics.userRegistered();
        Channel channel = channels.join("#c", session, ignored -> true).orElseThrow().channel();
        String reason = "x " + "a".repeat(502); // "QUIT :" + reason + CRLF is exactly 512 bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
            session.attachWriter(writer);

            new QuitCommandHandler(cleanup).handle(session, MessageParser.parse("QUIT :" + reason));

            assertThat(session.lifecycle().isClosing()).isTrue();
        }
        assertThat(nicknames.lookup("erin")).isEmpty();
        assertThat(channel.members()).doesNotContain(session);
        assertThat(statistics.registeredCount()).isZero();
        String sent = out.toString(StandardCharsets.UTF_8);
        assertThat(sent).startsWith("ERROR :x aaa").endsWith("\r\n");
        assertThat(sent.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(512);
    }
}
