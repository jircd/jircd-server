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
package net.jircd.core.extension;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.extension.capability.FaultCategory;
import net.jircd.core.extension.capability.HookKind;
import org.junit.jupiter.api.Test;

/** Incident bookkeeping across provider instances, driven without a registry. */
class CapabilityFaultsTest {

  private final Object instanceA = new Object();
  private final Object instanceB = new Object();
  private final AtomicReference<Object> current = new AtomicReference<>(instanceA);
  private final List<Runnable> scheduled = new ArrayList<>();
  private final List<String> withdrawn = new ArrayList<>();

  @Test
  void aReportForAnInstanceNoLongerPublishedNeverReplacesTheCurrentInstancesIncident() {
    // Review round 2, item 1: report A looks up instance A, then "pauses". Meanwhile B is
    // published and B's report opens B's incident. A resumes after that.
    AtomicReference<CapabilityFaults> faults = new AtomicReference<>();
    AtomicReference<Boolean> paused = new AtomicReference<>(false);
    faults.set(
        new CapabilityFaults(
            scheduled::add,
            id -> {
              Object seen = current.get();
              if (seen == instanceA && !paused.getAndSet(true)) {
                current.set(instanceB);
                faults.get().report(id, HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
              }
              return seen;
            },
            (id, fault) -> withdrawn.add(id)));

    faults.get().report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION); // stale A
    assertThat(scheduled).as("only B's incident").hasSize(1);

    // B's incident is still the open one, so B's next fault adds nothing.
    faults.get().report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    assertThat(scheduled).hasSize(1);

    scheduled.getFirst().run();
    assertThat(withdrawn).containsExactly("p");
    // B's incident has ended: a further fault by B opens a new one.
    faults.get().report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    assertThat(scheduled).hasSize(2);
  }

  @Test
  void aFaultByTheCurrentInstanceReplacesAnIncidentAnOlderInstanceLeftOpen() {
    CapabilityFaults faults =
        new CapabilityFaults(scheduled::add, id -> current.get(), (id, fault) -> withdrawn.add(id));
    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    current.set(instanceB);

    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    assertThat(scheduled).hasSize(2);

    // The older incident ending leaves the current instance's marker in place.
    scheduled.getFirst().run();
    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    assertThat(scheduled).hasSize(2);
  }
}
