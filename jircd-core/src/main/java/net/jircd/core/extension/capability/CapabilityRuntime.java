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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.SessionWriter;
import net.jircd.protocol.CapabilityNegotiationGrammar;
import net.jircd.protocol.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live holder of the current {@link RuntimeSnapshot}, and owner of the administrative mutex.
 *
 * <p>The mutex is short and never held across a provider callback, socket I/O or a channel monitor.
 * It serializes snapshot publication together with the physical prune of withdrawn names from every
 * live session, CAP state changes and their replies, live-session insertion, removal and writer
 * attachment, coherent captures, and the current prerequisite choice a group makes at admission.
 * Whole lifecycle transitions (provider start, register, stop) are serialized by the extension
 * registry's own lock, taken before this one, never after. Lock order: lifecycle lock, then this
 * mutex, then at most one session writer's queue lock.
 *
 * <p>A publication swaps the snapshot, prunes, and then appends {@code CAP DEL}/{@code NEW} to
 * eligible writers while still holding the mutex; appends never block, and an overflowing recipient
 * is handed to its own cleanup. Work that captured an older snapshot keeps it and may finish after
 * the {@code DEL}; the next capture sees the new state.
 */
public final class CapabilityRuntime {

  /** A coherent view captured under the mutex: one snapshot and one session's negotiation. */
  public record CapturedView(RuntimeSnapshot snapshot, Set<String> negotiated) {}

  /**
   * How long one publication that changed the offered set took, in its stages: waiting for the
   * mutex, then (holding it) building and swapping the snapshot and pruning every live session,
   * then queuing {@code DEL}/{@code NEW}. Socket drain is not part of it.
   *
   * @param sessions the live sessions the publication pruned and notified
   */
  public record PublicationTiming(
      long waitNanos, long publishNanos, long notifyNanos, int sessions) {}

  private final Object control = new Object();
  private final CapabilityControl facade = new CapabilityControl(this);
  private final AtomicReference<RuntimeSnapshot> snapshot =
      new AtomicReference<>(RuntimeSnapshot.EMPTY);
  private static final Logger LOG = LoggerFactory.getLogger(CapabilityRuntime.class);

  private final AtomicReference<Collection<ClientSession>> liveSessions =
      new AtomicReference<>(List.of());
  private final AtomicReference<Supplier<String>> serverName = new AtomicReference<>(() -> "*");
  private final AtomicReference<Consumer<PublicationTiming>> timings =
      new AtomicReference<>(timing -> {});

  public RuntimeSnapshot snapshot() {
    return snapshot.get();
  }

  /** The mutex alone, for code that must not publish. */
  public CapabilityControl control() {
    return facade;
  }

  /**
   * Tracks {@code sessions} — every accepted connection, named or not — as the sessions a
   * publication prunes and notifies. The owner inserts and removes them through {@link
   * #runUnderControl}, so no session escapes a prune.
   */
  public void attachLiveSessions(Collection<ClientSession> sessions) {
    this.liveSessions.set(Objects.requireNonNull(sessions));
  }

  /** The server name notifications are sent from. */
  public void attachServerName(Supplier<String> name) {
    this.serverName.set(Objects.requireNonNull(name));
  }

  /**
   * Receives the timing of every later publication that changes the offered set, on the publishing
   * thread once that publication's critical section has ended. For measurement; it must be quick.
   * An exception it throws is logged and changes nothing about the publication.
   */
  public void observePublications(Consumer<PublicationTiming> observer) {
    timings.set(Objects.requireNonNull(observer));
  }

  /** Runs {@code action} under the administrative mutex. It must not call a provider. */
  public void runUnderControl(Runnable action) {
    synchronized (control) {
      action.run();
    }
  }

  /** Computes {@code action} under the administrative mutex. It must not call a provider. */
  public <T> T underControl(Supplier<T> action) {
    synchronized (control) {
      return action.get();
    }
  }

  /** Captures the current snapshot and {@code session}'s negotiation together. */
  public CapturedView capture(ClientSession session) {
    synchronized (control) {
      return new CapturedView(snapshot.get(), session.negotiatedCapabilities());
    }
  }

  /** Replaces the configured limits slot bounds are evaluated against; providers unchanged. */
  public void setLimits(ReplyLimits limits) {
    publish(current -> current.withLimits(limits));
  }

  /**
   * Publishes {@code descriptor}.
   *
   * @throws IllegalArgumentException if it conflicts with a published provider; nothing changes
   */
  public RuntimeSnapshot publish(CapabilityDescriptor descriptor) {
    return publish(current -> current.with(descriptor));
  }

