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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SuppressedConnectionLogTest {
    @Test
    void dropsAreAggregatedAndOnlyOneSummaryIsScheduledPerSecond() {
        List<Long> counts = new ArrayList<>();
        try (ManualScheduler scheduler = new ManualScheduler()) {
            SuppressedConnectionLog log = new SuppressedConnectionLog(scheduler, counts::add);
            for (int i = 0; i < 20; i++) log.recordDrop();
            assertThat(counts).isEmpty();
            assertThat(scheduler.pending).hasSize(1);
            assertThat(scheduler.pending.getFirst().getDelay(TimeUnit.SECONDS)).isEqualTo(1);
            scheduler.runNext();
            assertThat(counts).containsExactly(20L);
            assertThat(scheduler.pending).isEmpty();
            log.recordDrop();
            log.recordDrop();
            assertThat(counts).containsExactly(20L);
            assertThat(scheduler.pending).hasSize(1);
            assertThat(scheduler.pending.getFirst().getDelay(TimeUnit.SECONDS)).isEqualTo(1);
            scheduler.runNext();
            assertThat(counts).containsExactly(20L, 2L);
        }
    }

    @Test
    void dropsDuringLoggingAreReportedInTheNextInterval() {
        List<Long> counts = new ArrayList<>();
        AtomicReference<SuppressedConnectionLog> reference = new AtomicReference<>();
        try (ManualScheduler scheduler = new ManualScheduler()) {
            SuppressedConnectionLog log =
                    new SuppressedConnectionLog(
                            scheduler,
                            count -> {
                                counts.add(count);
                                if (counts.size() == 1) reference.get().recordDrop();
                            });
            reference.set(log);
            log.recordDrop();
            scheduler.runNext();
            assertThat(counts).containsExactly(1L);
            assertThat(scheduler.pending).hasSize(1);
            assertThat(scheduler.pending.getFirst().getDelay(TimeUnit.SECONDS)).isEqualTo(1);
            scheduler.runNext();
            assertThat(counts).containsExactly(1L, 1L);
        }
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<ManualTask> pending = new ArrayList<>();

        ManualScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ManualTask task = new ManualTask(command, unit.toNanos(delay));
            pending.add(task);
            return task;
        }

        void runNext() {
            pending.removeFirst().run();
        }
    }

    private static final class ManualTask extends FutureTask<Void>
            implements ScheduledFuture<Void> {
        private final long delayNanos;

        ManualTask(Runnable command, long delayNanos) {
            super(command, null);
            this.delayNanos = delayNanos;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(delayNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ManualTask task && delayNanos == task.delayNanos;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(delayNanos);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(delayNanos, other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
