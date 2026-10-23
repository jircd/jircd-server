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

import java.util.concurrent.TimeUnit;
import net.jircd.core.session.WarningThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operator-visible report of a dropped hook result. Receives only the provider id, hook kind and a
 * category, so nothing client-controlled or secret can reach the log.
 */
@FunctionalInterface
public interface FaultReporter {

  void report(String providerId, HookKind kind, FaultCategory category);

  /** Logs one warning per provider/hook/category per interval, counting the suppressed ones. */
  static FaultReporter logging() {
    Logger log = LoggerFactory.getLogger(FaultReporter.class);
    WarningThrottle throttle =
        new WarningThrottle(TimeUnit.SECONDS.toNanos(10), 256, System::nanoTime);
    return (providerId, kind, category) -> {
      long suppressed = throttle.admit(providerId + '/' + kind + '/' + category);
      if (suppressed >= 0) {
        log.warn(
            "Capability provider '{}' hook {} result dropped ({}); {} similar suppressed",
            providerId,
            kind,
            category,
            suppressed);
      }
    };
  }
}