  /**
   * Withdraws {@code providerId} together with every published provider that requires it, in one
   * publication. Returns the provider ids withdrawn, each before every provider it requires (the
   * order to stop them in); empty if {@code providerId} was not published.
   */
  public Set<String> withdraw(String providerId) {
    long waiting = System.nanoTime();
    Set<String> closure;
    Published published = null;
    synchronized (control) {
      long held = System.nanoTime();
      closure = snapshot.get().withdrawalClosure(providerId);
      if (!closure.isEmpty()) {
        published = publishLocked(current -> current.withoutAll(closure), () -> {}, waiting, held);
      }
    }
    if (published != null) observe(published);
    return closure;
  }

  /**
   * Builds the next snapshot from the current one and, in the same critical section, swaps it in,
   * removes every name no longer offered from every live session, and queues {@code DEL} then
   * {@code NEW} to the eligible ones. {@code change} must be pure core code; if it throws, nothing
   * changes. A change that leaves the offered set as it was queues nothing.
   */
  public RuntimeSnapshot publish(UnaryOperator<RuntimeSnapshot> change) {
    return publish(change, () -> {});
  }

  /**
   * As {@link #publish(UnaryOperator)}, also running {@code alongside} in the same critical
   * section, right before the swap, so related configuration a reload publishes becomes visible to
   * captures together with the snapshot. {@code alongside} must be core code that only publishes
   * values; it runs whether or not the offered set changed. If it throws, the snapshot is not
   * swapped and nothing is pruned or queued: from the swap on, nothing that can fail runs before
   * the prune and the per-recipient notifications.
   */
  public RuntimeSnapshot publish(UnaryOperator<RuntimeSnapshot> change, Runnable alongside) {
    long waiting = System.nanoTime();
    Published published;
    synchronized (control) {
      published = publishLocked(change, alongside, waiting, System.nanoTime());
    }
    observe(published);
    return published.snapshot();
  }

  /** A publication's result, with its timing if it changed the offered set. */
  private record Published(RuntimeSnapshot snapshot, PublicationTiming timing) {}

  private void observe(Published published) {
    if (published.timing() == null) return;
    try {
      timings.get().accept(published.timing());
    } catch (RuntimeException e) {
      // The publication has committed; a measurement callback cannot change its outcome.
      LOG.warn("Capability publication observer failed; the publication stands", e);
    }
  }

  /** The publication itself; the caller holds the mutex, acquired at {@code held}. */
  private Published publishLocked(
      UnaryOperator<RuntimeSnapshot> change, Runnable alongside, long waiting, long held) {
    RuntimeSnapshot before = snapshot.get();
    RuntimeSnapshot next = Objects.requireNonNull(change.apply(before));
    Set<String> removed = new HashSet<>(before.offeredNames());
    next.offeredNames().forEach(removed::remove);
    Set<String> added = new HashSet<>(next.offeredNames());
    before.offeredNames().forEach(added::remove);
    alongside.run();
    snapshot.set(next);
    if (removed.isEmpty() && added.isEmpty()) return new Published(next, null);
    List<ClientSession> sessions = new ArrayList<>(liveSessions.get());
    for (ClientSession session : sessions) prune(session, removed);
    long pruned = System.nanoTime();
    for (ClientSession session : sessions) {
      try {
        notify(session, removed, added);
      } catch (RuntimeException e) {
        // The state is already published; one recipient's notification cannot undo it.
        LOG.warn(
            "Could not queue capability notification for connection {}", session.connectionId(), e);
      }
    }
    PublicationTiming timing =
        new PublicationTiming(
            held - waiting, pruned - held, System.nanoTime() - pruned, sessions.size());
    return new Published(next, timing);
  }

  private static void prune(ClientSession session, Set<String> removed) {
    Set<String> negotiated = session.negotiatedCapabilities();
    if (removed.stream().noneMatch(negotiated::contains)) return;
    Set<String> kept = new HashSet<>(negotiated);
    kept.removeAll(removed);
    session.replaceNegotiatedCapabilities(kept);
  }

  private void notify(ClientSession session, Set<String> removed, Set<String> added) {
    // Owned and closed by the session's connection, not by this append.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer = session.writer();
    if (writer == null || !session.notificationEligible()) return;
    String target = session.nickname() != null ? session.nickname() : "*";
    String server = serverName.get().get();
    List<Message> lines = new ArrayList<>();
    if (!removed.isEmpty()) {
      lines.addAll(CapabilityNegotiationGrammar.notifications(server, target, "DEL", removed));
    }
    if (!added.isEmpty()) {
      lines.addAll(CapabilityNegotiationGrammar.notifications(server, target, "NEW", added));
    }
    lines.forEach(writer::enqueueRaw);
  }
}
