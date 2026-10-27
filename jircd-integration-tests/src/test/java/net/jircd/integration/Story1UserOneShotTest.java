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

class Story1UserOneShotTest {

    @Test
    void userAfterRegistrationIsRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.registerAndAwaitWelcome("alice", "alice");

            client.send("USER alice2 0 * :Someone else");
            String reply = client.readUntil("462", Duration.ofSeconds(5));
            assertThat(reply).contains("462");
        }
    }

    @Test
    void secondUserBeforeRegistrationCompletesIsAlsoRejected() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            client.send("USER first 0 * :First");
            client.send("USER second 0 * :Second");
            String reply = client.readUntil("462", Duration.ofSeconds(5));
            assertThat(reply).contains("462");
        }
    }
}
