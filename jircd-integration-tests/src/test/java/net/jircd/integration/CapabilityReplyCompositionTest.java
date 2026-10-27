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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/**
 * #102 reply and tag composition over real sockets, at the configured limits: composed NAMES, WHO
 * and 366 at the exact 512-byte budget, escaped UTF-8 tag origins counted with the serializer's
 * rules, a group too large for one SendQ, and impossible decorations rejected at enable, reload and
 * startup before anything is published.
 */
class CapabilityReplyCompositionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** S of the reply budgets: the running server name's bytes. */
    private static final int S = "test.jircd.local".length();

    private static final int N = 100;
    private static final int C = 50;

    /** multi-prefix's status bytes (@ and +). */
    private static final int P = 2;

    /** The identity bytes a 353 can still carry: {@code S + 2N + C + 13 + P + I = 512}. */
    private static final int MAX_IDENTITY = 512 - (S + 2 * N + C + 13 + P);

    private static final String LIMITS =
            "capabilities:\n  multi-prefix: enabled\nnicknameMaxLength: "
                    + N
                    + "\nchannelNameMaxLength: "
                    + C
                    + "\n";

    /** Adds {@code !} and padding to each NAMES token, declaring exactly that many bytes. */
    private static final class Identity extends AbstractCapabilityExtension {
        private final int bytes;

        Identity(int bytes) {
            super("identity");
            this.bytes = bytes;
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.onNamesIdentity(
                    limits -> bytes, member -> member.nickname() + "!" + "x".repeat(bytes - 1));
        }
    }

    /** Contributes one server tag whose escaped form is what is bounded. */
    private static final class Tagger extends AbstractCapabilityExtension {
        private final String value;
        private final long bound;
        final CountDownLatch stopped = new CountDownLatch(1);

        Tagger(String value, long bound) {
            super("tagger");
            this.value = value;
            this.bound = bound;
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.onOutboundTags(Set.of("tagger"), bound, message -> Map.of("tagger", value));
        }

        @Override
        public void stop() {
            stopped.countDown();
        }
    }

    private static RawIrcClient client(TestServer server, String nick, String caps)
            throws Exception {
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
        client.send("CAP LS 302");
        if (!caps.isEmpty()) client.send("CAP REQ :" + caps);
        client.send("CAP END");
        client.registerAndAwaitWelcome(nick, "u");
        return client;
    }

    private static int wireBytes(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length + 2; // CRLF
    }

    @Test
    void composedNamesWhoAndEndOfNamesFitExactlyAtTheConfiguredMaximum() throws Exception {
        try (TestServer server = TestServer.start(LIMITS)) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            registry.register(new Identity(MAX_IDENTITY));
            registry.enable("identity");
            String nick = "a".repeat(N);
            String other = "b".repeat(N);
            String channel = "#" + "c".repeat(C - 1);
            try (RawIrcClient alice = client(server, nick, "multi-prefix identity");
                    RawIrcClient bob = client(server, other, "")) {
                alice.send("JOIN " + channel);
                alice.readUntil("366", TIMEOUT);
                alice.send("MODE " + channel + " +v " + nick);
                bob.send("JOIN " + channel);
                bob.readUntil("366", TIMEOUT);
                alice.readThroughPong(TIMEOUT);

                alice.send("NAMES " + channel);
                List<String> names = alice.readThroughPong(TIMEOUT);
                List<String> members = new ArrayList<>();
                for (String line : names) {
                    if (!line.contains(" 353 ")) continue;
                    assertThat(wireBytes(line)).isLessThanOrEqualTo(512);
                    int trailing = line.indexOf(" :", 1);
                    // A lone token needs no ':' prefix.
                    String list =
                            line.substring(trailing < 0 ? line.lastIndexOf(' ') + 1 : trailing + 2);
                    members.addAll(List.of(list.split(" ")));
                }
                String padding = "!" + "x".repeat(MAX_IDENTITY - 1);
                // Whole tokens only, each at the full composed width, split across lines if need
                // be.
                assertThat(members)
                        .containsExactlyInAnyOrder("@+" + nick + padding, other + padding);
                // Alone on its line at the budget's full width; a lone token needs no ':', so 511.
                assertThat(
                                names.stream()
                                        .filter(
                                                l ->
                                                        l.contains(" 353 ")
                                                                && l.contains("@+" + nick)))
                        .singleElement()
                        .isEqualTo(
                                ":test.jircd.local 353 "
                                        + nick
                                        + " = "
                                        + channel
                                        + " @+"
                                        + nick
                                        + padding);
                assertThat(
                                wireBytes(
                                        ":test.jircd.local 353 "
                                                + nick
                                                + " = "
                                                + channel
                                                + " @+"
                                                + nick
                                                + padding))
                        .isEqualTo(511);
                assertThat(names.stream().filter(l -> l.contains(" 366 ")))
                        .singleElement()
                        .asString()
                        .isEqualTo(
                                ":test.jircd.local 366 "
                                        + nick
                                        + " "
                                        + channel
                                        + " :End of /NAMES list");

                alice.send("WHO " + channel);
                List<String> who = alice.readThroughPong(TIMEOUT);
                assertThat(who.stream().filter(l -> l.contains(" 352 ")))
                        .hasSize(2)
                        .allSatisfy(l -> assertThat(wireBytes(l)).isLessThanOrEqualTo(512))
                        .anySatisfy(l -> assertThat(l).contains(" " + nick + " H@+ :0 "));
                assertThat(who.stream().filter(l -> l.contains(" 315 "))).hasSize(1);
            }
        }
    }

    @Test
    void oneByteMoreThanTheBudgetIsRejectedAtEnableAndPublishesNothing() throws Exception {
        try (TestServer server = TestServer.start(LIMITS);
                RawIrcClient observer = client(server, "observer", "")) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            registry.register(new Identity(MAX_IDENTITY + 1));

            assertThatThrownBy(() -> registry.enable("identity"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("353=513");

            assertThat(registry.stateOf("identity")).isEqualTo(Extension.State.FAILED);
            assertThat(registry.capabilitySnapshot().providerIds()).doesNotContain("identity");
            assertThat(observer.readThroughPong(TIMEOUT)).noneMatch(l -> l.contains(" CAP "));
        }
    }

    @Test
    void aReloadThatWouldPushAnEnabledDecorationPastTheBudgetIsRejectedAndChangesNothing()
            throws Exception {
        try (TestServer server = TestServer.start(LIMITS)) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            registry.register(new Identity(MAX_IDENTITY));
            registry.enable("identity");
            Files.writeString(
                    server.configPath,
                    TestServer.baseYaml()
                            + "nicknameMaxLength: "
                            + (N + 1)
                            + "\nchannelNameMaxLength: "
                            + C
                            + "\n");

            assertThatThrownBy(() -> server.application.reloader().reload())
                    .isInstanceOf(ConfigurationException.class);

            assertThat(registry.supportedFeatures().nicknameMaxLength()).isEqualTo(N);
            assertThat(registry.stateOf("identity")).isEqualTo(Extension.State.ENABLED);
        }
    }

    @Test
    void startupRejectsLimitsTheDiscoveredStatusDecorationCannotFit() {
        // One nickname byte more than multi-prefix's two statuses leave room for in one 353.
        int tooLong = (512 - (S + C + 13 + P)) / 2 + 1;
        assertThatThrownBy(
                        () ->
                                TestServer.start(
                                                "nicknameMaxLength: "
                                                        + tooLong
                                                        + "\nchannelNameMaxLength: "
                                                        + C
                                                        + "\n")
                                        .close())
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void tagOriginsAreEscapedExactlyAndBoundedByTheirEscapedUtf8Bytes() throws Exception {
        // Escaped, "a\sb\:c\\d\r\né" is 16 UTF-8 bytes and "tagger=" adds 7: 23 data bytes.
        String value = "a b;c\\d\r\né";
        try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
                RawIrcClient alice = client(server, "alice", "message-tags");
                RawIrcClient bob = client(server, "bob", "message-tags")) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            Tagger tagger = new Tagger(value, 23);
            registry.register(tagger);
            registry.enable("tagger");
            alice.send("CAP REQ :tagger");
            alice.readUntil("ACK", TIMEOUT);

            bob.send("@+utf=é\\sx;+plain=1 PRIVMSG alice :hi");
            String received = alice.readUntil("PRIVMSG alice", TIMEOUT);
            String tags = received.substring(1, received.indexOf(' '));
            assertThat(List.of(tags.split(";")))
                    .contains("+utf=é\\sx", "+plain=1", "tagger=a\\sb\\:c\\\\d\\r\\né")
                    .anyMatch(t -> t.startsWith("msgid="));
            assertThat(registry.stateOf("tagger")).isEqualTo(Extension.State.ENABLED);
        }
    }

    @Test
    void aValueThatFitsRawButNotEscapedIsOutOfBoundAndFailsItsOwner() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = client(server, "alice", "")) {
            ExtensionRegistry registry = server.application.extensionRegistry();
            // "tagger=" + 9 raw bytes is 16, under the bound; escaped spaces make it 25.
            Tagger tagger = new Tagger("         ", 16);
            registry.register(tagger);
            registry.enable("tagger");
            alice.send("CAP REQ :tagger");
            alice.readUntil("ACK", TIMEOUT);
            List<String> seen;
            try (RawIrcClient bob = client(server, "bob", "")) {
                bob.send("PRIVMSG alice :hi");
                seen = Withdrawals.linesThrough(alice, "PRIVMSG alice");
                assertThat(seen.getLast()).doesNotContain("tagger");
            }
            Withdrawals.awaitFailed(registry, "tagger", tagger.stopped);
            // The DEL may come before or after the message whose preparation faulted.
            seen.addAll(alice.readThroughPong(TIMEOUT));
            assertThat(seen).containsOnlyOnce(":test.jircd.local CAP alice DEL tagger");
        }
    }

    @Test
    void aGroupLargerThanTheSendqClosesOnlyThatRecipientWithNoPartialGroup() throws Exception {
        String yaml =
                "capabilities:\n  batch: enabled\n  message-tags: enabled\nsendqLimitBytes: 16384\n";
        try (TestServer server = TestServer.start(yaml);
                RawIrcClient alice = client(server, "alice", "batch message-tags");
                RawIrcClient bob = client(server, "bob", "batch message-tags")) {
            List<Message> large = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                large.add(
                        new Message(
                                Map.of(),
                                "bot!bot@host",
                                Command.PRIVMSG,
                                "PRIVMSG",
                                List.of("#probe", "x".repeat(400))));
            }
            server.application
                    .nicknameRegistry()
                    .lookup("bob")
                    .orElseThrow()
                    .writer()
                    .enqueueGroup("probe", List.of(), large);
            List<String> bobLines = bob.readLinesFor(TIMEOUT);
            assertThat(bobLines)
                    .anyMatch(l -> l.startsWith("ERROR") && l.contains("SendQ exceeded"));
            assertThat(bobLines)
                    .noneMatch(l -> l.startsWith("BATCH") || l.contains("PRIVMSG #probe"));

            server.application
                    .nicknameRegistry()
                    .lookup("alice")
                    .orElseThrow()
                    .writer()
                    .enqueueGroup("probe", List.of(), large.subList(0, 2));
            List<String> aliceLines =
                    alice.readThroughPong(TIMEOUT).stream()
                            .filter(l -> l.contains("BATCH") || l.contains("PRIVMSG #probe"))
                            .toList();
            assertThat(aliceLines).hasSize(4);
            assertThat(aliceLines.getFirst()).startsWith("BATCH +");
            assertThat(aliceLines.getLast()).startsWith("BATCH -");
        }
    }
}
