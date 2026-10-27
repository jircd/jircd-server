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
import org.junit.jupiter.api.Test;

/**
 * The {@code SAJOIN}-bypass half of T106 is deferred to Story 6 (Phase 7), which is what actually
 * implements {@code SAJOIN} and administrator privilege (T138) — this covers everything else: a ban
 * blocking {@code JOIN}, and lifting it restoring access.
 */
class Story5BanJoinTest {

    @Test
    void banMaskBlocksJoinAndRemovingItRestoresAccess() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            alice.send("MODE #lobby +b bob!*@*");
            alice.readUntil("MODE #lobby +b bob", Duration.ofSeconds(5));

            bob.send("JOIN #lobby");
            assertThat(bob.readUntil("474", Duration.ofSeconds(5))).contains("474");

            alice.send("MODE #lobby -b bob!*@*");
            alice.readUntil("MODE #lobby -b bob", Duration.ofSeconds(5));

            bob.send("JOIN #lobby");
            assertThat(bob.readUntil("353", Duration.ofSeconds(5))).contains("353");
        }
    }
}
