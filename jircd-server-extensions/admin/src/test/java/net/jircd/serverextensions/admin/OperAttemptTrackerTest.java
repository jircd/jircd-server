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
package net.jircd.serverextensions.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.config.ConnectionLimitPolicy;
import net.jircd.core.config.ServerConfiguration;
import org.junit.jupiter.api.Test;

class OperAttemptTrackerTest {
  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
  private final Clock clock =
      new Clock() {
        @Override
        public java.time.ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  @Test
  void slowVerificationStartsTheFailureWindowWhenVerificationCompletes() {
    var tracker = new OperAttemptTracker(clock, 2);
    assertThat(
            tracker.authenticate(
                "source",
                1,
                1,
                true,
                () -> {
                  now.set(Instant.EPOCH.plusSeconds(2));
                  return false;
                }))
        .isEqualTo(OperAttemptTracker.Result.FAILURE_LIMIT_REACHED);
    AtomicBoolean checked = new AtomicBoolean();
    assertThat(
            tracker.authenticate(
                "source",
                1,
                1,
                true,
                () -> {
                  checked.set(true);
                  return true;
                }))
        .isEqualTo(OperAttemptTracker.Result.LOCKED);
    assertThat(checked).isFalse();
    now.set(Instant.EPOCH.plusSeconds(3));
    assertThat(tracker.authenticate("source", 1, 1, true, () -> true))
        .isEqualTo(OperAttemptTracker.Result.SUCCESS);
  }

  @Test
  void thresholdSkipsPasswordVerificationUntilExactWindowExpiry() {
    var tracker = new OperAttemptTracker(clock, 2);
    assertThat(tracker.authenticate("source", 2, 60, true, () -> false))
        .isEqualTo(OperAttemptTracker.Result.FAILURE);
    assertThat(tracker.authenticate("source", 2, 60, true, () -> false))
        .isEqualTo(OperAttemptTracker.Result.FAILURE_LIMIT_REACHED);
    AtomicBoolean checked = new AtomicBoolean();
    now.set(Instant.EPOCH.plusSeconds(59));
    assertThat(
            tracker.authenticate(
                "source",
                2,
                60,
                true,
                () -> {
                  checked.set(true);
                  return true;
                }))
        .isEqualTo(OperAttemptTracker.Result.LOCKED);
    assertThat(checked).isFalse();
    now.set(Instant.EPOCH.plusSeconds(60));
    assertThat(tracker.authenticate("source", 2, 60, true, () -> true))
        .isEqualTo(OperAttemptTracker.Result.SUCCESS);
  }

  @Test
  void successfulAuthenticationClearsTheSourceCounter() {
    var tracker = new OperAttemptTracker(clock, 2);
    tracker.authenticate("source", 2, 60, true, () -> false);
    assertThat(tracker.authenticate("source", 2, 60, true, () -> true))
        .isEqualTo(OperAttemptTracker.Result.SUCCESS);
    assertThat(tracker.authenticate("source", 2, 60, true, () -> false))
        .isEqualTo(OperAttemptTracker.Result.FAILURE);
  }

  @Test
  void fullTrackerReportsCapacityWithoutEvictingLiveLockouts() {
    var tracker = new OperAttemptTracker(clock, 2);
    tracker.authenticate("one", 1, 60, true, () -> false);
    tracker.authenticate("two", 1, 60, true, () -> false);
    AtomicBoolean checked = new AtomicBoolean();
    assertThat(
            tracker.authenticate(
                "three",
                1,
                60,
                true,
                () -> {
                  checked.set(true);
                  return true;
                }))
        .isEqualTo(OperAttemptTracker.Result.CAPACITY_FULL);
    assertThat(checked).isFalse();
    assertThat(tracker.authenticate("one", 1, 60, true, () -> true))
        .isEqualTo(OperAttemptTracker.Result.LOCKED);
    now.set(Instant.EPOCH.plusSeconds(60));
    assertThat(tracker.authenticate("three", 1, 60, true, () -> true))
        .isEqualTo(OperAttemptTracker.Result.SUCCESS);
  }

  @Test
  void sourcesAreIndependentAndWindowIsNotExtendedByFailuresOrReload() {
    var tracker = new OperAttemptTracker(clock, 2);
    tracker.authenticate("one", 3, 60, true, () -> false);
    now.set(Instant.EPOCH.plusSeconds(30));
    assertThat(tracker.authenticate("one", 2, 600, true, () -> false))
        .isEqualTo(OperAttemptTracker.Result.FAILURE_LIMIT_REACHED);
    assertThat(tracker.authenticate("two", 2, 600, true, () -> true))
        .isEqualTo(OperAttemptTracker.Result.SUCCESS);
    now.set(Instant.EPOCH.plusSeconds(60));
    assertThat(tracker.authenticate("one", 2, 600, true, () -> false))
        .isEqualTo(OperAttemptTracker.Result.FAILURE);
  }

  @Test
  void concurrentConnectionsCannotVerifyBeyondTheThreshold() throws Exception {
    var tracker = new OperAttemptTracker(clock, 2);
    AtomicInteger verified = new AtomicInteger();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 20; i++) {
        tasks.add(
            executor.submit(
                () ->
                    tracker.authenticate(
                        "source",
                        3,
                        60,
                        true,
                        () -> {
                          verified.incrementAndGet();
                          return false;
                        })));
      }
      for (var task : tasks) {
        task.get();
      }
    }
    assertThat(verified).hasValue(3);
  }

  @Test
  void ipv6PrefixAndMappedIpv4ShareTheirCanonicalLockout() {
    var tracker = new OperAttemptTracker(clock, 10);
    var limits = new ServerConfiguration.ConnectionLimits(100, 20, 64, List.of());
    for (String[] pair :
        List.of(
            new String[] {"2001:db8::1", "2001:db8::ffff"},
            new String[] {"192.0.2.1", "::ffff:192.0.2.1"})) {
      String first = ConnectionLimitPolicy.resolve(InetAddress.ofLiteral(pair[0]), limits).bucket();
      String second =
          ConnectionLimitPolicy.resolve(InetAddress.ofLiteral(pair[1]), limits).bucket();
      tracker.authenticate(first, 1, 60, true, () -> false);
      assertThat(tracker.authenticate(second, 1, 60, true, () -> true))
          .isEqualTo(OperAttemptTracker.Result.LOCKED);
    }
  }

  @Test
  void tlsRejectionSkipsVerificationAndCountingButLockoutStillTakesPrecedence() {
    var tracker = new OperAttemptTracker(clock, 2);
    AtomicBoolean checked = new AtomicBoolean();
    assertThat(
            tracker.authenticate(
                "source",
                1,
                60,
                false,
                () -> {
                  checked.set(true);
                  return false;
                }))
        .isEqualTo(OperAttemptTracker.Result.TLS_REQUIRED);
    assertThat(checked).isFalse();
    assertThat(tracker.authenticate("source", 1, 60, true, () -> true))
        .isEqualTo(OperAttemptTracker.Result.SUCCESS);
    tracker.authenticate("source", 1, 60, true, () -> false);
    assertThat(
            tracker.authenticate(
                "source",
                1,
                60,
                false,
                () -> {
                  checked.set(true);
                  return true;
                }))
        .isEqualTo(OperAttemptTracker.Result.LOCKED);
    assertThat(checked).isFalse();
  }

  @Test
  void unrelatedSourcesCanVerifyWhileAnotherSourceIsBusy() throws Exception {
    var tracker = new OperAttemptTracker(clock, 2);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  tracker.authenticate(
                      "one",
                      2,
                      60,
                      true,
                      () -> {
                        entered.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return false;
                      }));
      try {
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var second = executor.submit(() -> tracker.authenticate("two", 2, 60, true, () -> true));
        assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS))
            .isEqualTo(OperAttemptTracker.Result.SUCCESS);
      } finally {
        release.countDown();
      }
      first.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
  }
}
