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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.jircd.core.extension.capability.FaultCategory;
import net.jircd.core.extension.capability.FaultReporter;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.session.WarningThrottle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registry's {@link FaultReporter}: turns a provider's runtime hook fault into one FAILED
 * withdrawal and keeps the diagnostics bounded.
 *
 * <p>A fault by a provider that is currently published starts an incident: the withdrawal is handed
 * to {@code withdrawals}, never run on the faulting thread, which may be any delivery thread and
 * has already replaced the dropped result with its default. Further faults by that instance while
 * the incident is pending schedule nothing more; a re-enabled instance opens its own. The
 * withdrawal itself runs under the registry's lifecycle lock, so it is ordered with every enable,
 * disable and reload.
 *
 * <p>Diagnostics name the provider, the hook kind and a category, and never carry an exception
 * message, stack trace, client content or configuration value. The failure transition is logged
 * once per incident; other fault reports and cleanup warnings are throttled per provider, so a
 * fan-out of faults from work captured before the withdrawal costs one line per window.
 */
final class CapabilityFaults implements FaultReporter {

  // Deliberately the public FaultReporter's name, not this package-private class's: it is the
  // logger operators configure for capability faults, as it was before #102.
  private static final Logger LOG = LoggerFactory.getLogger(FaultReporter.class); // NOSONAR S3416

  /** The fault that opened an incident. */
  record Fault(HookKind kind, FaultCategory category) {}

  /**
   * One open incident, for one published instance of a provider: {@code instance} is the descriptor
   * that was current when it opened. Compared by identity, so a re-enabled instance's fault never
   * matches an incident its predecessor left open.
   */
  private static final class Incident {
    final Object instance;
    final Fault fault;

    Incident(Object instance, Fault fault) {
      this.instance = instance;
      this.fault = fault;
    }
  }

  private final Executor withdrawals;
  private final Function<String, Object> published;
  private final BiConsumer<String, Fault> withdraw;
  private final Map<String, Incident> pending = new ConcurrentHashMap<>();
  private final WarningThrottle throttle =
      new WarningThrottle(TimeUnit.SECONDS.toNanos(10), 256, System::nanoTime);

  /**
   * @param published the descriptor currently published for a provider id, or {@code null}; its
   *     identity tells one enabled instance of a provider from the next
   * @param withdraw withdraws a provider as FAILED for the fault; runs on {@code withdrawals}
   */
  CapabilityFaults(
      Executor withdrawals,
      Function<String, Object> published,
      BiConsumer<String, Fault> withdraw) {
    this.withdrawals = Objects.requireNonNull(withdrawals);
    this.published = Objects.requireNonNull(published);
    this.withdraw = Objects.requireNonNull(withdraw);
  }

  /**
   * Opens an incident unless one is already open for the instance published now. An incident left
   * open by an earlier instance (a re-enable happened before its withdrawal finished) does not
   * count: the current instance's fault opens its own, so it can never be lost. A report whose
   * instance stopped being the published one before it got here (it looked the instance up, then a
   * re-enable overtook it) changes nothing, so it can never replace a newer instance's incident.
   */
  @Override
  public void report(String providerId, HookKind kind, FaultCategory category) {
    Fault fault = new Fault(kind, category);
    Object instance = category.providerFault() ? published.apply(providerId) : null;
    if (instance != null) {
      Incident opened = new Incident(instance, fault);
      Incident open =
          pending.compute(
              providerId, (id, existing) -> chooseIncident(id, existing, instance, opened));
      if (open == opened) {
        schedule(providerId, opened);
        return;
      }
    }
    dropped(providerId, fault);
  }

  /**
   * The marker to keep: the existing one if the reporting instance is no longer published or
   * already has its incident open, otherwise the reporter's new incident.
   */
  private Incident chooseIncident(
      String providerId, Incident existing, Object instance, Incident opened) {
    if (published.apply(providerId) != instance) return existing;
    if (existing != null && existing.instance == instance) return existing;
    return opened;
  }

  /** Ends only {@code incident}: a newer instance's incident for the same id stays open. */
  private void schedule(String providerId, Incident incident) {
    try {
      withdrawals.execute(
          () -> {
            try {
              withdraw.accept(providerId, incident.fault);
            } finally {
              pending.remove(providerId, incident);
            }
          });
    } catch (RejectedExecutionException _) {
      pending.remove(providerId, incident);
      Fault fault = incident.fault;
      LOG.warn(
          "Capability provider '{}' hook {} faulted ({}) but its withdrawal could not be scheduled",
          providerId,
          fault.kind(),
          fault.category());
    }
  }

  /** A result dropped without starting an incident; throttled per provider. */
  void dropped(String providerId, Fault fault) {
    long suppressed = throttle.admit(providerId + "/fault");
    if (suppressed >= 0) {
      LOG.warn(
          "Capability provider '{}' hook {} result dropped ({}); {} similar suppressed",
          providerId,
          fault.kind(),
          fault.category(),
          suppressed);
    }
  }

  /**
   * The failure transition, logged once per incident. It also opens the provider's throttle window,
   * so late faults from work captured before the withdrawal stay quiet for a while.
   */
  void failed(String providerId, Fault fault, List<String> dependents) {
    throttle.admit(providerId + "/fault");
    LOG.warn(
        "Capability provider '{}' FAILED: hook {} result dropped ({}); withdrawn{}. Enable it again"
            + " once fixed",
        providerId,
        fault.kind(),
        fault.category(),
        dependents.isEmpty() ? "" : " with its dependents " + dependents);
  }

  /** A stop that threw after a fault's withdrawal; only the exception's type is logged. */
  void stopFailed(String providerId, Throwable failure) {
    long suppressed = throttle.admit(providerId + "/cleanup");
    if (suppressed >= 0) {
      LOG.warn(
          "Capability provider '{}' failed to stop after it was withdrawn ({}); a later disable"
              + " retries the cleanup; {} similar suppressed",
          providerId,
          failure.getClass().getSimpleName(),
          suppressed);
    }
  }
}
