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
package net.jircd.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WhowasHistoryTest {

    private static WhowasEntry entry(String nickname, Instant disconnectedAt) {
        return new WhowasEntry(
                nickname, "ident", "host.example", "host.example", "Real Name", disconnectedAt);
    }

    @Test
    void oldestEntryIsEvictedOnceCapacityIsReached() {
        WhowasHistory history = new WhowasHistory(() -> 2);
        Instant t0 = Instant.parse("2026-08-19T00:00:00Z");

        history.record(entry("alice", t0));
        history.record(entry("bob", t0.plusSeconds(1)));
        history.record(entry("carol", t0.plusSeconds(2))); // evicts alice's entry

        assertThat(history.mostRecentFor("alice")).isEmpty();
        assertThat(history.mostRecentFor("bob")).isPresent();
        assertThat(history.mostRecentFor("carol")).isPresent();
    }

    @Test
    void repeatedNicknameReturnsTheMostRecentEntry() {
        WhowasHistory history = new WhowasHistory(() -> 10);
        Instant t0 = Instant.parse("2026-08-19T00:00:00Z");

        history.record(
                new WhowasEntry("alice", "old-ident", "old.host", "old.host", "Old Name", t0));
        history.record(
                new WhowasEntry(
                        "alice",
                        "new-ident",
                        "new.host",
                        "new.host",
                        "New Name",
                        t0.plusSeconds(5)));

        assertThat(history.mostRecentFor("alice"))
                .isPresent()
                .get()
                .satisfies(e -> assertThat(e.ident()).isEqualTo("new-ident"));
    }

    @Test
    void noMatchReturnsEmpty() {
        WhowasHistory history = new WhowasHistory(() -> 10);
        assertThat(history.mostRecentFor("neverconnected")).isEmpty();
    }

    @Test
    void lookupIsCaseInsensitive() {
        WhowasHistory history = new WhowasHistory(() -> 10);
        history.record(entry("Alice", Instant.parse("2026-08-19T00:00:00Z")));

        assertThat(history.mostRecentFor("alice")).isPresent();
        assertThat(history.mostRecentFor("ALICE")).isPresent();
    }

    @Test
    void mostRecentNForReturnsUpToCountMostRecentEntriesFirst() {
        WhowasHistory history = new WhowasHistory(() -> 10);
        Instant t0 = Instant.parse("2026-08-19T00:00:00Z");

        history.record(entry("erin", t0));
        history.record(entry("erin", t0.plusSeconds(1)));
        history.record(entry("erin", t0.plusSeconds(2)));

        var result = history.mostRecentNFor("erin", 2);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).disconnectedAt()).isEqualTo(t0.plusSeconds(2));
        assertThat(result.get(1).disconnectedAt()).isEqualTo(t0.plusSeconds(1));
    }

    @Test
    void mostRecentNForReturnsEveryRetainedEntryWhenCountIsZeroOrNegative() {
        WhowasHistory history = new WhowasHistory(() -> 10);
        Instant t0 = Instant.parse("2026-08-19T00:00:00Z");

        history.record(entry("erin", t0));
        history.record(entry("erin", t0.plusSeconds(1)));
        history.record(entry("erin", t0.plusSeconds(2)));

        assertThat(history.mostRecentNFor("erin", 0)).hasSize(3);
        assertThat(history.mostRecentNFor("erin", -1)).hasSize(3);
    }
}
