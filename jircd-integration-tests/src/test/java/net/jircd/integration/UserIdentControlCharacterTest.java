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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserIdentControlCharacterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(strings = {"id\u0001x", "id\u0085x", "id\tx", "id\u007fx"})
    void controlCharacterIdentFallsBackToUserEverywhere(String ident) throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.send("NICK alice");
            alice.send("USER " + ident + " 0 * :Alice");
            alice.readUntil(" 001 ", TIMEOUT);
            bob.registerAndAwaitWelcome("bob", "bob");

            alice.send("JOIN #ctl");
            String join = alice.readUntil("JOIN", TIMEOUT);
            assertThat(join).startsWith(":alice!user@").doesNotContain(ident);

            bob.send("WHOIS alice");
            String whois = bob.readUntil("311", TIMEOUT);
            assertThat(whois).contains("alice user ").doesNotContain(ident);

            bob.send("WHO alice");
            String who = bob.readUntil("352", TIMEOUT);
            assertThat(who).contains(" user ").doesNotContain(ident);
        }
    }

    @Test
    void truncationDoesNotSplitSurrogatePair() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.send("NICK alice");
            alice.send("USER abcdefgh\uD83D\uDE00 0 * :Alice");
            alice.readUntil(" 001 ", TIMEOUT);
            bob.registerAndAwaitWelcome("bob", "bob");

            bob.send("WHOIS alice");
            String whois = bob.readUntil("311", TIMEOUT);
            assertThat(whois).contains("alice abcdefgh 127.0.0.1").doesNotContain("?", "\uFFFD");
        }
    }
}
