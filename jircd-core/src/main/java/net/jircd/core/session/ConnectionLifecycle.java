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

import java.util.concurrent.atomic.AtomicReference;

/** The {@code CONNECTING} → {@code REGISTERED} → {@code CLOSING} state machine (FR-001). */
public final class ConnectionLifecycle {

    public enum State {
        CONNECTING,
        REGISTERED,
        CLOSING
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.CONNECTING);

    public State get() {
        return state.get();
    }

    public boolean isRegistered() {
        return state.get() == State.REGISTERED;
    }

    public boolean isClosing() {
        return state.get() == State.CLOSING;
    }

    /**
     * CONNECTING -&gt; REGISTERED. Returns {@code true} only for the call that made the transition,
     * so a caller never sends the welcome burst to a session that is already REGISTERED or CLOSING.
     */
    public boolean completeRegistration() {
        return state.compareAndSet(State.CONNECTING, State.REGISTERED);
    }

    /**
     * CONNECTING -&gt; CLOSING, atomically: {@code true} only if the session was still
     * unregistered. Claims the same transition as {@link #closeIfNotAlreadyClosing()}, so exactly
     * one of a racing registration and registration timeout wins.
     */
    public boolean closeIfUnregistered() {
        return state.compareAndSet(State.CONNECTING, State.CLOSING);
    }

    /**
     * Any state -&gt; CLOSING, atomically claimed by at most one caller — {@code true} only for
     * whichever call actually performed the transition (the session was not already {@code
     * CLOSING}), {@code false} for every other call, whether truly concurrent or simply later.
     * {@link net.jircd.core.session.DisconnectCleanup#cleanup} uses this to stay idempotent: two
     * disconnect triggers racing for the same session (e.g. a client-sent {@code QUIT} racing an
     * abrupt TCP-level close of the same socket) must run cleanup exactly once, not once per
     * trigger.
     */
    public boolean closeIfNotAlreadyClosing() {
        return claimClosing() != State.CLOSING;
    }

    /**
     * Any state -&gt; CLOSING, returning the state it replaced; {@link State#CLOSING} means another
     * caller already claimed it. Lets the one winner tell whether the session was {@code
     * REGISTERED}.
     */
    public State claimClosing() {
        return state.getAndSet(State.CLOSING);
    }
}
