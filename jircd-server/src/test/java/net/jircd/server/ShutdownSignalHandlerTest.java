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
package net.jircd.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * TERM/INT handling without real signals (spec 016 FR-009); the container suite sends real ones.
 */
class ShutdownSignalHandlerTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final ShutdownSignalHandler handler =
            new ShutdownSignalHandler(status -> events.add("exit " + status));

    @Test
    void aSignalShutsTheServerDownThenExitsZero() {
        handler.attach(() -> events.add("shutdown"));

        handler.onSignal("TERM");

        assertThat(events).containsExactly("shutdown", "exit 0");
    }

    @Test
    void aSecondSignalIsIgnored() {
        handler.attach(() -> events.add("shutdown"));

        handler.onSignal("TERM");
        handler.onSignal("INT");

        assertThat(events).containsExactly("shutdown", "exit 0");
    }

    @Test
    void aSignalBeforeTheServerExistsStillExitsZero() {
        handler.onSignal("INT");

        assertThat(events).containsExactly("exit 0");
    }

    @Test
    void aFailingShutdownStillExitsZero() {
        handler.attach(
                () -> {
                    events.add("shutdown");
                    throw new IllegalStateException("listener close failed");
                });

        handler.onSignal("TERM");

        assertThat(events).containsExactly("shutdown", "exit 0");
    }
}
