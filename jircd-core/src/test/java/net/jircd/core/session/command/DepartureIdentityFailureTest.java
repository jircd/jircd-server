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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/**
 * A hostname-display failure while resolving the PART/KICK notification prefix happens before the
 * membership transition, so the failed command leaves membership and privileges untouched.
 */
class DepartureIdentityFailureTest {

    @Test
    void partWithFailingHostnameDisplayKeepsMembershipAndPrivileges() throws Exception {
        try (Fixture f = new Fixture()) {

            assertThatThrownBy(() -> f.part.handle(f.bob, MessageParser.parse("PART #c :bye")))
                    .isInstanceOf(IllegalStateException.class);

            f.assertBobStillMemberWithVoiceAndNoNotice();
        }
    }

    @Test
    void kickWithFailingHostnameDisplayKeepsTargetAndPrivileges() throws Exception {
        try (Fixture f = new Fixture()) {

            assertThatThrownBy(
                            () -> f.kick.handle(f.alice, MessageParser.parse("KICK #c bob :out")))
                    .isInstanceOf(IllegalStateException.class);

            f.assertBobStillMemberWithVoiceAndNoNotice();
        }
    }

    @Test
    void kickerGrantedOperatorAfterThePreCheckStillKeepsTheTargetWhenTheDisplayFails()
            throws Exception {
        try (Fixture f = new Fixture()) {
            f.channel.revokeOperator(f.alice);
            f.channel.grantOperator(f.bob);
            ClientSession carol = session("carol");
            f.registry.join("#c", carol, ignored -> true);
            KickCommandHandler racing =
                    new KickCommandHandler(
                            f.registry,
                            f.extensions,
                            () -> "server",
                            () -> f.channel.grantOperator(f.alice));

            assertThatThrownBy(
                            () -> racing.handle(f.alice, MessageParser.parse("KICK #c carol :out")))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(f.channel.members()).contains(carol);
            assertThat(carol.channelMemberships()).containsExactly(f.channel);
        }
    }

    @Test
    void nonMemberKickerIsRejectedWithoutTheExtensionOrAnyRemoval() throws Exception {
        try (Fixture f = new Fixture()) {
            ClientSession outsider = session("outsider");

            f.kick.handle(outsider, MessageParser.parse("KICK #c bob :out"));

            assertThat(f.channel.members()).containsExactlyInAnyOrder(f.alice, f.bob);
            assertThat(f.hostname.calls.get()).isZero();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ChannelRegistry registry = new ChannelRegistry();
        final ExtensionRegistry extensions = new ExtensionRegistry();
        final FailingHostname hostname = new FailingHostname();
        final ClientSession alice = session("alice");
        final ClientSession bob = session("bob");
        final Channel channel;
        final PartCommandHandler part;
        final KickCommandHandler kick;
        final ByteArrayOutputStream aliceOut = new ByteArrayOutputStream();
        final ByteArrayOutputStream bobOut = new ByteArrayOutputStream();
        final SessionWriter aliceWriter;
        final SessionWriter bobWriter;

        Fixture() {
            channel = registry.join("#c", alice, ignored -> true).orElseThrow().channel();
            registry.join("#c", bob, ignored -> true);
            channel.grantVoice(bob);
            aliceWriter = new SessionWriter(alice, aliceOut, TagRenderer.NONE);
            bobWriter = new SessionWriter(bob, bobOut, TagRenderer.NONE);
            alice.attachWriter(aliceWriter);
            bob.attachWriter(bobWriter);
            extensions.register(hostname);
            extensions.enable("failing-hostname");
            part = new PartCommandHandler(registry, extensions, () -> "server");
            kick = new KickCommandHandler(registry, extensions, () -> "server");
        }

        @Override
        public void close() {
            aliceWriter.close();
            bobWriter.close();
        }

        void assertBobStillMemberWithVoiceAndNoNotice() {
            aliceWriter.close();
            bobWriter.close();
            assertThat(channel.members()).containsExactlyInAnyOrder(alice, bob);
            assertThat(bob.channelMemberships()).containsExactly(channel);
            assertThat(channel.voiced()).containsExactly(bob);
            assertThat(channel.operators()).containsExactly(alice);
            assertThat(aliceOut.toString(StandardCharsets.UTF_8)).isEmpty();
            assertThat(bobOut.toString(StandardCharsets.UTF_8)).isEmpty();
        }
    }

    private static ClientSession session(String nick) {
        ClientSession session =
                new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
        session.setNickname(nick);
        session.setIdent(nick);
        return session;
    }

    private static final class FailingHostname
            implements ServerExtension, HostnameDisplayExtension {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public String id() {
            return "failing-hostname";
        }

        @Override
        public String extensionPoint() {
            return "hostname-display";
        }

        @Override
        public void start(ServerContext context) {
            // Stateless test extension: nothing to set up.
        }

        @Override
        public void stop() {
            // Stateless test extension: nothing to release.
        }

        @Override
        public String display(String hostname) {
            calls.incrementAndGet();
            throw new IllegalStateException("display failed");
        }
    }
}
