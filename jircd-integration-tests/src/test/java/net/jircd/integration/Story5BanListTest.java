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

class Story5BanListTest {

    @Test
    void banListQueryReturnsEntriesAndAddRemoveNoOpsAreSilent() throws Exception {
        try (TestServer server = TestServer.start();
                RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

            alice.registerAndAwaitWelcome("alice", "alice"); // operator
            alice.send("JOIN #lobby");
            alice.readUntil("353", Duration.ofSeconds(5));

            // a channel with zero bans still closes the query with bare 368
            alice.send("MODE #lobby b");
            assertThat(alice.readUntil("368", Duration.ofSeconds(5))).contains("368");

            alice.send("MODE #lobby +b mask1!*@*");
            alice.readUntil("MODE #lobby +b mask1", Duration.ofSeconds(5));
            alice.send("MODE #lobby +b mask2!*@*");
            alice.readUntil("MODE #lobby +b mask2", Duration.ofSeconds(5));

            alice.send("MODE #lobby b");
            List<String> lines = alice.readLinesFor(Duration.ofSeconds(2));
            long banEntries = lines.stream().filter(l -> l.contains("367")).count();
            assertThat(banEntries).isEqualTo(2);
            assertThat(lines).anyMatch(l -> l.contains("mask1"));
            assertThat(lines).anyMatch(l -> l.contains("mask2"));
            assertThat(lines).anyMatch(l -> l.contains("368"));

            // removing a mask not present is a silent no-op
            alice.send("MODE #lobby -b notpresent!*@*");
            alice.readUntil("MODE #lobby -b notpresent", Duration.ofSeconds(5));
            alice.send("MODE #lobby b");
            List<String> afterNoOpRemoval = alice.readLinesFor(Duration.ofSeconds(2));
            assertThat(afterNoOpRemoval.stream().filter(l -> l.contains("367")).count())
                    .isEqualTo(2);

            // adding a mask already present doesn't grow the list
            alice.send("MODE #lobby +b mask1!*@*");
            alice.readUntil("MODE #lobby +b mask1", Duration.ofSeconds(5));
            alice.send("MODE #lobby b");
            List<String> afterDuplicateAdd = alice.readLinesFor(Duration.ofSeconds(2));
            assertThat(afterDuplicateAdd.stream().filter(l -> l.contains("367")).count())
                    .isEqualTo(2);
        }
    }
}
