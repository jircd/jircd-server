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
import java.util.Arrays;
import java.util.List;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import net.jircd.protocol.MessageSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Negotiation and permitted NAMES identities over real sockets. */
class UserhostInNamesTest {
    static final String CAPABILITY = "userhost-in-names";
    static final String NAMES_ROOM = "NAMES #room";
    private static final String JOIN_ROOM = "JOIN #room";
    private static final String ALICE_IDENTITY = "@alice!ident@127.0.0.1";
    private static final String OWNER_IDENTITY = "@owner!ident@127.0.0.1";
    private static final String VIEWER_IDENTITY = "viewer!user@127.0.0.1";
    private static final String JOIN = "JOIN ";
    private static final String LOOPBACK_HOST = "@127.0.0.1";
    static final Duration WAIT = Duration.ofSeconds(5);
    static final String CAPS =
            "capabilities:\n  userhost-in-names: enabled\n  multi-prefix: enabled\n";

    static void negotiate(RawIrcClient client, String target, String capabilities)
            throws Exception {
        client.send("CAP REQ :" + capabilities);
        assertThat(
                        MessageParser.parse(client.readUntil("CAP " + target + " ACK", WAIT))
                                .params()
                                .getLast())
                .isEqualTo(capabilities);
    }

    static List<Message> replies(RawIrcClient client, String command) throws Exception {
        client.send(command);
        List<Message> result = new ArrayList<>();
        for (String line : client.readThroughPong(WAIT)) {
            result.add(MessageParser.parse(line));
        }
        return result;
    }

    static List<String> tokens(List<Message> replies) {
        return replies.stream()
                .filter(m -> "353".equals(m.rawCommand()))
                .flatMap(m -> Arrays.stream(m.params().getLast().split(" ")))
                .toList();
    }

    static List<String> names(RawIrcClient client, String command) throws Exception {
        List<Message> result = replies(client, command);
        assertThat(result.stream().filter(m -> "366".equals(m.rawCommand())))
                .singleElement()
                .satisfies(m -> assertThat(m.params().getLast()).isEqualTo("End of /NAMES list"));
        return tokens(result);
    }

    static void oper(RawIrcClient client) throws Exception {
        client.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
        client.readUntil("381", WAIT);
    }

