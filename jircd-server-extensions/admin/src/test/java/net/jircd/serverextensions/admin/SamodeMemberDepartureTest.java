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
package net.jircd.serverextensions.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SamodeMemberDepartureTest {
    @ParameterizedTest
    @ValueSource(strings = {"+o", "-o"})
    void departedAdminGets441AndRemainingMembersGetNoEcho(String change) throws Exception {
        ChannelRegistry registry = new ChannelRegistry();
        ClientSession admin = session("admin");
        admin.grantAdministratorPrivilege();
        ClientSession peer = session("peer");
        Channel channel = registry.join("#c", admin, ignored -> true).orElseThrow().channel();
        registry.join("#c", peer, ignored -> true);
        CountDownLatch resolved = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        SamodeCommandHandler handler =
                new SamodeCommandHandler(
                        registry,
                        enabledAdmin(),
                        () -> "server",
                        () -> {
                            resolved.countDown();
                            await(resume);
                        });
        ByteArrayOutputStream replies = new ByteArrayOutputStream();
        ByteArrayOutputStream echoes = new ByteArrayOutputStream();
        try (SessionWriter adminWriter = new SessionWriter(admin, replies, TagRenderer.NONE);
                SessionWriter peerWriter = new SessionWriter(peer, echoes, TagRenderer.NONE);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            admin.attachWriter(adminWriter);
            peer.attachWriter(peerWriter);
            var pending =
                    executor.submit(
                            () -> {
                                handler.handle(admin, MessageParser.parse("SAMODE #c " + change));
                                return null;
                            });
            try {
                await(resolved);
                registry.part(channel, admin).orElseThrow();
            } finally {
                resume.countDown();
            }
            pending.get(5, TimeUnit.SECONDS);
        }
        assertThat(replies.toString(StandardCharsets.UTF_8))
                .contains(" 441 admin admin :They aren't on that channel");
        assertThat(echoes.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"+o", "-o"})
    void validAdminStillGetsAnEchoForAnIdempotentChange(String change) throws Exception {
        ChannelRegistry registry = new ChannelRegistry();
        ClientSession admin = session("admin");
        admin.grantAdministratorPrivilege();
        Channel channel = registry.join("#c", admin, ignored -> true).orElseThrow().channel();
        if (change.startsWith("-")) {
            channel.revokeOperator(admin);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SessionWriter writer = new SessionWriter(admin, out, TagRenderer.NONE)) {
            admin.attachWriter(writer);
            new SamodeCommandHandler(registry, enabledAdmin(), () -> "server")
                    .handle(admin, MessageParser.parse("SAMODE #c " + change));
        }
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains(" MODE #c " + change + " admin")
                .doesNotContain(" 441 ");
    }

    private static ExtensionRegistry enabledAdmin() {
        ExtensionRegistry extensions = new ExtensionRegistry();
        extensions.register(
                new ServerExtension() {
                    @Override
                    public String id() {
                        return AdminExtension.ID;
                    }

                    @Override
                    public void start(ServerContext context) {
                        // Stateless test extension: nothing to set up.
                    }

                    @Override
                    public void stop() {
                        // Stateless test extension: nothing to release.
                    }
                });
        extensions.enable(AdminExtension.ID);
        return extensions;
    }

    private static ClientSession session(String nick) {
        ClientSession session =
                new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
        session.setNickname(nick);
        session.setIdent(nick);
        return session;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
