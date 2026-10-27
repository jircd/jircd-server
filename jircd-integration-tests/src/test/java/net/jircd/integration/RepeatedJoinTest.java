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
import java.util.List;
import org.junit.jupiter.api.Test;

/** A JOIN for a channel the client is already in changes nothing and notifies no one. */
class RepeatedJoinTest {

    private static final Duration QUIET = Duration.ofMillis(500);

    @Test
    void aSecondJoinCommandForAnExistingMembershipIsSilentForEveryone() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #chan");
            alice.readUntil("366", Duration.ofSeconds(5));
            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("JOIN #chan");
            bob.readUntil("366", Duration.ofSeconds(5));
            alice.readUntil("JOIN #chan", Duration.ofSeconds(5));

            bob.send("JOIN #chan");

            assertThat(bob.readLinesFor(QUIET)).isEmpty();
            assertThat(alice.readLinesFor(QUIET)).isEmpty();
        }
    }

    @Test
    void aRepeatedChannelNameInOneCommaSeparatedJoinIsJoinedOnce() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #chan");
            alice.readUntil("366", Duration.ofSeconds(5));
            bob.registerAndAwaitWelcome("bob", "bob");

            bob.send("JOIN #chan,#CHAN,#other,#chan");

            List<String> bobLines = bob.readLinesFor(QUIET);
            assertThat(bobLines.stream().filter(l -> l.contains(" JOIN #chan")).count())
                    .isEqualTo(1);
            assertThat(bobLines.stream().filter(l -> l.contains(" 366 ")).count()).isEqualTo(2);
            List<String> aliceLines = alice.readLinesFor(QUIET);
            assertThat(aliceLines.stream().filter(l -> l.contains(" JOIN ")).count()).isEqualTo(1);
        }
    }

    @Test
    void aRepeatedJoinAfterTheAdmissionGatesTightenProducesNoError() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice");
            alice.send("JOIN #chan");
            alice.readUntil("366", Duration.ofSeconds(5));
            bob.registerAndAwaitWelcome("bob", "bob");
            bob.send("JOIN #chan");
            bob.readUntil("366", Duration.ofSeconds(5));

            alice.send("MODE #chan +ilk 1 secret");
            alice.readUntil("MODE #chan", Duration.ofSeconds(5));
            alice.readLinesFor(QUIET);
            bob.readLinesFor(QUIET);

            bob.send("JOIN #chan");
            bob.send("JOIN #chan wrongkey");

            assertThat(bob.readLinesFor(QUIET)).isEmpty();
            assertThat(alice.readLinesFor(QUIET)).isEmpty();
        }
    }
}
