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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Story1RegistrationBurstTest {

    @Test
    void registrationBurstArrivesInOrderEndingInNoMotd() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            client.send("NICK alice");
            client.send("USER alice 0 * :Alice");

            List<String> numerics = new ArrayList<>();
            while (numerics.size() < 6) {
                String line = client.readUntil(" ", Duration.ofSeconds(5));
                String[] parts = line.split(" ", 3);
                numerics.add(parts[1]);
                if ("422".equals(parts[1])) {
                    break;
                }
            }

            assertThat(numerics).containsExactly("001", "002", "003", "004", "005", "422");
        }
    }

    @Test
    void isupportAdvertisesCoreTokens() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            client.send("NICK bob");
            client.send("USER bob 0 * :Bob");
            String isupport = client.readUntil(" 005 ", Duration.ofSeconds(5));

            assertThat(isupport).contains("CASEMAPPING=rfc1459");
            assertThat(isupport).contains("CHANTYPES=#");
            assertThat(isupport).contains("NICKLEN=9");
            assertThat(isupport).contains("CHANNELLEN=50");
            assertThat(isupport).contains("TOPICLEN=390");
            assertThat(isupport).contains("MODES=6");
            assertThat(isupport).contains("UTF8ONLY");
        }
    }
}
