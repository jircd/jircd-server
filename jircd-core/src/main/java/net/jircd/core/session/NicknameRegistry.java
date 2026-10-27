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

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide nickname uniqueness (FR-002), compared using the rfc1459 casemapping (FR-052) — the
 * original casing a client registered with is stored, never a folded form. Claims are resolved
 * atomically: exactly one winner on a concurrent claim, no window where two sessions hold the same
 * name. Its public surface is intentionally narrow so the uniqueness scope can later widen from
 * server-local to network-wide (FR-022) without callers changing.
 */
public final class NicknameRegistry {

    private final Map<String, ClientSession> byFoldedNickname = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} if this session now holds {@code nickname}; {@code false} if already
     * claimed by another.
     */
    public boolean claim(String nickname, ClientSession session) {
        String folded = CaseMapping.fold(nickname);
        ClientSession previousOwner = byFoldedNickname.get(folded);
        if (previousOwner == session) {
            return true; // re-registering the same nickname it already holds is a no-op success
        }
        ClientSession winner = byFoldedNickname.putIfAbsent(folded, session);
        return winner == null;
    }

    public void release(String nickname, ClientSession session) {
        if (nickname == null) {
            return;
        }
        byFoldedNickname.remove(CaseMapping.fold(nickname), session);
    }

    public Optional<ClientSession> lookup(String nickname) {
        return Optional.ofNullable(byFoldedNickname.get(CaseMapping.fold(nickname)));
    }

    /** Every currently-registered session (FR-061's bare/mask {@code WHO} forms). */
    public Collection<ClientSession> all() {
        return byFoldedNickname.values();
    }
}
