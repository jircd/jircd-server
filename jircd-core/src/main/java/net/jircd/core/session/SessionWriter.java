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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.jircd.protocol.CapabilityName;
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
  private static final int QUEUE_CAPACITY = 256;

  /**
   * Sentinel that tells {@link #drainLoop} to stop after everything queued ahead of it has been
   * written — never itself run. Lets {@link #close} guarantee a message enqueued just before it
   * (e.g. {@code KILL}'s {@code ERROR} line to a self-targeted admin, where the same thread that
   * enqueues it also closes the connection moments later with no other delay) is actually written
   * before the socket goes away, instead of racing an immediate {@code interrupt()} against the
   * writer thread's next queue drain.
   */
  private static final Runnable POISON_PILL = () -> {};

  private static final Duration CLOSE_DRAIN_TIMEOUT = Duration.ofSeconds(2);

  private final ClientSession session;
  private final OutputStream out;
  private final TagRenderer tagRenderer;
  private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final Thread writerThread;
  private volatile Runnable onOverflow = () -> {};

  public SessionWriter(ClientSession session, OutputStream out, TagRenderer tagRenderer) {
    this.session = session;
    this.out = out;
    this.tagRenderer = tagRenderer;
    this.writerThread =
        Thread.ofVirtual()
            .name("session-writer-" + session.connectionId())
            .unstarted(this::drainLoop);
    this.writerThread.start();
  }

  public void setOnOverflow(Runnable onOverflow) {
    this.onOverflow = onOverflow;
  }

  /**
   * Enqueues a fan-out message; capability-dependent tags are rendered per-recipient at drain time.
   */
  public void enqueue(OutboundMessage message) {
    boolean offered = queue.offer(() -> writeFanOutMessage(message));
    if (!offered) {
      handleOverflow();
    }
  }

  /**
   * Enqueues a pre-built raw protocol line (numeric replies, echoes) — no tag decoration applied.
   */
  public void enqueueRaw(Message rawMessage) {
    boolean offered = queue.offer(() -> writeLine(MessageSerializer.serialize(rawMessage)));
    if (!offered) {
      handleOverflow();
    }
  }

  private static final String BATCH_TAG_KEY = "batch";

  /**
   * Wraps {@code members} in {@code BATCH +ref}/{@code BATCH -ref} framing with a {@code batch=ref}
   * tag merged onto each member, but only if this session negotiated both {@code batch} and {@code
   * message-tags} — otherwise every member is sent via {@link #enqueueRaw} completely unmodified
   * (011-away-notify-batch FR-007 through FR-011, research.md Decision 2). {@code ref} is a fresh
   * {@link UUID} generated per call — collision-safe without any shared/synchronized state
   * (research.md Decision 3). The open/close {@code BATCH} lines carry no prefix, the same
   * precedent {@code KillCommandHandler}'s {@code ERROR} line already establishes for a
   * server-framing line with no natural sender identity.
   */
  public void enqueueBatch(String type, List<String> typeParams, List<Message> members) {
    if (!session.negotiatedCapabilities().contains(CapabilityName.BATCH)
        || !session.negotiatedCapabilities().contains(CapabilityName.MESSAGE_TAGS)) {
      for (Message member : members) {
        enqueueRaw(member);
      }
      return;
    }
    String ref = UUID.randomUUID().toString();
    List<String> openParams = new ArrayList<>();
    openParams.add("+" + ref);
    openParams.add(type);
    openParams.addAll(typeParams);
    enqueueRaw(new Message(Map.of(), null, null, "BATCH", List.copyOf(openParams)));
    for (Message member : members) {
      Map<String, String> tags = new LinkedHashMap<>(member.tags());
      tags.put(BATCH_TAG_KEY, ref);
      enqueueRaw(
          new Message(
              tags, member.prefix(), member.command(), member.rawCommand(), member.params()));
    }
    enqueueRaw(new Message(Map.of(), null, null, "BATCH", List.of("-" + ref)));
  }

  private void handleOverflow() {
    LOG.warn("Outbound queue overflow for connection {}; closing", session.connectionId());
    onOverflow.run();
  }

  private void writeFanOutMessage(OutboundMessage message) {
    // tagRenderer.render() is the sole source of tags here — it already seeds the (recipient-
    // filtered) client tags before merging in capability-contributed ones (CapabilityTagRenderer,
    // 005-fix-batch-conformance FR-010), so this call must not re-seed unfiltered client tags on
    // top of it.
    Map<String, String> tags = new java.util.LinkedHashMap<>(tagRenderer.render(session, message));
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
    writeLine(MessageSerializer.serialize(wireMessage));
  }

  private void writeLine(String line) {
    try {
      out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (IOException e) {
      LOG.debug("Write failed for connection {}: {}", session.connectionId(), e.toString());
      onOverflow.run();
    }
  }

  private void drainLoop() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        Runnable task = queue.take();
        if (task == POISON_PILL) {
          return;
        }
        task.run();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Lets everything already queued drain before this session's writer thread stops — enqueues
   * {@link #POISON_PILL} and waits (bounded) for the writer thread to reach it, rather than
   * interrupting immediately, so a message queued just before {@code close()} (e.g. {@code KILL}'s
   * {@code ERROR} line) is guaranteed written first, not racing the writer thread's next drain. If
   * the queue is already full — the same "we're overwhelmed, drop what's left" case an overflow
   * disconnect already accepts — falls back to an immediate interrupt rather than blocking here.
   */
  @Override
  public void close() {
    if (queue.offer(POISON_PILL)) {
      try {
        writerThread.join(CLOSE_DRAIN_TIMEOUT.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (writerThread.isAlive()) {
      writerThread.interrupt();
    }
  }
}
