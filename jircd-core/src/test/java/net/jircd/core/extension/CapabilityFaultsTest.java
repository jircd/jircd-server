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
import java.util.concurrent.atomic.AtomicInteger;
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
  void aFaultFromWorkCapturedBeforeAReEnableAddsNothingWhileTheCurrentInstancesIncidentIsOpen() {
    // Review round 2, item 1: a report from work that captured instance A, arriving once B is
    // published and B's incident is open, must not replace B's marker or open a second incident.
    CapabilityFaults faults =
        new CapabilityFaults(scheduled::add, id -> current.get(), (id, fault) -> withdrawn.add(id));
    current.set(instanceB);
    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION); // B's incident

    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION); // late, from A's work
    assertThat(scheduled).as("only B's incident").hasSize(1);

    scheduled.getFirst().run();
    assertThat(withdrawn).containsExactly("p");
    // B's incident has ended: a further fault by B opens a new one.
    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    assertThat(scheduled).hasSize(2);
  }

  @Test
  void aFaultReportedWhileItsInstanceIsReplacedStillSchedulesAWithdrawal() {
    // Sourcery, PR #112 comment 2: the report sees A, then A is disabled and B enabled before it
    // records the incident. The fault is still the provider's, so B is withdrawn, not left running.
    AtomicInteger lookups = new AtomicInteger();
    CapabilityFaults faults =
        new CapabilityFaults(
            scheduled::add,
            id -> {
              Object seen = current.get();
              if (lookups.incrementAndGet() == 1) current.set(instanceB);
              return seen;
            },
            (id, fault) -> withdrawn.add(id));

    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    assertThat(scheduled).hasSize(1);
    scheduled.getFirst().run();
    assertThat(withdrawn).containsExactly("p");
  }

  @Test
  void aFaultWhenNoInstanceIsPublishedSchedulesNothing() {
    CapabilityFaults faults =
        new CapabilityFaults(scheduled::add, id -> null, (id, fault) -> withdrawn.add(id));
    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    assertThat(scheduled).isEmpty();
  }

  @Test
  void anExecutorThatThrowsLeavesNoIncidentOpenAndTheFaultingCallReturns() {
    // Sourcery, PR #112 comment 5: any unchecked executor failure, not only a rejection.
    AtomicInteger attempts = new AtomicInteger();
    CapabilityFaults faults =
        new CapabilityFaults(
            task -> {
              if (attempts.incrementAndGet() == 1) throw new IllegalStateException("broken");
              scheduled.add(task);
            },
            id -> current.get(),
            (id, fault) -> withdrawn.add(id));

    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    faults.report("p", HookKind.OUTBOUND_TAGS, FaultCategory.EXCEPTION);
    assertThat(attempts).as("the second fault tried again").hasValue(2);
    assertThat(scheduled).hasSize(1);
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
