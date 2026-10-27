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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class NicknameRegistryTest {

    private ClientSession newSession(String id) {
        return new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
    }

    @Test
    void concurrentClaimOfSameNicknameHasExactlyOneWinner() throws Exception {
        NicknameRegistry registry = new NicknameRegistry();
        int contenders = 50;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var sessions =
                    IntStream.range(0, contenders).mapToObj(i -> newSession("c" + i)).toList();
            var futures =
                    sessions.stream()
                            .map(
                                    session ->
                                            (Future<Boolean>)
                                                    pool.submit(
                                                            () -> registry.claim("Alice", session)))
                            .toList();
            AtomicInteger winners = new AtomicInteger();
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    winners.incrementAndGet();
                }
            }
            assertThat(winners.get()).isEqualTo(1);
        }
    }

    @Test
    void nicknameUniquenessIsCaseInsensitive() {
        NicknameRegistry registry = new NicknameRegistry();
        ClientSession first = newSession("a");
        ClientSession second = newSession("b");

        assertThat(registry.claim("Alice", first)).isTrue();
        assertThat(registry.claim("alice", second)).isFalse();
        assertThat(registry.claim("ALICE", second)).isFalse();
    }

    @Test
    void lookupByAnyCasingResolvesToOriginallyRegisteredCasing() {
        NicknameRegistry registry = new NicknameRegistry();
        ClientSession session = newSession("a");
        session.setNickname("Alice");
        registry.claim("Alice", session);

        assertThat(registry.lookup("alice")).contains(session);
        assertThat(registry.lookup("ALICE")).contains(session);
        assertThat(registry.lookup("alice").get().nickname()).isEqualTo("Alice");
    }
}
