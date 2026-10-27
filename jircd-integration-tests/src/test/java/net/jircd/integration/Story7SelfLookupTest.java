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

class Story7SelfLookupTest {

    @Test
    void selfLookupReturnsRealHostnameEvenWhileCloakObscuresItFromOthers() throws Exception {
        try (TestServer server = TestServer.start(TestServer.CLOAK_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("WHOIS alice");
            String whoisUser = alice.readUntil("311", Duration.ofSeconds(5));
            assertThat(whoisUser).contains("311").doesNotContain("user-");
            alice.readUntil("318", Duration.ofSeconds(5));
        }
    }

    @Test
    void noArgumentWhoisAlsoTargetsSelf() throws Exception {
        try (TestServer server = TestServer.start(TestServer.CLOAK_ENABLED_YAML);
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice");

            alice.send("WHOIS");
            String whoisUser = alice.readUntil("311", Duration.ofSeconds(5));
            assertThat(whoisUser).contains("alice").doesNotContain("user-");
        }
    }
}
