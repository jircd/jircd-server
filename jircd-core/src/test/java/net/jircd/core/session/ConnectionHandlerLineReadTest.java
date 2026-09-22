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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ConnectionHandlerLineReadTest {

  private static final byte[] CRLF = {'\r', '\n'};

  private static ByteArrayInputStream stream(byte[] bytes) {
    return new ByteArrayInputStream(bytes);
  }

  @Test
  void unterminatedOversizedLineIsDiscardedNotBuffered() throws IOException {
    byte[] huge = new byte[1_000_000];
    Arrays.fill(huge, (byte) 'a');
    assertSame(ConnectionHandler.OVERSIZED_LINE, ConnectionHandler.readLineBytes(stream(huge)));
  }

  @Test
  void oversizedLineDoesNotSwallowFollowingLine() throws IOException {
    byte[] big = new byte[10_000];
    Arrays.fill(big, (byte) 'a');
    byte[] tail = "\r\nNICK bob\r\n".getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[big.length + tail.length];
    System.arraycopy(big, 0, all, 0, big.length);
    System.arraycopy(tail, 0, all, big.length, tail.length);
    ByteArrayInputStream in = stream(all);
    assertSame(ConnectionHandler.OVERSIZED_LINE, ConnectionHandler.readLineBytes(in));
    assertArrayEquals(
        "NICK bob".getBytes(StandardCharsets.UTF_8), ConnectionHandler.readLineBytes(in));
    assertNull(ConnectionHandler.readLineBytes(in));
  }

  @Test
  void normalLineStillReadAndCrStripped() throws IOException {
    assertArrayEquals(
        "PING x".getBytes(StandardCharsets.UTF_8),
        ConnectionHandler.readLineBytes(stream("PING x\r\n".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void multibyteLineUnderByteCapIsReturnedIntact() throws IOException {
    // 1000 three-byte characters = 3000 bytes, under the 4608-byte cap.
    byte[] line = "€".repeat(1000).getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(line, ConnectionHandler.readLineBytes(stream(withNewline(line))));
  }

  @Test
  void capIsMeasuredInBytesNotCharacters() throws IOException {
    // 2000 three-byte characters = 6000 bytes: well under 4608 characters, over 4608 bytes.
    byte[] line = "€".repeat(2000).getBytes(StandardCharsets.UTF_8);
    assertSame(
        ConnectionHandler.OVERSIZED_LINE,
        ConnectionHandler.readLineBytes(stream(withNewline(line))));
  }

  @Test
  void multibyteCharacterStraddlingTheCapIsDiscardedWithoutDecoding() throws IOException {
    // 4607 ASCII bytes plus a 3-byte character crosses the 4608-byte cap mid-sequence.
    byte[] line = ("a".repeat(4607) + "€").getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream in =
        stream(withNewline(line, "NICK bob".getBytes(StandardCharsets.UTF_8)));
    assertSame(ConnectionHandler.OVERSIZED_LINE, ConnectionHandler.readLineBytes(in));
    assertArrayEquals(
        "NICK bob".getBytes(StandardCharsets.UTF_8), ConnectionHandler.readLineBytes(in));
  }

  private static byte[] withNewline(byte[] line, byte[]... following) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    out.writeBytes(line);
    out.writeBytes(CRLF);
    for (byte[] next : following) {
      out.writeBytes(next);
      out.writeBytes(CRLF);
    }
    return out.toByteArray();
  }

  @Test
  void bareLineFeedTerminatorIsAlsoAccepted() throws IOException {
    assertArrayEquals(
        "PING x".getBytes(StandardCharsets.UTF_8),
        ConnectionHandler.readLineBytes(stream("PING x\n".getBytes(StandardCharsets.UTF_8))));
  }

  @Test
  void carriageReturnIsNotCountedTowardTheReturnedLine() throws IOException {
    // 4606 bytes is the largest legal stripped line (4096 tags + 510 command). The reader buffers
    // it plus the trailing CR (4607 bytes; the LF is never stored), which is under the 4608 cap and
    // must not be discarded.
    byte[] line = ("@" + "t".repeat(4094) + " " + "a".repeat(510)).getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(line, ConnectionHandler.readLineBytes(stream(withNewline(line))));
  }
}
