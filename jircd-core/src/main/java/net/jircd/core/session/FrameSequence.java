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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;

/**
 * An immutable, fully materialized ordered run of wire lines and their exact size. The writer
 * admits it as one queue entry, so either every line is queued or none is, and a closing frame can
 * never be dropped independently of its opener. It holds only strings and a byte count: no plugin
 * callback or mutable state reaches the queue.
 */
public final class FrameSequence {
  private final List<String> lines;
  private final long bytes;

  private FrameSequence(List<String> lines, long bytes) {
    this.lines = lines;
    this.bytes = bytes;
  }

  /**
   * Serializes {@code frames} now, so any size or syntax violation surfaces before admission.
   *
   * @throws IllegalArgumentException if {@code frames} is empty or a frame is not valid IRC syntax
   * @throws MessageSerializer.MessageTooLongException if any frame cannot be represented
   */
  public static FrameSequence of(List<Message> frames) {
    if (frames.isEmpty()) {
      throw new IllegalArgumentException("A frame sequence needs at least one frame");
    }
    List<String> lines = new ArrayList<>(frames.size());
    long total = 0;
    for (Message frame : frames) {
      FrameSyntax.validate(frame);
      String line = MessageSerializer.serializeText(frame);
      total = Math.addExact(total, (long) line.getBytes(StandardCharsets.UTF_8).length + 2);
      lines.add(line);
    }
    return new FrameSequence(List.copyOf(lines), total);
  }

  /** The serialized lines without line terminators. */
  public List<String> lines() {
    return lines;
  }

  /** Exact bytes on the wire: tags, command, parameters and CRLF of every line. */
  public long bytes() {
    return bytes;
  }
}
