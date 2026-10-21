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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LivenessMonitorTest {

  private final List<SessionWriter> writersToClose = new ArrayList<>();

  @AfterEach
  void closeWriters() {
    writersToClose.forEach(SessionWriter::close);
    writersToClose.clear();
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant start) {
      this.instant = start;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private ClientSession newSession(MutableClock clock) {
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    // Closed in closeWriters() (@AfterEach) via writersToClose — PMD's CloseResource
    // check doesn't track a resource escaping into a field for later cleanup.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer =
        new SessionWriter(session, java.io.OutputStream.nullOutputStream(), TagRenderer.NONE);
    session.attachWriter(writer);
    writersToClose.add(writer);
    // Align the session's liveness timestamp exactly with the fake clock's start —
    // ClientSession's own field initializer otherwise reads the real wall clock, which
    // (however briefly) drifts from an independently-constructed MutableClock.
    session.markAliveAt(clock.instant());
    return session;
  }

  @Test
  void idleBeyondIntervalTriggersAPing() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session, cleanup, Duration.ofSeconds(60), Duration.ofSeconds(30), clock);
    session.attachLivenessMonitor(monitor);

    clock.advance(Duration.ofSeconds(60));
    monitor.checkNow();

    assertThat(session.lifecycle().isClosing()).isFalse(); // PING sent, not yet timed out
  }

  @Test
  void pongResetsTheTimer() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session, cleanup, Duration.ofSeconds(60), Duration.ofSeconds(30), clock);
    session.attachLivenessMonitor(monitor);

    clock.advance(Duration.ofSeconds(60));
    monitor.checkNow(); // sends PING
    clock.advance(Duration.ofSeconds(10));
    monitor.onPongReceived();

    clock.advance(Duration.ofSeconds(29));
    monitor.checkNow();
    assertThat(session.lifecycle().isClosing()).isFalse();
  }

  @Test
  void noPongWithinTimeoutTransitionsSessionToClosing() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session, cleanup, Duration.ofSeconds(60), Duration.ofSeconds(30), clock);
    session.attachLivenessMonitor(monitor);

    clock.advance(Duration.ofSeconds(60));
    monitor.checkNow(); // sends PING

    clock.advance(Duration.ofSeconds(31));
    monitor.checkNow(); // no PONG arrived — times out

    assertThat(session.lifecycle().isClosing()).isTrue();
  }

  private LivenessMonitor registrationMonitor(MutableClock clock, ClientSession session) {
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session,
            cleanup,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
            clock);
    session.attachLivenessMonitor(monitor);
    return monitor;
  }

  @Test
  void unregisteredSessionIsClosedOnceTheRegistrationTimeoutElapses() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    LivenessMonitor monitor = registrationMonitor(clock, session);

    clock.advance(Duration.ofSeconds(19));
    monitor.checkNow();
    assertThat(session.lifecycle().isClosing()).isFalse();

    clock.advance(Duration.ofSeconds(1));
    monitor.checkNow();
    assertThat(session.lifecycle().isClosing()).isTrue();
  }

  @Test
  void registeredSessionIsNotClosedByTheRegistrationTimeout() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    LivenessMonitor monitor = registrationMonitor(clock, session);

    session.lifecycle().completeRegistration();
    clock.advance(Duration.ofSeconds(30));
    monitor.checkNow();

    assertThat(session.lifecycle().isClosing()).isFalse();
  }

  @Test
  void answeringPingsDoesNotExtendTheRegistrationDeadline() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session,
            cleanup,
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
            clock);
    session.attachLivenessMonitor(monitor);

    for (int i = 0; i < 3; i++) {
      clock.advance(Duration.ofSeconds(5));
      monitor.checkNow(); // idle: sends PING
      monitor.onPongReceived();
    }
    assertThat(session.lifecycle().isClosing()).isFalse();

    clock.advance(Duration.ofSeconds(5));
    monitor.checkNow();
    assertThat(session.lifecycle().isClosing()).isTrue();
  }

  @Test
  void nextCheckDelayWakesAtTheRegistrationDeadlineOnlyWhileUnregistered() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    LivenessMonitor monitor = registrationMonitor(clock, session);
    Duration tick = Duration.ofSeconds(5);

    assertThat(monitor.nextCheckDelay(tick)).isEqualTo(tick);
    clock.advance(Duration.ofSeconds(18));
    assertThat(monitor.nextCheckDelay(tick)).isEqualTo(Duration.ofSeconds(2));
    clock.advance(Duration.ofSeconds(5));
    assertThat(monitor.nextCheckDelay(tick)).isEqualTo(Duration.ofMillis(1));

    session.lifecycle().completeRegistration();
    assertThat(monitor.nextCheckDelay(tick)).isEqualTo(tick);
  }

  @Test
  void registrationCompletingAfterTheTimeoutClaimedClosingSendsNoWelcome() {
    MutableClock clock = new MutableClock(Instant.now());
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    ClientSession session = new ClientSession("c1", "host.example", RateLimitBucket.withDefaults());
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer = new SessionWriter(session, out, TagRenderer.NONE);
    session.attachWriter(writer);
    session.markAliveAt(clock.instant());
    session.setNickname("late");
    session.setIdent("user"); // only the final step of registration is "in flight"
    var extensions = new net.jircd.core.extension.ExtensionRegistry();
    var completion =
        new net.jircd.core.session.command.RegistrationCompletion(
            () -> "server",
            "test",
            Instant.now(),
            extensions,
            new net.jircd.core.session.UserStatistics());
    // The hook runs between the timeout claiming CLOSING and the writer closing: the exact window
    // in which a concurrent NICK/USER/CAP END would call tryComplete.
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            extensions,
            () -> completion.tryComplete(session));
    LivenessMonitor monitor =
        new LivenessMonitor(
            session,
            cleanup,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
            clock);
    session.attachLivenessMonitor(monitor);

    clock.advance(Duration.ofSeconds(20));
    monitor.checkNow();
    writer.close();

    String written = out.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(written).isEqualTo("ERROR :Registration timeout\r\n");
    assertThat(session.lifecycle().isRegistered()).isFalse();
  }

  @Test
  void aTimeoutDoesNotClaimClosingFromARegisteredSession() {
    ConnectionLifecycle lifecycle = new ConnectionLifecycle();
    lifecycle.completeRegistration();

    assertThat(lifecycle.closeIfUnregistered()).isFalse();
    assertThat(lifecycle.isRegistered()).isTrue();
  }

  @Test
  void nextCheckDelayWakesWhenTheNextIdlePingIsDue() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(session, cleanup, Duration.ofSeconds(2), Duration.ofSeconds(30), clock);
    Duration tick = Duration.ofSeconds(5);

    assertThat(monitor.nextCheckDelay(tick)).isEqualTo(Duration.ofSeconds(2));
    clock.advance(Duration.ofSeconds(1));
    assertThat(monitor.nextCheckDelay(tick)).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void connectionStartInstantComesFromTheCaller() {
    MutableClock clock = new MutableClock(Instant.now());
    ClientSession session = newSession(clock);
    Instant acceptedAt = clock.instant();
    clock.advance(Duration.ofSeconds(15)); // time spent between accept and monitor creation
    DisconnectCleanup cleanup =
        new DisconnectCleanup(
            new NicknameRegistry(),
            new ChannelRegistry(),
            new WhowasHistory(() -> 100),
            new net.jircd.core.extension.ExtensionRegistry());
    LivenessMonitor monitor =
        new LivenessMonitor(
            session,
            cleanup,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
            acceptedAt,
            clock);
    session.attachLivenessMonitor(monitor);

    clock.advance(Duration.ofSeconds(5));
    monitor.checkNow();

    assertThat(session.lifecycle().isClosing()).isTrue();
  }
}
