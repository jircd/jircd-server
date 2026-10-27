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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;

/**
 * Per-connection keep-alive (FR-039): sends a server-initiated {@code PING} after a configured idle
 * interval, and — if no {@code PONG} arrives within a configured timeout — sends {@code ERROR} and
 * drives the session to {@code CLOSING} via {@link DisconnectCleanup}. Also enforces the
 * registration timeout: a session that has not completed registration (including one parked in CAP
 * negotiation) within it receives {@code ERROR} and is closed, however many PINGs it answers.
 * Driven by an injectable clock rather than real time so tests are deterministic.
 */
public final class LivenessMonitor {

    private final ClientSession session;
    private final DisconnectCleanup disconnectCleanup;
    private final Duration idleInterval;
    private final Duration timeout;
    private final Duration registrationTimeout;
    private final Instant connectedAt;
    private final Clock clock;

    private volatile boolean pingOutstanding;
    private volatile Instant pingSentAt;

    /** Without a registration timeout: an unregistered session is only subject to keep-alive. */
    public LivenessMonitor(
            ClientSession session,
            DisconnectCleanup disconnectCleanup,
            Duration idleInterval,
            Duration timeout,
            Clock clock) {
        this(session, disconnectCleanup, idleInterval, timeout, null, clock);
    }

    /** The registration deadline runs from monitor construction. */
    public LivenessMonitor(
            ClientSession session,
            DisconnectCleanup disconnectCleanup,
            Duration idleInterval,
            Duration timeout,
            Duration registrationTimeout,
            Clock clock) {
        this(
                session,
                disconnectCleanup,
                idleInterval,
                timeout,
                registrationTimeout,
                clock.instant(),
                clock);
    }

    /** {@code connectedAt} is when the connection was accepted, which the deadline runs from. */
    public LivenessMonitor(
            ClientSession session,
            DisconnectCleanup disconnectCleanup,
            Duration idleInterval,
            Duration timeout,
            Duration registrationTimeout,
            Instant connectedAt,
            Clock clock) {
        this.registrationTimeout = registrationTimeout;
        this.connectedAt = connectedAt;
        this.session = session;
        this.disconnectCleanup = disconnectCleanup;
        this.idleInterval = idleInterval;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** Evaluates whether to send a PING or time out the connection, based on the current clock. */
    public void checkNow() {
        Instant now = clock.instant();
        if (registrationExpired(now)) {
            // Claim CLOSING first, only if still unregistered: a registration completing
            // concurrently either wins here (and we do nothing) or loses and sends no welcome after
            // our ERROR.
            if (session.lifecycle().closeIfUnregistered()) {
                enqueueError("Registration timeout");
                disconnectCleanup.cleanupClaimed(session, "Registration timeout");
            }
            return;
        }
        if (pingOutstanding) {
            if (Duration.between(pingSentAt, now).compareTo(timeout) >= 0) {
                timeOut("Ping timeout");
            }
            return;
        }
        if (Duration.between(session.lastLivenessAt(), now).compareTo(idleInterval) >= 0) {
            sendPing(now);
        }
    }

    /**
     * How long the liveness loop may sleep before the next check: the regular tick, shortened to
     * reach the next idle PING and, while the session is unregistered, its registration deadline.
     */
    public Duration nextCheckDelay(Duration tick) {
        Duration delay = tick;
        if (pingOutstanding) {
            // A PONG can clear the ping at any moment; the next one is then due within an interval.
            delay = shorter(delay, idleInterval);
        } else {
            delay =
                    shorter(
                            delay,
                            idleInterval.minus(
                                    Duration.between(session.lastLivenessAt(), clock.instant())));
        }
        if (registrationTimeout != null && !session.lifecycle().isRegistered()) {
            delay =
                    shorter(
                            delay,
                            registrationTimeout.minus(
                                    Duration.between(connectedAt, clock.instant())));
        }
        return delay;
    }

    /** The smaller of the two, but never less than a millisecond so the loop cannot spin. */
    private static Duration shorter(Duration current, Duration candidate) {
        Duration floor = Duration.ofMillis(1);
        Duration bounded = candidate.compareTo(floor) < 0 ? floor : candidate;
        return bounded.compareTo(current) < 0 ? bounded : current;
    }

    private boolean registrationExpired(Instant now) {
        return registrationTimeout != null
                && !session.lifecycle().isRegistered()
                && Duration.between(connectedAt, now).compareTo(registrationTimeout) >= 0;
    }

    /** Called when this connection's PONG (answering our own PING) is received. */
    public void onPongReceived() {
        pingOutstanding = false;
        session.markAliveAt(clock.instant());
    }

    private void sendPing(Instant now) {
        pingOutstanding = true;
        pingSentAt = now;
        // Borrowed reference to the session's own still-active writer — not this
        // monitor's to close; the session's connection lifecycle owns it.
        @SuppressWarnings("PMD.CloseResource")
        SessionWriter writer = session.writer();
        if (writer != null) {
            writer.enqueueRaw(
                    new Message(
                            Map.of(),
                            null,
                            Command.PING,
                            "PING",
                            java.util.List.of(session.connectionId())));
        }
    }

    private void timeOut(String reason) {
        enqueueError(reason);
        disconnectCleanup.cleanup(session, reason);
    }

    private void enqueueError(String reason) {
        // Borrowed reference to the session's own writer, closed by the disconnect cleanup that
        // follows, not by this method.
        @SuppressWarnings("PMD.CloseResource")
        SessionWriter writer = session.writer();
        if (writer != null) {
            writer.enqueueRaw(
                    new Message(Map.of(), null, Command.ERROR, "ERROR", java.util.List.of(reason)));
        }
    }
}
