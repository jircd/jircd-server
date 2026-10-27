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
 * 006-complete-core-protocol Story 2: {@code +t} (topic-lock) gates whether an ordinary member can
 * set the channel topic.
 */
class TopicLockTest {

    @Test
    void ordinaryMemberCanSetTheTopicWithTopicLockOff() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient foo = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bar = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            foo.registerAndAwaitWelcome("foo", "foo"); // operator via first-join
            foo.send("JOIN #chan");
            foo.readUntil("353", Duration.ofSeconds(5));

            bar.registerAndAwaitWelcome("bar", "bar");
            bar.send("JOIN #chan");
            bar.readUntil("353", Duration.ofSeconds(5));
            foo.readUntil("JOIN #chan", Duration.ofSeconds(5));

            bar.send("TOPIC #chan :new topic");
            assertThat(bar.readUntil("TOPIC #chan", Duration.ofSeconds(5))).contains("new topic");
        }
    }

    @Test
    void ordinaryMemberIsRejectedWithTopicLockOnButOperatorAlwaysSucceeds() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient foo = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient bar = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            foo.registerAndAwaitWelcome("foo", "foo"); // operator via first-join
            foo.send("JOIN #chan");
            foo.readUntil("353", Duration.ofSeconds(5));

            bar.registerAndAwaitWelcome("bar", "bar");
            bar.send("JOIN #chan");
            bar.readUntil("353", Duration.ofSeconds(5));
            foo.readUntil("JOIN #chan", Duration.ofSeconds(5));

            foo.send("MODE #chan +t");
            foo.readUntil("MODE #chan +t", Duration.ofSeconds(5));

            bar.send("TOPIC #chan :new topic");
            assertThat(bar.readUntil("482", Duration.ofSeconds(5))).contains("482");

            foo.send("TOPIC #chan :ops always can");
            assertThat(foo.readUntil("TOPIC #chan", Duration.ofSeconds(5)))
                    .contains("ops always can");
        }
    }
}
