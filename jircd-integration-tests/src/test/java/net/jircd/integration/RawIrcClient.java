/*
 * Copyright 2026 JIRCD Maintainers
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A minimal raw-socket IRC client for protocol-level integration tests — no jircd-protocol
 * dependency, by design.
 */
public final class RawIrcClient implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader reader;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final AtomicInteger answeredServerPings = new AtomicInteger();
    private volatile boolean answerServerPings;

    private RawIrcClient(Socket socket) throws IOException {
        this.socket = socket;
        this.reader =
                new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = socket.getOutputStream();
    }

    public static RawIrcClient connectPlaintext(int port) throws IOException {
        return new RawIrcClient(new Socket(TestServer.LOOPBACK, port));
    }

    /** Test seam to force receive-side backpressure independently of host TCP defaults. */
    void setReceiveBufferSize(int bytes) throws SocketException {
        socket.setReceiveBufferSize(bytes);
    }

    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;

    public static RawIrcClient connectTls(int port) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new TrustAllTrustManager()}, null);
        return new RawIrcClient(context.getSocketFactory().createSocket(TestServer.LOOPBACK, port));
    }

    /**
     * The SHA-256 fingerprint of the server certificate presented during this connection's TLS
     * handshake — for tests proving certificate identity persists across a restart
     * (004-fix-tls-certificate SC-001). Only valid for a connection made via {@link
     * #connectTls(int)}.
     */
    public String peerCertificateFingerprint() throws Exception {
        // Borrowed reference to this instance's own still-open socket — closed by this class's own
        // close(), not here.
        @SuppressWarnings("PMD.CloseResource")
        var sslSocket = (javax.net.ssl.SSLSocket) socket;
        var certificate = sslSocket.getSession().getPeerCertificates()[0];
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(certificate.getEncoded());
        return java.util.HexFormat.of().formatHex(hash);
    }

    /**
     * The certificate chain the server presented in this connection's TLS handshake, leaf first.
     */
    public java.security.cert.Certificate[] peerCertificateChain() throws Exception {
        // Borrowed reference to this instance's own still-open socket, closed by close().
        @SuppressWarnings("PMD.CloseResource")
        var sslSocket = (javax.net.ssl.SSLSocket) socket;
        // getSession() runs the handshake; bound it so a stalled server fails the test.
        sslSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
        try {
            return sslSocket.getSession().getPeerCertificates();
        } finally {
            sslSocket.setSoTimeout(0);
        }
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public void send(String line) throws IOException {
        synchronized (writeLock) {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** Enables automatic replies to server PINGs while preserving those lines for the caller. */
    void answerServerPings() {
        answerServerPings = true;
    }

    int answeredServerPingCount() {
        return answeredServerPings.get();
    }

    /**
     * Writes a raw line verbatim (plus CR-LF), for tests that need to put invalid-UTF-8 byte
     * sequences on the wire — impossible via {@link #send(String)} since a Java {@code String}
     * cannot represent malformed UTF-8 in the first place.
     */
    public void sendRawBytes(byte[] line) throws IOException {
        synchronized (writeLock) {
            out.write(line);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * Waits up to {@code duration} for the server to send or close; {@code true} if it did neither.
     * A line that arrives is consumed, so a {@code false} result means the connection was not
     * quiet.
     */
    public boolean staysSilentFor(Duration duration) throws IOException {
        socket.setSoTimeout((int) duration.toMillis());
        try {
            readWireLine();
            return false;
        } catch (java.net.SocketTimeoutException _) {
            return true;
        } finally {
            socket.setSoTimeout(0);
        }
    }

    public String readLine() throws IOException {
        return readWireLine();
    }

    private String readWireLine() throws IOException {
        String line = reader.readLine();
        if (answerServerPings && line != null) {
            String token = serverPingToken(line);
            if (token != null) {
                try {
                    send("PONG :" + token);
                    answeredServerPings.incrementAndGet();
                } catch (IOException _) {
                    // The line was already read; preserve it for callers even if the peer closed.
                }
            }
        }
        return line;
    }

    static String serverPingToken(String line) {
        String message = line;
        if (message.startsWith("@")) {
            int tagsEnd = message.indexOf(' ');
            if (tagsEnd < 0) return null;
            message = message.substring(tagsEnd + 1).stripLeading();
        }
        if (message.startsWith(":")) {
            int prefixEnd = message.indexOf(' ');
            if (prefixEnd < 0) return null;
            message = message.substring(prefixEnd + 1).stripLeading();
        }

        int commandEnd = message.indexOf(' ');
        String command = commandEnd < 0 ? message : message.substring(0, commandEnd);
        if (!command.equalsIgnoreCase("PING") || commandEnd < 0) return null;

        String parameter = message.substring(commandEnd + 1).stripLeading();
        if (parameter.isEmpty()) return null;
        if (parameter.startsWith(":")) {
            String trailing = parameter.substring(1);
            return trailing.isEmpty() ? null : trailing;
        }
        int nextParameter = parameter.indexOf(' ');
        return nextParameter < 0 ? parameter : parameter.substring(0, nextParameter);
    }

    /**
     * Reads lines until one containing {@code prefix} is found (or the timeout elapses), returning
     * it. A purely-numeric {@code prefix} (a reply code) is matched as an isolated digit run — not
     * a raw substring — so it can't spuriously match inside an unrelated free-text numeric, e.g. a
     * {@code 221} search matching inside {@code RPL_CREATED}'s {@code ...42.221297Z} timestamp. A
     * purely-uppercase-letters {@code prefix} (an IRC command name, e.g. {@code PING}) gets the
     * same word-boundary protection, for the identical reason — {@code CASEMAPPING=rfc1459} in a
     * {@code 005 RPL_ISUPPORT} line ends in the literal substring {@code PING} (from {@code
     * ...maPPING}), which a raw substring search would spuriously match well before any real {@code
     * PING} arrives.
     */
    public String readUntil(String prefix, Duration timeout) throws IOException {
        boolean isNumeric = prefix.chars().allMatch(Character::isDigit);
        boolean isUppercaseWord =
                !prefix.isEmpty() && prefix.chars().allMatch(c -> Character.isUpperCase((char) c));
        Pattern wordBoundary =
                isNumeric
                        ? Pattern.compile("(?<!\\d)" + prefix + "(?!\\d)")
                        : isUppercaseWord
                                ? Pattern.compile("(?<![A-Za-z])" + prefix + "(?![A-Za-z])")
                                : null;
        Instant deadline = Instant.now().plus(timeout);
        socket.setSoTimeout(Math.max(1, (int) timeout.toMillis()));
        while (Instant.now().isBefore(deadline)) {
            String line = readWireLine();
            if (line == null) {
                throw new IOException("Connection closed while waiting for: " + prefix);
            }
            boolean matches =
                    wordBoundary != null
                            ? wordBoundary.matcher(line).find()
                            : line.contains(prefix);
            if (matches) {
                return line;
            }
        }
        throw new IOException("Timed out waiting for: " + prefix);
    }

    /**
     * Sends a unique PING barrier and returns all lines through its PONG, in wire order. The
     * supplied timeout bounds the whole read and is explicitly applied to each socket read.
     */
    public List<String> readThroughPong(Duration timeout) throws IOException {
        String token = "barrier-" + UUID.randomUUID();
        send("PING " + token);
        Instant deadline = Instant.now().plus(timeout);
        List<String> lines = new ArrayList<>();
        while (Instant.now().isBefore(deadline)) {
            long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
            socket.setSoTimeout((int) Math.max(1, remainingMillis));
            String line = readWireLine();
            if (line == null) {
                throw new IOException("Connection closed while waiting for barrier PONG");
            }
            lines.add(line);
            if (line.startsWith("PONG ") && line.endsWith(" " + token)) {
                return lines;
            }
        }
        throw new java.net.SocketTimeoutException("Timed out waiting for barrier PONG");
    }

    public List<String> readLinesFor(Duration duration) throws IOException {
        List<String> lines = new ArrayList<>();
        socket.setSoTimeout(Math.max(1, (int) duration.toMillis()));
        try {
            String line;
            while ((line = readWireLine()) != null) {
                lines.add(line);
            }
        } catch (java.net.SocketTimeoutException expected) {
            // expected: we're only reading for a bounded window
        }
        return lines;
    }

    public void registerAndAwaitWelcome(String nickname, String username) throws IOException {
        send("NICK " + nickname);
        send("USER " + username + " 0 * :" + username);
        readUntil(" 001 ", Duration.ofSeconds(5));
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static final class TrustAllTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(
                java.security.cert.X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(
                java.security.cert.X509Certificate[] chain, String authType) {}

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }
}
