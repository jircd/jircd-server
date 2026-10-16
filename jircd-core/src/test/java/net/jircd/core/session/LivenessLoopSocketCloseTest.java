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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/**
 * Issue #38: the liveness thread closes the socket as soon as it sees {@code CLOSING}, but {@code
 * DisconnectCleanup} sets {@code CLOSING} first and only then drains the writer. A liveness thread
 * scheduled late (CPU load) therefore closed the socket under a still-queued {@code ERROR}/numeric,
 * and the client saw EOF instead.
 */
class LivenessLoopSocketCloseTest {

  @Test
  @SuppressWarnings("PMD.CloseResource") // the fake socket/writer are closed by the code under test
  void socketIsNotClosedWhileTheWriterStillHasQueuedOutput() throws Exception {
    var session =
        new ClientSession("c1", "127.0.0.1", new RateLimitBucket(20, 10.0, Clock.systemUTC()));
    var writeStarted = new CountDownLatch(1);
    var releaseWrite = new CountDownLatch(1);
    var written = new AtomicBoolean();
    OutputStream blockingOut =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            writeStarted.countDown();
            try {
              releaseWrite.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IOException(e);
            }
            written.set(true);
          }
        };
    var closed = new CountDownLatch(1);
    var socketClosedAfterWrite = new AtomicBoolean();
    Socket socket =
        new Socket() {
          @Override
          public void close() {
            socketClosedAfterWrite.set(written.get());
            closed.countDown();
          }
        };
    var writer = new SessionWriter(session, blockingOut, (recipient, message) -> Map.of());
    session.attachWriter(writer);
    writer.enqueueRaw(new Message(Map.of(), null, Command.ERROR, "ERROR", List.of("bye")));
    assertTrue(writeStarted.await(5, TimeUnit.SECONDS));

    // DisconnectCleanup's first step; its writer drain (blocked on our stream) comes after.
    session.lifecycle().closeIfNotAlreadyClosing();
    Thread liveness =
        Thread.ofVirtual().start(() -> ConnectionHandler.runLivenessLoop(session, null, socket));

    assertFalse(
        closed.await(300, TimeUnit.MILLISECONDS), "socket closed while output was still queued");
    releaseWrite.countDown();
    assertTrue(closed.await(5, TimeUnit.SECONDS));
    assertTrue(socketClosedAfterWrite.get());
    liveness.join();
  }
}
