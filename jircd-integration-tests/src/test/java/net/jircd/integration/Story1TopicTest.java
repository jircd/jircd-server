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

class Story1TopicTest {

    @Test
    void anyClientCanViewOperatorSetsNonOperatorRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bob = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            alice.registerAndAwaitWelcome("alice", "alice"); // first joiner -> operator
            bob.registerAndAwaitWelcome("bob", "bob");
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));
            bob.send("JOIN #lobby");
            bob.readUntil("353", Duration.ofSeconds(5));

            bob.send("TOPIC #lobby");
            String noTopic = bob.readUntil("331", Duration.ofSeconds(5));
            assertThat(noTopic).contains("331");

            // 006-complete-core-protocol FR-008/FR-009 — topic-setting is only operator-gated while
            // topic-lock (+t) is active; a freshly-created channel defaults to +t off, so this
            // scenario now needs +t explicitly set to exercise the non-operator-rejected case.
            alice.send("MODE #lobby +t");
            alice.readUntil("MODE #lobby +t", Duration.ofSeconds(5));

            bob.send("TOPIC #lobby :bob's topic");
            String rejected = bob.readUntil("482", Duration.ofSeconds(5));
            assertThat(rejected).contains("482");

            alice.send("TOPIC #lobby :alice's topic");
            String topicChange = bob.readUntil("TOPIC #lobby", Duration.ofSeconds(5));
            assertThat(topicChange).contains("alice's topic");
        }
    }
}
