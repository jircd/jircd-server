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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.ConnectionMonitorLog;
import net.jircd.core.session.SessionWriterTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class SendqOverflowTest {
    private static final Duration STEP = Duration.ofSeconds(5);
    private static final String CONFIG =
            "sendqLimitBytes: 16384\nrateLimit:\n  bucketSize: 100000\n  refillRatePerSecond: 100000\n";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonReaderIsClosedAndSlowReaderCanReceiveError(boolean resumeReading) throws Exception {
        SocketBuffers buffers = new SocketBuffers();
        try (TestServer server = TestServer.start(CONFIG, buffers);
                RawIrcClient stuck = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient sender = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient reader = RawIrcClient.connectPlaintext(server.plaintextPort())) {
            join(stuck, "stuck");
            join(sender, "sender");
            join(reader, "reader");
            stuck.readThroughPong(STEP);
            sender.readThroughPong(STEP);
            reader.readThroughPong(STEP);
            ClientSession session =
                    server.application.nicknameRegistry().lookup("stuck").orElseThrow();
            // Borrowed from ConnectionHandler; TestServer owns its lifetime and we observe its
            // close.
            @SuppressWarnings("PMD.CloseResource")
            Socket serverSocket = buffers.constrainReceiver(stuck);
            long start = System.nanoTime();
            List<String> received = floodUntil(sender, reader, session, 0);
            if (received.stream().noneMatch(line -> line.contains(" QUIT "))) {
                received.add(reader.readUntil(" QUIT ", STEP));
            }
            assertThat(received.stream().filter(line -> line.contains(" QUIT ")))
                    .singleElement()
                    .asString()
                    .endsWith(" QUIT :SendQ exceeded");
            if (resumeReading) {
                assertThat(stuck.readUntil("ERROR ", STEP))
                        .isEqualTo("ERROR :Closing Link: 127.0.0.1 (SendQ exceeded)");
                assertThat(stuck.readLine()).isNull();
            }
            long deadline = start + STEP.toNanos();
            while (!serverSocket.isClosed() && System.nanoTime() < deadline)
                LockSupport.parkNanos(5_000_000L);
            assertThat(serverSocket.isClosed()).isTrue();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(STEP);
            assertThat(reader.readThroughPong(STEP)).anyMatch(line -> line.startsWith("PONG "));
        }
    }

    @Test
    void overflowRacingQuitBroadcastsAndLogsDisconnectExactlyOnce() throws Exception {
        SocketBuffers buffers = new SocketBuffers();
        try (TestServer server = TestServer.start(CONFIG, buffers);
                RawIrcClient stuck = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient sender = RawIrcClient.connectPlaintext(server.plaintextPort());
                RawIrcClient reader = RawIrcClient.connectPlaintext(server.plaintextPort());
                LogCapture log = new LogCapture(ConnectionMonitorLog.class);
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            join(stuck, "stuck");
            join(sender, "sender");
            join(reader, "reader");
            stuck.readThroughPong(STEP);
            sender.readThroughPong(STEP);
            reader.readThroughPong(STEP);
            ClientSession session =
                    server.application.nicknameRegistry().lookup("stuck").orElseThrow();
            buffers.constrainReceiver(stuck);
            List<String> received = floodUntil(sender, reader, session, 15360);
            CountDownLatch gate = new CountDownLatch(1);
            var quitting =
                    threads.submit(
                            () -> {
                                gate.await();
                                stuck.send("QUIT :finished");
                                return null;
                            });
            var flooding =
                    threads.submit(
                            () -> {
                                gate.await();
                                for (int i = 0; i < 4; i++)
                                    sender.send("PRIVMSG #q :" + "x".repeat(480));
                                return null;
                            });
            gate.countDown();
            quitting.get(5, TimeUnit.SECONDS);
            flooding.get(5, TimeUnit.SECONDS);
            received.add(reader.readUntil(" QUIT ", STEP));
            // stop is the completion barrier for all server-side cleanup before counting log
            // entries.
            received.addAll(reader.readThroughPong(STEP));
            server.application.stop();
            assertThat(
                            received.stream()
                                    .filter(
                                            line ->
                                                    line.startsWith(":stuck!")
                                                            && line.contains(" QUIT ")))
                    .hasSize(1);
            assertThat(
                            log.messages().stream()
                                    .filter(
                                            line ->
                                                    line.contains("connection-event=disconnected")
                                                            && line.contains(
                                                                    "connection="
                                                                            + session.connectionId()
                                                                            + " ")))
                    .hasSize(1);
        }
    }

    @Test
    void overflowLogsOneSendqEntryAndOneDisconnectWithTheConfiguredLimit() throws Exception {
        long firstTotal = -1;
        for (int round = 0; round < 2; round++) {
            SocketBuffers buffers = new SocketBuffers();
            try (TestServer server = TestServer.start(CONFIG, buffers);
                    RawIrcClient stuck = RawIrcClient.connectPlaintext(server.plaintextPort());
                    RawIrcClient sender = RawIrcClient.connectPlaintext(server.plaintextPort());
                    RawIrcClient reader = RawIrcClient.connectPlaintext(server.plaintextPort());
                    LogCapture log = new LogCapture(ConnectionMonitorLog.class)) {
                join(stuck, "stuck");
                join(sender, "sender");
                join(reader, "reader");
                stuck.readThroughPong(STEP);
                sender.readThroughPong(STEP);
                reader.readThroughPong(STEP);
                ClientSession session =
                        server.application.nicknameRegistry().lookup("stuck").orElseThrow();
                buffers.constrainReceiver(stuck);
                floodUntil(sender, reader, session, 0);
                reader.readThroughPong(STEP);
                server.application.stop();
                String token = "connection=" + session.connectionId() + " ";
                List<String> overflow =
                        log.messages().stream()
                                .filter(
                                        m ->
                                                m.startsWith("connection-event=sendq-overflow")
                                                        && m.contains(token))
                                .toList();
                assertThat(overflow).hasSize(1);
                assertThat(overflow.get(0))
                        .contains(" limitBytes=16384 ")
                        .contains(" queuedBytes=");
                assertThat(
                                log.messages().stream()
                                        .filter(
                                                m ->
                                                        m.startsWith(
                                                                        "connection-event=disconnected")
                                                                && m.contains(token)
                                                                && m.endsWith(
                                                                        "reason=SendQ exceeded")))
                        .hasSize(1);
                Matcher total = Pattern.compile("overflowTotal=(\\d+)").matcher(overflow.get(0));
                assertThat(total.find()).isTrue();
                long value = Long.parseLong(total.group(1));
                if (round == 0) firstTotal = value;
                else assertThat(value).isEqualTo(firstTotal + 1);
            }
        }
    }

    private static void join(RawIrcClient client, String nick) throws Exception {
        client.registerAndAwaitWelcome(nick, nick);
        client.send("JOIN #q");
        client.readUntil(" 366 ", STEP);
    }

    private static List<String> floodUntil(
            RawIrcClient sender, RawIrcClient reader, ClientSession target, long queuedThreshold)
            throws Exception {
        List<String> received = new ArrayList<>();
        long deadline = System.nanoTime() + STEP.toNanos();
        int sequence = 0;
        while (!target.lifecycle().isClosing() && System.nanoTime() < deadline) {
            String body = "flood-" + sequence++ + "-" + "x".repeat(460);
            sender.send("PRIVMSG #q :" + body);
            String line;
            do {
                line = reader.readUntil(" ", STEP);
                received.add(line);
            } while (!line.contains(body));
            if (queuedThreshold > 0
                    && SessionWriterTestAccess.queuedBytes(target.writer()) >= queuedThreshold)
                return received;
        }
        assertThat(target.lifecycle().isClosing()).isTrue();
        return received;
    }

    /** Captures borrowed sockets through the fixture hook; TestServer still owns their lifetime. */
    private static final class SocketBuffers implements Consumer<Socket> {
        private final Map<Integer, Socket> accepted = new ConcurrentHashMap<>();

        @Override
        public void accept(Socket socket) {
            try {
                socket.setSendBufferSize(1024);
                accepted.put(socket.getPort(), socket);
            } catch (SocketException e) {
                throw new UncheckedIOException(e);
            }
        }

        Socket constrainReceiver(RawIrcClient client) throws SocketException {
            client.setReceiveBufferSize(1024);
            return java.util.Objects.requireNonNull(accepted.get(client.localPort()));
        }
    }
}
