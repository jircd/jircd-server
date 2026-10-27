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

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.command.RegistrationCompletion;
import org.junit.jupiter.api.Test;

/**
 * The registered-user counter moves exactly once per session: up when registration wins the
 * CONNECTING to REGISTERED transition, down when disconnect cleanup claims CLOSING from REGISTERED,
 * whatever the cause of the disconnect.
 */
final class UserStatisticsTest {

    private final NicknameRegistry nicknames = new NicknameRegistry();
    private final UserStatistics statistics = new UserStatistics();
    private final ExtensionRegistry extensions = new ExtensionRegistry();
    private final DisconnectCleanup cleanup =
            new DisconnectCleanup(
                    nicknames,
                    new ChannelRegistry(),
                    new WhowasHistory(() -> 10),
                    extensions,
                    statistics);
    private final RegistrationCompletion completion =
            new RegistrationCompletion(
                    () -> "server", "test", Instant.now(), extensions, statistics);

    @Test
    void registrationRaisesCountAndPeakAndDisconnectLowersOnlyTheCount() {
        ClientSession alice = pending("alice");
        ClientSession bob = pending("bob");
        completion.tryComplete(alice);
        completion.tryComplete(bob);
        assertThat(statistics.registeredCount()).isEqualTo(2);

        cleanup.cleanup(alice, "Quit: bye");

        assertThat(statistics.registeredCount()).isEqualTo(1);
        assertThat(statistics.peak()).isEqualTo(2);
    }

    @Test
    void repeatedTryCompleteCountsASessionOnce() {
        ClientSession alice = pending("alice");
        completion.tryComplete(alice);
        completion.tryComplete(alice);

        assertThat(statistics.registeredCount()).isEqualTo(1);
    }

    @Test
    void repeatedCleanupOfARegisteredSessionDecrementsOnce() {
        ClientSession alice = pending("alice");
        completion.tryComplete(alice);

        cleanup.cleanup(alice, "Quit"); // QUIT
        cleanup.cleanup(alice, "Killed by oper"); // a KILL racing the QUIT
        cleanup.cleanup(alice, "Excess Flood");

        assertThat(statistics.registeredCount()).isZero();
    }

    @Test
    void registrationTimeoutOfAnUnregisteredSessionNeverTouchesTheCounter() {
        ClientSession alice = pending("alice");
        completion.tryComplete(alice);
        ClientSession late =
                new ClientSession("late", "host.example", RateLimitBucket.withDefaults());
        attachWriter(late);

        assertThat(late.lifecycle().closeIfUnregistered()).isTrue();
        cleanup.cleanupClaimed(late, "Registration timeout");
        completion.tryComplete(
                late); // a racing NICK/USER finishing after the timeout claimed CLOSING

        assertThat(statistics.registeredCount()).isEqualTo(1);
        assertThat(statistics.peak()).isEqualTo(1);
    }

    @Test
    void anUnregisteredSessionClosedByCleanupNeverTouchesTheCounter() {
        ClientSession never =
                new ClientSession("n", "host.example", RateLimitBucket.withDefaults());
        attachWriter(never);

        cleanup.cleanup(never, "Connection closed");

        assertThat(statistics.registeredCount()).isZero();
    }

    @Test
    void registerAndCloseRacingAcrossManySessionsLeaveNoDrift() throws Exception {
        int sessions = 200;
        List<ClientSession> all = new ArrayList<>();
        for (int i = 0; i < sessions; i++) {
            all.add(pending("u" + i));
        }
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (ClientSession session : all) {
                futures.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    completion.tryComplete(session);
                                    return null;
                                }));
                futures.add(
                        pool.submit(
                                () -> {
                                    go.await();
                                    cleanup.cleanup(session, "Quit");
                                    return null;
                                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(statistics.registeredCount()).isZero();
        assertThat(statistics.peak()).isBetween(0, sessions);
    }

    private ClientSession pending(String nick) {
        ClientSession session =
                new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
        attachWriter(session);
        session.setNickname(nick);
        session.setIdent(nick);
        nicknames.claim(nick, session);
        return session;
    }

    @SuppressWarnings("PMD.CloseResource")
    private static void attachWriter(ClientSession session) {
        session.attachWriter(
                new SessionWriter(session, new ByteArrayOutputStream(), TagRenderer.NONE));
    }
}
