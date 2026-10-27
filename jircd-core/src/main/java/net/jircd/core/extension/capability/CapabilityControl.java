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

import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;

/**
 * The administrative mutex of the {@link CapabilityRuntime}, without its publication methods: what
 * CAP handling, live-session tracking, output capture and group admission need. Actions run here
 * must be short core code that calls no provider and does no socket I/O; appending to one session
 * writer's queue is allowed.
 */
public final class CapabilityControl {

    private final CapabilityRuntime runtime;

    CapabilityControl(CapabilityRuntime runtime) {
        this.runtime = runtime;
    }

    /** Runs {@code action} under the administrative mutex. */
    public void runUnderControl(Runnable action) {
        runtime.runUnderControl(action);
    }

    /** Computes {@code action} under the administrative mutex. */
    public <T> T underControl(Supplier<T> action) {
        return runtime.underControl(action);
    }

    /** The current snapshot and {@code session}'s negotiation, captured together. */
    public CapabilityRuntime.CapturedView capture(ClientSession session) {
        return runtime.capture(session);
    }
}
