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

class Story1UserModeTest {

    @Test
    void selfModeQueryAndUnprivilegedChangesAreHandled() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("MODE alice");
            String umodeis = alice.readUntil("221", Duration.ofSeconds(5));
            assertThat(umodeis).contains("221").contains("+");

            alice.send("MODE alice +o");
            String noPriv = alice.readUntil("481", Duration.ofSeconds(5));
            assertThat(noPriv).contains("481");

            alice.send("MODE alice -o"); // silent no-op, not an error — assert nothing crashes by
            // following with another query
            alice.send("MODE alice +z");
            String unknownFlag = alice.readUntil("501", Duration.ofSeconds(5));
            assertThat(unknownFlag).contains("501");

            alice.send("MODE bob");
            String usersDontMatch = alice.readUntil("502", Duration.ofSeconds(5));
            assertThat(usersDontMatch).contains("502");
        }
    }

    @Test
    void selfModeTargetIsComparedWithTheRfc1459CaseMapping() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("a[b\\c^", "abc");

            client.send("MODE A{B|C~");
            String umodeis = client.readUntil("221", Duration.ofSeconds(5));
            assertThat(umodeis).contains("221");
        }
    }
}
