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
package net.jircd.serverextensions.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.core.session.WhowasHistory;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/**
 * #151: KILL claims the target's CLOSING (which stops it counting) before it queues the target's
 * ERROR, so a target that has read the ERROR can never still be counted. A target another trigger
 * already claimed gets no second ERROR, and the killer is told the nick is gone.
 */
class KillClaimOrderTest {

    private final NicknameRegistry nicknames = new NicknameRegistry();
    private final ExtensionRegistry extensions = enabledAdmin();
    private final KillCommandHandler handler =
            new KillCommandHandler(
                    nicknames,
                    extensions,
                    () -> "server",
                    new DisconnectCleanup(
                            nicknames,
                            new ChannelRegistry(),
                            new WhowasHistory(() -> 100),
                            extensions));

    @Test
    void killAfterAnotherTriggerClaimedTheTargetQueuesNoErrorAndTellsTheKillerNoSuchNick()
            throws Exception {
        ClientSession admin = session("admin");
        admin.grantAdministratorPrivilege();
        ClientSession victim = session("victim");
        nicknames.claim("victim", victim);
        ByteArrayOutputStream adminOut = new ByteArrayOutputStream();
        ByteArrayOutputStream victimOut = new ByteArrayOutputStream();
        try (SessionWriter adminWriter = new SessionWriter(admin, adminOut, TagRenderer.NONE);
                SessionWriter victimWriter =
                        new SessionWriter(victim, victimOut, TagRenderer.NONE)) {
            admin.attachWriter(adminWriter);
            victim.attachWriter(victimWriter);
            victim.lifecycle().claimClosing();

            handler.handle(admin, MessageParser.parse("KILL victim :bye"));
        }
        assertThat(victimOut.toString(StandardCharsets.UTF_8)).doesNotContain("ERROR");
        assertThat(adminOut.toString(StandardCharsets.UTF_8))
                .contains(" 401 admin victim :No such nick/channel")
                .doesNotContain("Killed victim");
    }

    @Test
    void killClaimsTheTargetSendsErrorOnceAndNoticesTheKiller() throws Exception {
        ClientSession admin = session("admin");
        admin.grantAdministratorPrivilege();
        ClientSession victim = session("victim");
        nicknames.claim("victim", victim);
        ByteArrayOutputStream adminOut = new ByteArrayOutputStream();
        ByteArrayOutputStream victimOut = new ByteArrayOutputStream();
        try (SessionWriter adminWriter = new SessionWriter(admin, adminOut, TagRenderer.NONE);
                SessionWriter victimWriter =
                        new SessionWriter(victim, victimOut, TagRenderer.NONE)) {
            admin.attachWriter(adminWriter);
            victim.attachWriter(victimWriter);

            handler.handle(admin, MessageParser.parse("KILL victim :bye"));

            assertThat(victim.lifecycle().isClosing()).isTrue();
        }
        assertThat(victimOut.toString(StandardCharsets.UTF_8)).isEqualTo("ERROR bye\r\n");
        assertThat(adminOut.toString(StandardCharsets.UTF_8)).contains("Killed victim (bye)");
    }

    private static ClientSession session(String nick) {
        ClientSession session =
                new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
        session.setNickname(nick);
        session.setIdent(nick);
        return session;
    }

    private static ExtensionRegistry enabledAdmin() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(
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
        registry.enable(AdminExtension.ID);
        return registry;
    }
}
