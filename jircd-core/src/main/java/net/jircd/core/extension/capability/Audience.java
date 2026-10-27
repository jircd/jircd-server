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
package net.jircd.core.extension.capability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;

/**
 * Core-owned, lazily collected, deduplicated peers of one committed event. The collection runs at
 * most once, and only if some listener asks for it.
 */
public final class Audience {
    private final Supplier<List<ClientSession>> source;
    private List<ClientSession> peers;

    public Audience(Supplier<List<ClientSession>> source) {
        this.source = Objects.requireNonNull(source);
    }

    public synchronized List<ClientSession> peers() {
        if (peers == null) peers = List.copyOf(new LinkedHashSet<>(source.get()));
        return peers;
    }
}
