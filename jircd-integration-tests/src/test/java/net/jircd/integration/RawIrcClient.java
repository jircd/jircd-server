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

  private RawIrcClient(Socket socket) throws IOException {
    this.socket = socket;
    this.reader =
        new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    this.out = socket.getOutputStream();
  }

  public static RawIrcClient connectPlaintext(int port) throws IOException {
    return new RawIrcClient(new Socket(TestServer.LOOPBACK, port));
  }

  /** Test seam to force receive-side backpressure independently of host TCP defaults. */
  void setReceiveBufferSize(int bytes) throws SocketException {
    socket.setReceiveBufferSize(bytes);
  }

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

  public int localPort() {
    return socket.getLocalPort();
  }

  public void send(String line) throws IOException {
    out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /**
   * Writes a raw line verbatim (plus CR-LF), for tests that need to put invalid-UTF-8 byte
   * sequences on the wire — impossible via {@link #send(String)} since a Java {@code String} cannot
   * represent malformed UTF-8 in the first place.
   */
  public void sendRawBytes(byte[] line) throws IOException {
    out.write(line);
    out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  public String readLine() throws IOException {
    return reader.readLine();
  }

  /**
   * Reads lines until one containing {@code prefix} is found (or the timeout elapses), returning
   * it. A purely-numeric {@code prefix} (a reply code) is matched as an isolated digit run — not a
   * raw substring — so it can't spuriously match inside an unrelated free-text numeric, e.g. a
   * {@code 221} search matching inside {@code RPL_CREATED}'s {@code ...42.221297Z} timestamp. A
   * purely-uppercase-letters {@code prefix} (an IRC command name, e.g. {@code PING}) gets the same
   * word-boundary protection, for the identical reason — {@code CASEMAPPING=rfc1459} in a {@code
   * 005 RPL_ISUPPORT} line ends in the literal substring {@code PING} (from {@code ...maPPING}),
   * which a raw substring search would spuriously match well before any real {@code PING} arrives.
   */
  public String readUntil(String prefix, Duration timeout) throws IOException {
    boolean isNumeric = prefix.chars().allMatch(Character::isDigit);
    boolean isUppercaseWord =
        !prefix.isEmpty() && prefix.chars().allMatch(c -> Character.isUpperCase((char) c));
    java.util.regex.Pattern wordBoundary =
        isNumeric
            ? java.util.regex.Pattern.compile("(?<!\\d)" + prefix + "(?!\\d)")
            : isUppercaseWord
                ? java.util.regex.Pattern.compile("(?<![A-Za-z])" + prefix + "(?![A-Za-z])")
                : null;
    Instant deadline = Instant.now().plus(timeout);
    socket.setSoTimeout(Math.max(1, (int) timeout.toMillis()));
    while (Instant.now().isBefore(deadline)) {
      String line = reader.readLine();
      if (line == null) {
        throw new IOException("Connection closed while waiting for: " + prefix);
      }
      boolean matches =
          wordBoundary != null ? wordBoundary.matcher(line).find() : line.contains(prefix);
      if (matches) {
        return line;
      }
    }
    throw new IOException("Timed out waiting for: " + prefix);
  }

  /**
   * Sends a unique PING barrier and returns all lines through its PONG, in wire order. The supplied
   * timeout bounds the whole read and is explicitly applied to each socket read.
   */
  public List<String> readThroughPong(Duration timeout) throws IOException {
    String token = "barrier-" + UUID.randomUUID();
    send("PING " + token);
    Instant deadline = Instant.now().plus(timeout);
    List<String> lines = new ArrayList<>();
    while (Instant.now().isBefore(deadline)) {
      long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
      socket.setSoTimeout((int) Math.max(1, remainingMillis));
      String line = reader.readLine();
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
      while ((line = reader.readLine()) != null) {
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
    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}

    @Override
    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
      return new java.security.cert.X509Certificate[0];
    }
  }
}
