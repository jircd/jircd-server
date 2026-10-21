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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** Aggregates capacity drops off the accept thread, with at least one second between reports. */
final class SuppressedConnectionLog {
  private final ScheduledExecutorService scheduler;
  private final LongConsumer logger;
  private final AtomicLong pending = new AtomicLong();
  private final AtomicBoolean reportScheduled = new AtomicBoolean();

  SuppressedConnectionLog(ScheduledExecutorService scheduler, LongConsumer logger) {
    this.scheduler = scheduler;
    this.logger = logger;
  }

  void recordDrop() {
    pending.incrementAndGet();
    scheduleReport();
  }

  private void scheduleReport() {
    if (!reportScheduled.compareAndSet(false, true)) return;
    boolean scheduled = false;
    try {
      scheduler.schedule(this::report, 1, TimeUnit.SECONDS);
      scheduled = true;
    } finally {
      if (!scheduled) reportScheduled.set(false);
    }
  }

  private void report() {
    try {
      long count = pending.getAndSet(0);
      if (count > 0) logger.accept(count);
    } finally {
      reportScheduled.set(false);
      if (pending.get() > 0) scheduleReport();
    }
  }
}
