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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.capability.OutputGroup;
import net.jircd.core.extension.capability.RequestContext;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only path that writes to one session's socket (research.md "Message fan-out concurrency
 * model"). Owns a bounded outbound queue and a dedicated writer virtual thread; a sender never
 * writes cross-thread to another session's socket, and never blocks waiting for a slow recipient —
 * queue overflow transitions that recipient to {@code CLOSING} instead (data-model.md {@code
 * ClientSession} validation rules).
 */
public final class SessionWriter implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(SessionWriter.class);

  private record Entry(long bytes, Object message, TagRenderer.PreparedTags tags) {}

  /**
   * Sentinel that tells {@link #drainLoop} to stop after everything queued ahead of it has been
   * written — never itself run. Lets {@link #close} guarantee a message enqueued just before it
   * (e.g. {@code KILL}'s {@code ERROR} line to a self-targeted admin, where the same thread that
   * enqueues it also closes the connection moments later with no other delay) is actually written
   * before the socket goes away, instead of racing an immediate {@code interrupt()} against the
   * writer thread's next queue drain.
   */
  private static final Entry POISON_PILL = new Entry(0, null, null);

  private static final Duration CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(2);

  private final ClientSession session;
  private final OutputStream out;
  private final TagRenderer tagRenderer;
  private final BlockingQueue<Entry> queue = new LinkedBlockingQueue<>();
  private final Object enqueueLock = new Object();
  private final AtomicLong queuedBytes = new AtomicLong();
  private final int limitBytes;
  private volatile long overflowQueuedBytes;
  private final Thread writerThread;
  private final AtomicBoolean closing = new AtomicBoolean();
  private final AtomicBoolean overflowed = new AtomicBoolean();
  private final CountDownLatch overflowDispatched = new CountDownLatch(1);
  private final AtomicReference<Runnable> onSendqOverflow = new AtomicReference<>(() -> {});
  private final AtomicReference<Runnable> onWriteFailure = new AtomicReference<>(() -> {});

  /** Creates a writer with the server's default outbound byte budget. */
  public SessionWriter(ClientSession session, OutputStream out, TagRenderer tagRenderer) {
    this(session, out, tagRenderer, ServerConfiguration.DEFAULT_SENDQ_LIMIT_BYTES);
  }

  /** Creates a writer with a fixed per-connection outbound byte budget. */
  public SessionWriter(
      ClientSession session, OutputStream out, TagRenderer tagRenderer, int limitBytes) {
    if (limitBytes <= 0) throw new IllegalArgumentException("limitBytes must be positive");
    this.limitBytes = limitBytes;
    this.session = session;
    this.out = out;
    this.tagRenderer = tagRenderer;
    this.writerThread =
        Thread.ofVirtual()
            .name("session-writer-" + session.connectionId())
            .unstarted(this::drainLoop);
    this.writerThread.start();
  }

  /** Installs the nonblocking handoff to sendq disconnect cleanup. */
  public void setOnSendqOverflow(Runnable onSendqOverflow) {
    this.onSendqOverflow.set(onSendqOverflow);
  }

  /** Installs cleanup for socket write failures unrelated to sendq overflow. */
  public void setOnWriteFailure(Runnable onWriteFailure) {
    this.onWriteFailure.set(onWriteFailure);
  }

  /**
   * Enqueues a fan-out message; capability-dependent tags are rendered per-recipient at drain time.
   */
  public void enqueue(OutboundMessage message) {
    if (closing.get() || overflowed.get()) return;
    TagRenderer.PreparedTags tags = tagRenderer.prepare(session, message);
    enqueueEntry(new Entry(message.componentBytes() + tags.wireBytes(), message, tags));
  }

  /**
   * Enqueues a prepared delivery as one entry: all of its frames are admitted, or none is (the
   * session overflows instead). Its exact byte charge is released once, when it is written or
   * discarded.
   */
  public void enqueue(PreparedDelivery delivery) {
    if (closing.get() || overflowed.get()) return;
    enqueueEntry(new Entry(delivery.frames().bytes(), delivery.frames(), null));
  }

  /** Enqueues a pre-built line, charging its escaped tag and component bytes including CRLF. */
  public void enqueueRaw(Message rawMessage) {
    if (closing.get() || overflowed.get()) return;
    enqueueRaw(rawMessage, WireBytes.raw(rawMessage));
  }

  /** Uses the shared charge computed once by raw broadcast delivery. */
  void enqueueRaw(Message rawMessage, long bytes) {
    if (closing.get() || overflowed.get()) return;
    enqueueEntry(new Entry(bytes, rawMessage, null));
  }

  long queuedBytes() {
    return queuedBytes.get();
  }

  int limitBytes() {
    return limitBytes;
  }

  /** Queued charge, including the rejected line, captured before the backlog was discarded. */
  long overflowQueuedBytes() {
    return overflowQueuedBytes;
  }

  private void enqueueEntry(Entry entry) {
    synchronized (enqueueLock) {
      if (closing.get() || overflowed.get()) return;
      long reserved = queuedBytes.addAndGet(entry.bytes());
      if (reserved <= limitBytes) {
        queue.add(entry);
        return;
      }
      queuedBytes.addAndGet(-entry.bytes());
      if (!overflowed.compareAndSet(false, true)) return;
      overflowQueuedBytes = reserved;
      // Subtract only entries actually removed: the drain thread may already own one entry.
      Entry discarded;
      while ((discarded = queue.poll()) != null) queuedBytes.addAndGet(-discarded.bytes());
      Message error =
          Message.of(
              Command.ERROR, "Closing Link: " + session.realHostname() + " (SendQ exceeded)");
      queue.add(new Entry(0, error, null));
      queue.add(POISON_PILL);
    }
    try {
      handleOverflow();
    } finally {
      // The writer may consume ERROR + pill before this callback even gets scheduled. Shutdown
      // must not relinquish the connection until its cleanup thread has been handed off.
      overflowDispatched.countDown();
    }
  }

  /**
   * Enqueues a finite group of {@code members}, framed for this recipient by whichever provider
   * owns group framing (or sent plain when none applies to it), as one queue entry: every frame,
   * including any closer, is admitted or none is, so an opener can never be queued without its
   * closer and concurrent groups never interleave. The group must fit this queue; one that cannot
   * overflows this recipient, like any other output it cannot hold. Nothing here keeps a provider
   * callback for the writer thread: the plan is serialized now.
   */
  public void enqueueGroup(String type, List<String> typeParams, List<Message> members) {
    enqueueGroup(type, typeParams, members, RequestContext.of(0, Map.of()));
  }

  /** As {@link #enqueueGroup(String, List, List)}, for the request {@code context} it answers. */
  public void enqueueGroup(
      String type, List<String> typeParams, List<Message> members, RequestContext context) {
    if (closing.get() || overflowed.get()) {
      return;
    }
    long memberBytes = 0;
    for (Message member : members) {
      memberBytes = Math.addExact(memberBytes, WireBytes.raw(member));
    }
    if (memberBytes > limitBytes) {
      // Larger than the whole queue: reject before allocating any transformed copy.
      enqueueEntry(new Entry(memberBytes, null, null));
      return;
    }
    // The plan's own bound is left open on purpose: a provider's valid framing that is merely too
    // large for the queue must overflow this recipient below, not read as a malformed plan and fall
    // back to plain members. The plan stays bounded by its frame count and the per-frame limits.
    OutputGroup group =
        new OutputGroup(UUID.randomUUID().toString(), type, typeParams, members, Long.MAX_VALUE);
    List<Message> frames = tagRenderer.frame(session, group, context).frames();
    if (frames.isEmpty()) {
      return;
    }
    FrameSequence sequence;
    try {
      sequence = FrameSequence.of(frames);
    } catch (IllegalArgumentException e) {
      LOG.warn(
          "Dropping an unrepresentable output group for connection {}: {}",
          session.connectionId(),
          e.getMessage());
      return;
    }
    enqueueEntry(new Entry(sequence.bytes(), sequence, null));
  }

  private void handleOverflow() {
    LOG.warn("Outbound queue overflow for connection {}; closing", session.connectionId());
    onSendqOverflow.get().run();
  }

  private void writeFanOutMessage(OutboundMessage message, TagRenderer.PreparedTags prepared) {
    // The selection matches the enqueue reservation; decoration stays on the writer thread.
    Map<String, String> tags;
    try {
      tags = new java.util.LinkedHashMap<>(prepared.tags().get());
    } catch (MessageSerializer.MessageTooLongException e) {
      LOG.warn(
          "Dropping oversized outbound tags for connection {}: {}",
          session.connectionId(),
          e.getMessage());
      return;
    }
    java.util.List<String> params =
        message.body() != null
            ? java.util.List.of(message.target(), message.body())
            : java.util.List.of(message.target());
    Message wireMessage =
        new Message(
            tags,
            message.senderPresentedForm(),
            Command.fromToken(message.command()),
            message.command(),
            params);
    writeMessage(wireMessage, false);
  }

  private void writeMessage(Message message, boolean serverOriginOnly) {
    try {
      if (serverOriginOnly
          && MessageSerializer.tagDataBytes(message.tags())
              > MessageSerializer.MAX_TAG_DATA_BYTES_PER_ORIGIN) {
        throw new MessageSerializer.MessageTooLongException(
            "Outbound server tags exceed 4094 data bytes");
      }
      writeLine(MessageSerializer.serializeText(message));
    } catch (MessageSerializer.MessageTooLongException e) {
      // Report unrepresentable identities and nick/channel pairs through the same throttled
      // policy as numeric replies. PRIVMSG/NOTICE have already attempted text fitting above.
      if (OutboundSizeGuard.warnIfOversized(message)) {
        return;
      }
      LOG.warn(
          "Dropping oversized outbound message for connection {}: {}",
          session.connectionId(),
          e.getMessage());
    }
  }

  private boolean writeLine(String line) {
    try {
      out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
      return true;
    } catch (IOException e) {
      LOG.debug("Write failed for connection {}: {}", session.connectionId(), e.toString());
      // A failed best-effort ERROR must not steal the SendQ reason from its pending cleanup.
      if (!overflowed.get()) onWriteFailure.get().run();
      return false;
    }
  }

  private void drainLoop() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        Entry entry = queue.take();
        queuedBytes.addAndGet(-entry.bytes());
        if (entry == POISON_PILL) {
          return;
        }
        if (entry.message() instanceof Message raw) {
          writeMessage(raw, true);
        } else if (entry.message() instanceof FrameSequence sequence) {
          for (String line : sequence.lines()) {
            if (!writeLine(line)) break;
          }
        } else {
          writeFanOutMessage((OutboundMessage) entry.message(), entry.tags());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Lets everything already queued drain before this session's writer thread stops — enqueues
   * {@link #POISON_PILL} and waits (bounded) for the writer thread to reach it, rather than
   * interrupting immediately, so a message queued just before {@code close()} (e.g. {@code KILL}'s
   * {@code ERROR} line) is guaranteed written first, not racing the writer thread's next drain. The
   * poison pill uses a reserved closing allowance outside the data budget, so a full data queue
   * still preserves final lines. A stuck stream is interrupted after the bounded drain wait.
   *
   * <p>Only the first call does this. A later call (the liveness thread closes after {@code
   * DisconnectCleanup} does) only waits, bounded the same way, for the drain the first call
   * started. It never offers its own pill or interrupts: an interrupt would cut off the lines still
   * draining ahead of the first call's pill.
   */
  @Override
  public void close() {
    if (!closing.compareAndSet(false, true)) {
      awaitDrain();
      return;
    }
    synchronized (enqueueLock) {
      if (!overflowed.get()) queue.add(POISON_PILL);
    }
    awaitDrain();
    if (writerThread.isAlive()) {
      writerThread.interrupt();
    }
  }

  /** Waits for the writer's actual exit and any overflow-cleanup handoff after socket closure. */
  void awaitTermination() throws InterruptedException {
    writerThread.join();
    if (overflowed.get()) overflowDispatched.await();
  }

  private void awaitDrain() {
    // A failed socket write can invoke cleanup on this writer; joining itself cannot drain it.
    if (Thread.currentThread() == writerThread) return;
    try {
      writerThread.join(CLOSE_DRAIN_TIMEOUT);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