    @Test
    void preAndPostRegistrationNegotiationAndRemovalAffectOnlyRequester() throws Exception {
        try (TestServer server = TestServer.start(CAPS);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.send("CAP LS 302");
            assertThat(alice.readUntil("CAP * LS", WAIT)).contains(CAPABILITY);
            negotiate(alice, "*", CAPABILITY);
            alice.send("CAP END");
            alice.registerAndAwaitWelcome("alice", "ident");
            assertThat(names(alice, JOIN_ROOM)).containsExactly(ALICE_IDENTITY);
            bob.registerAndAwaitWelcome("bob", "other");
            assertThat(names(bob, JOIN_ROOM)).containsExactlyInAnyOrder("@alice", "bob");
            negotiate(bob, "bob", CAPABILITY);
            assertThat(names(bob, NAMES_ROOM))
                    .containsExactlyInAnyOrder(ALICE_IDENTITY, "bob!other@127.0.0.1");
            assertThat(replies(bob, "CAP LIST").stream().filter(m -> "CAP".equals(m.rawCommand())))
                    .singleElement()
                    .satisfies(m -> assertThat(m.params().getLast()).isEqualTo(CAPABILITY));
            negotiate(bob, "bob", "-userhost-in-names");
            assertThat(names(bob, NAMES_ROOM)).containsExactlyInAnyOrder("@alice", "bob");
            assertThat(names(alice, NAMES_ROOM))
                    .containsExactlyInAnyOrder(ALICE_IDENTITY, "bob!other@127.0.0.1");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void namesUsesSameDisplayHostAsWhoisAndNeverExposesCloakedRealHost(boolean cloak)
            throws Exception {
        try (TestServer server =
                        TestServer.start(CAPS + (cloak ? TestServer.CLOAK_ENABLED_YAML : ""));
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient observer = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("alice", "ident");
            negotiate(client, "alice", CAPABILITY);
            observer.registerAndAwaitWelcome("observer", "observer");
            List<Message> whois = replies(observer, "WHOIS alice");
            String host =
                    whois.stream()
                            .filter(m -> "311".equals(m.rawCommand()))
                            .findFirst()
                            .orElseThrow()
                            .params()
                            .get(3);
            assertThat(names(client, JOIN_ROOM)).containsExactly("@alice!ident@" + host);
            negotiate(observer, "observer", CAPABILITY);
            List<String> peerNames = names(observer, NAMES_ROOM);
            assertThat(peerNames).containsExactly("@alice!ident@" + host);
            if (cloak) {
                assertThat(peerNames).singleElement().asString().doesNotContain("127.0.0.1");
                assertThat(host).doesNotContain("127.0.0.1").contains("user-");
            } else {
                assertThat(host).isEqualTo("127.0.0.1");
            }
            List<Message> who = replies(observer, "WHO #room");
            assertThat(who.stream().filter(m -> "352".equals(m.rawCommand())))
                    .singleElement()
                    .satisfies(
                            m -> {
                                assertThat(m.params()).hasSize(8);
                                assertThat(m.params().get(2)).isEqualTo("ident");
                                assertThat(m.params().get(3)).isEqualTo(host);
                                assertThat(m.params().get(5)).isEqualTo("alice");
                                assertThat(m.params().get(6)).isEqualTo("H@");
                            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"+s", "+p"})
    void bareNamesSajoinAndVisibilityRetainCompletionAndAuthorization(String hiddenMode)
            throws Exception {
        try (TestServer server = TestServer.start(CAPS + TestServer.adminEnabledYaml());
                RawIrcClient owner = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient viewer = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            owner.registerAndAwaitWelcome("owner", "ident");
            names(owner, "JOIN #secret");
            owner.send("MODE #secret " + hiddenMode);
            owner.readUntil("MODE #secret " + hiddenMode, WAIT);
            viewer.registerAndAwaitWelcome("viewer", "user");
            negotiate(viewer, "viewer", CAPABILITY);
            assertThat(tokens(replies(viewer, "NAMES"))).doesNotContain(OWNER_IDENTITY);
            assertThat(names(viewer, "NAMES #secret")).isEmpty();
            assertThat(names(viewer, "NAMES #missing")).isEmpty();
            oper(viewer);
            assertThat(names(viewer, "SAJOIN #secret"))
                    .containsExactlyInAnyOrder(OWNER_IDENTITY, VIEWER_IDENTITY);
            assertThat(names(viewer, "NAMES #secret"))
                    .containsExactlyInAnyOrder(OWNER_IDENTITY, VIEWER_IDENTITY);
            assertThat(tokens(replies(viewer, "NAMES"))).contains(OWNER_IDENTITY, VIEWER_IDENTITY);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "multi-prefix", CAPABILITY, "multi-prefix userhost-in-names"})
    void statusAndIdentityComposeIndependently(String capabilities) throws Exception {
        try (TestServer server = TestServer.start(CAPS);
                RawIrcClient owner = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient viewer = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            owner.registerAndAwaitWelcome("owner", "界界");
            names(owner, JOIN_ROOM);
            owner.send("MODE #room +v owner");
            owner.readUntil("MODE #room +v owner", WAIT);
            viewer.registerAndAwaitWelcome("viewer", "user");
            if (!capabilities.isEmpty()) {
                negotiate(viewer, "viewer", capabilities);
            }
            String prefix = capabilities.contains("multi-prefix") ? "@+" : "@";
            String identity = capabilities.contains(CAPABILITY) ? "owner!界界@127.0.0.1" : "owner";
            assertThat(names(viewer, NAMES_ROOM)).containsExactly(prefix + identity);
            assertThat(
                            replies(viewer, "WHO #room").stream()
                                    .filter(m -> "352".equals(m.rawCommand())))
                    .singleElement()
                    .satisfies(
                            m -> {
                                assertThat(m.params().get(5)).isEqualTo("owner");
                                assertThat(m.params().get(6)).isEqualTo("H" + prefix);
                            });
        }
    }

    @Test
    void socketWalkthroughSplitsCompleteMultibyteTokensAndEndsListing() throws Exception {
        try (TestServer server = TestServer.start(CAPS + "channelNameMaxLength: 350\n");
                RawIrcClient owner = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient peer = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient viewer = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            String channel = "#" + "界".repeat(116);
            String ident = "界".repeat(9);
            owner.registerAndAwaitWelcome("member001", ident);
            names(owner, JOIN + channel);
            owner.send("MODE " + channel + " +v member001");
            owner.readUntil("MODE " + channel + " +v member001", WAIT);
            peer.registerAndAwaitWelcome("member002", ident);
            names(peer, JOIN + channel);
            viewer.registerAndAwaitWelcome("member003", ident);
            negotiate(viewer, "member003", "multi-prefix userhost-in-names");
            List<Message> result = replies(viewer, JOIN + channel);
            assertThat(result.stream().filter(m -> "353".equals(m.rawCommand()))).hasSize(2);
            assertThat(tokens(result))
                    .containsExactlyInAnyOrder(
                            "@+member001!" + ident + LOOPBACK_HOST,
                            "member002!" + ident + LOOPBACK_HOST,
                            "member003!" + ident + LOOPBACK_HOST);
            assertThat(result.stream().filter(m -> "366".equals(m.rawCommand())))
                    .singleElement()
                    .satisfies(
                            m -> assertThat(m.params().getLast()).isEqualTo("End of /NAMES list"));
            assertThat(result)
                    .isNotEmpty()
                    .allSatisfy(
                            m ->
                                    assertThat(MessageSerializer.commandBytes(m))
                                            .isLessThanOrEqualTo(512));
        }
    }
}
