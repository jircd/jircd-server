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
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.UserMode;
import net.jircd.core.session.UserStatistics;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/**
 * {@code LUSERS} numerics 251–255, 265, 266 in the order modern.ircdocs.horse lists them, mirroring
 * irctest {@code lusers.py testLusersFull}: the local/global counts equal the registered users, the
 * max never shrinks, and {@code 253} counts the connections that have not registered yet.
 */
final class LusersCommandHandlerTest {

    private final NicknameRegistry nicknames = new NicknameRegistry();
    private final ChannelRegistry channels = new ChannelRegistry();
    private final UserStatistics statistics = new UserStatistics();
    private final AtomicInteger unregistered = new AtomicInteger();
    private final LusersCommandHandler handler =
            new LusersCommandHandler(
                    nicknames, channels, statistics, unregistered::get, () -> "server");

    @Test
    void repliesFollowTheDocumentedNumericOrder() throws Exception {
        ClientSession alice = register("alice");
        unregistered.set(1);

        List<Message> replies = lusers(alice);

        assertThat(replies.stream().map(Message::rawCommand))
                .containsExactly("251", "252", "253", "254", "255", "265", "266");
    }

    @Test
    void localAndGlobalUsersReportCurrentAndMax() throws Exception {
        register("alice");
        ClientSession bob = register("bob");

        List<Message> replies = lusers(bob);

        assertThat(byCode(replies, "265").params())
                .containsExactly("bob", "2", "2", "Current local users 2, max 2");
        assertThat(byCode(replies, "266").params())
                .containsExactly("bob", "2", "2", "Current global users 2, max 2");
    }

    @Test
    void maxIsAHighWaterMarkThatSurvivesDisconnects() throws Exception {
        ClientSession alice = register("alice");
        ClientSession bob = register("bob");
        ClientSession carol = register("carol");
        for (ClientSession gone : List.of(bob, carol)) {
            gone.lifecycle().closeIfNotAlreadyClosing();
            statistics.userLeft();
            nicknames.release(gone.nickname(), gone);
        }

        List<Message> replies = lusers(alice);

        assertThat(byCode(replies, "265").params())
                .containsExactly("alice", "1", "3", "Current local users 1, max 3");
        assertThat(byCode(replies, "266").params())
                .containsExactly("alice", "1", "3", "Current global users 1, max 3");
    }

    @Test
    void unknownConnectionsAreReportedWhenAnyExist() throws Exception {
        ClientSession alice = register("alice");
        unregistered.set(2);

        Message unknown = byCode(lusers(alice), "253");

        assertThat(unknown.params()).containsExactly("alice", "2", "unknown connection(s)");
    }

    @Test
    void unknownConnectionsAreOmittedWhenNoneExist() throws Exception {
        ClientSession alice = register("alice");

        assertThat(lusers(alice).stream().map(Message::rawCommand)).doesNotContain("253");
    }

    @Test
    void usersThatClaimedANickButNotRegisteredAreNotCounted() throws Exception {
        ClientSession alice = register("alice");
        ClientSession pending = newSession("pending");
        pending.setNickname("pending");
        nicknames.claim("pending", pending);

        List<Message> replies = lusers(alice);

        assertThat(byCode(replies, "251").params().getLast())
                .isEqualTo("There are 1 users and 0 invisible on 1 servers");
        assertThat(byCode(replies, "255").params().getLast())
                .isEqualTo("I have 1 clients and 1 servers");
        assertThat(byCode(replies, "265").params()).contains("1");
    }

    @Test
    void invisibleUsersAreCountedSeparately() throws Exception {
        ClientSession alice = register("alice");
        alice.setUserMode(UserMode.INVISIBLE);

        Message client = byCode(lusers(alice), "251");

        assertThat(client.params().getLast())
                .isEqualTo("There are 1 users and 1 invisible on 1 servers");
    }

    @Test
    void aSessionPresentUnderTwoNicknameKeysDuringARenameIsCountedOnce() throws Exception {
        ClientSession alice = register("alice");
        alice.setUserMode(UserMode.INVISIBLE);
        alice.grantAdministratorPrivilege();
        // NickCommandHandler claims the new key before releasing the old one.
        assertThat(nicknames.claim("alicia", alice)).isTrue();
        assertThat(nicknames.all()).hasSize(2);

        List<Message> replies = lusers(alice);

        assertThat(byCode(replies, "251").params().getLast())
                .isEqualTo("There are 1 users and 1 invisible on 1 servers");
        assertThat(byCode(replies, "252").params())
                .containsExactly("alice", "1", "operator(s) online");
    }

    private ClientSession register(String nick) {
        ClientSession session = newSession(nick);
        session.setNickname(nick);
        nicknames.claim(nick, session);
        session.setIdent(nick);
        assertThat(session.lifecycle().completeRegistration()).isTrue();
        statistics.userRegistered();
        return session;
    }

    private static ClientSession newSession(String id) {
        return new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
    }

    private List<Message> lusers(ClientSession session) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE)) {
            session.attachWriter(writer);
            handler.handle(session, MessageParser.parse("LUSERS"));
        }
        List<Message> replies = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\r\n")) {
            replies.add(MessageParser.parse(line));
        }
        return replies;
    }

    private static Message byCode(List<Message> replies, String code) {
        return replies.stream().filter(m -> m.rawCommand().equals(code)).findFirst().orElseThrow();
    }
}
