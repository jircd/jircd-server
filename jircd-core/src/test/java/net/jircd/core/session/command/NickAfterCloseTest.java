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
import net.jircd.core.extension.ExtensionRegistry;
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
 * A NICK already past the reader's isClosing() check can claim after a concurrent disconnect
 * (registration or ping timeout, KILL, sendq) has run cleanup: cleanup saw no nickname and released
 * nothing, so the claim must be rolled back by the handler itself.
 */
class NickAfterCloseTest {

  @Test
  void nickClaimedAfterCleanupHasRunIsRolledBackAndNeverWelcomed() throws Exception {
    NicknameRegistry nicknames = new NicknameRegistry();
    ExtensionRegistry extensions = new ExtensionRegistry();
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            nicknames, new ChannelRegistry(), new WhowasHistory(() -> 10), extensions);
    NickCommandHandler handler =
        new NickCommandHandler(
            nicknames,
            () -> "server",
            () -> 30,
            new RegistrationCompletion(() -> "server", "test", java.time.Instant.now(), extensions),
            extensions);
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    session.setIdent("user"); // USER already processed: the NICK would complete registration
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE);
    session.attachWriter(writer);

    cleanup.cleanup(session, "Registration timeout"); // the whole disconnect precedes the claim
    handler.handle(session, MessageParser.parse("NICK ghost"));
    writer.close();

    assertThat(nicknames.lookup("ghost")).isEmpty();
    assertThat(session.lifecycle().isRegistered()).isFalse();
    assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain(" 001 ");
  }
}
