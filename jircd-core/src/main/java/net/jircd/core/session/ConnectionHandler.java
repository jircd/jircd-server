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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ConnectionAdmissionExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.MalformedMessageException;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import net.jircd.protocol.NumericReply;
import net.jircd.protocol.Utf8Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-connection command dispatch loop (FR-015): blocking read → parse via {@code
 * jircd-protocol} → route to a registered handler, matched case-insensitively. Before creating a
 * {@link ClientSession} at all, consults the {@code connection-admission} extension point (FR-066);
 * with nothing claiming it this release, every connection is admitted.
 *
 * <p>Reads raw bytes and validates each line's UTF-8 well-formedness (FR-054) before ever decoding
 * it to a {@code String} — a lenient, char-stream-based reader (e.g. {@code
 * InputStreamReader}/{@code BufferedReader} over a {@code Charset}) silently substitutes the
 * replacement character for malformed input by default, which would make every downstream per-field
 * {@code Utf8Validator} check in individual command handlers permanently unreachable: by the time a
 * handler sees a {@code String}, any invalid byte sequence has already been silently sanitized
 * away. Validating the whole raw line once, here, is the single source of truth instead.
 */
public final class ConnectionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ConnectionHandler.class);

  /**
   * 512 bytes (command+params, CR-LF inclusive) and up to 4096 bytes for a message-tags section
   * (FR-049) are two INDEPENDENT limits (005-fix-batch-conformance FR-011), not one combined figure
   * — a tag section alone exceeding 4096 bytes is rejected even if the line's total length would
   * fit under the old combined 4608-byte check.
   */
  private static final int MAX_TAG_SECTION_BYTES = 4096;

  private static final int MAX_COMMAND_SECTION_BYTES = 512;

  /**
   * Hard cap on bytes buffered per line while reading, before any validation runs: the two section
   * limits combined. Anything longer cannot be valid, so the excess is discarded unread into memory
   * rather than accumulated.
   */
  private static final int MAX_LINE_BYTES = MAX_TAG_SECTION_BYTES + MAX_COMMAND_SECTION_BYTES;

  /** Returned by {@link #readLineBytes} for a line that exceeded {@link #MAX_LINE_BYTES}. */
  static final byte[] OVERSIZED_LINE = new byte[0];

  /** Commands valid before registration completes (FR-001, FR-060, FR-039, FR-006). */
  private static final java.util.Set<Command> PRE_REGISTRATION_COMMANDS =
      java.util.Set.of(
          Command.NICK, Command.USER, Command.CAP, Command.PING, Command.PONG, Command.QUIT);

  /** Reasonable, industry-standard keep-alive response-timeout default (FR-039). */
  private static final Duration KEEP_ALIVE_TIMEOUT = Duration.ofSeconds(10);

  /** How often the per-connection liveness loop re-evaluates {@link LivenessMonitor#checkNow()}. */
  private static final Duration LIVENESS_CHECK_TICK = Duration.ofSeconds(5);

  private final ExtensionRegistry extensionRegistry;
  private final DisconnectCleanup disconnectCleanup;
  private final Supplier<String> serverName;
  private final Supplier<ServerConfiguration.RateLimit> rateLimit;
  private final Supplier<Integer> keepAliveFrequencySeconds;
  private final TagRenderer tagRenderer;
  private final Map<Command, CommandHandler> handlers = new ConcurrentHashMap<>();

  public ConnectionHandler(
      ExtensionRegistry extensionRegistry,
      DisconnectCleanup disconnectCleanup,
      Supplier<String> serverName,
      Supplier<ServerConfiguration.RateLimit> rateLimit,
      Supplier<Integer> keepAliveFrequencySeconds) {
    this.extensionRegistry = extensionRegistry;
    this.disconnectCleanup = disconnectCleanup;
    this.serverName = serverName;
    this.rateLimit = rateLimit;
    this.keepAliveFrequencySeconds = keepAliveFrequencySeconds;
    this.tagRenderer = new CapabilityTagRenderer(extensionRegistry);
  }

  public void registerHandler(Command command, CommandHandler handler) {
    handlers.put(command, handler);
  }

  /** Spawns one virtual thread to own this connection's lifetime end to end. */
  public void accept(Socket socket) {
    String connectionId = UUID.randomUUID().toString();
    Thread.ofVirtual()
        .name("connection-" + connectionId)
        .start(() -> handleConnection(socket, connectionId));
  }

  private void handleConnection(Socket socket, String connectionId) {
    String remoteAddress =
        socket.getInetAddress() == null ? "unknown" : socket.getInetAddress().getHostAddress();
    if (!isAdmitted(remoteAddress)) {
      closeQuietly(socket);
      return;
    }

    ServerConfiguration.RateLimit configuredRateLimit = rateLimit.get();
    ClientSession session =
        new ClientSession(
            connectionId,
            remoteAddress,
            new RateLimitBucket(
                configuredRateLimit.bucketSize(),
                configuredRateLimit.refillRatePerSecond(),
                java.time.Clock.systemUTC()));
    ConnectionMonitorLog.connected(connectionId, remoteAddress);
    // Attached to the session and closed later via DisconnectCleanup, not in this method.
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer;
    try {
      writer = new SessionWriter(session, socket.getOutputStream(), tagRenderer);
    } catch (IOException e) {
      closeQuietly(socket);
      return;
    }
    session.attachWriter(writer);
    writer.setOnOverflow(() -> disconnectCleanup.cleanup(session, "Excess Flood"));

    LivenessMonitor livenessMonitor =
        new LivenessMonitor(
            session,
            disconnectCleanup,
            Duration.ofSeconds(keepAliveFrequencySeconds.get()),
            KEEP_ALIVE_TIMEOUT,
            Clock.systemUTC());
    session.attachLivenessMonitor(livenessMonitor);
    Thread.ofVirtual()
        .name("liveness-" + connectionId)
        .start(() -> runLivenessLoop(session, livenessMonitor, socket));

    try (BufferedInputStream in = new BufferedInputStream(socket.getInputStream())) {
      byte[] lineBytes;
      while ((lineBytes = readLineBytes(in)) != null) {
        if (session.lifecycle().isClosing()) {
          break;
        }
        if (lineBytes == OVERSIZED_LINE) {
          Replies.send(
              session, serverName.get(), NumericReply.ERR_INPUTTOOLONG, "Input line was too long");
        } else {
          processLine(session, lineBytes);
        }
        if (session.lifecycle().isClosing()) {
          break;
        }
      }
      if (!session.lifecycle().isClosing()) {
        disconnectCleanup.cleanup(session, "Connection reset by peer");
      }
    } catch (IOException e) {
      if (!session.lifecycle().isClosing()) {
        disconnectCleanup.cleanup(session, "Connection reset by peer");
      }
    } finally {
      closeQuietly(socket);
    }
  }

  /**
   * Ticks {@link LivenessMonitor#checkNow()} on its own virtual thread (FR-039) — the connection's
   * main thread is blocked inside {@link #readLineBytes} waiting for socket input, so probing idle
   * connections needs a separate thread. {@link DisconnectCleanup#cleanup} only closes the writer
   * and marks the session {@code CLOSING}; it has no reference to the socket itself, so a
   * disconnect triggered from any thread other than this connection's own main thread (a keep-alive
   * timeout, here, or an outbound-queue overflow, {@code SessionWriter}'s {@code onOverflow}) would
   * otherwise leave the main thread blocked in {@code read()} forever, waiting for input that will
   * never arrive. This loop is this connection's single reliable backstop: whenever the session
   * becomes {@code CLOSING} for any reason, it closes the socket directly, which unblocks that read
   * and lets the main thread reach its own cleanup path.
   */
  private static void runLivenessLoop(
      ClientSession session, LivenessMonitor livenessMonitor, Socket socket) {
    while (!session.lifecycle().isClosing()) {
      try {
        Thread.sleep(LIVENESS_CHECK_TICK);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      if (session.lifecycle().isClosing()) {
        break;
      }
      livenessMonitor.checkNow();
    }
    closeQuietly(socket);
  }

  /**
   * Reads raw bytes up to the next {@code \n} (an optional preceding {@code \r} is stripped),
   * returning them without decoding. Returns {@code null} only at end-of-stream with no bytes read
   * at all — a final, unterminated line at EOF is still returned, the same as {@code
   * BufferedReader#readLine()} would.
   *
   * <p>Buffering is bounded by {@link #MAX_LINE_BYTES}: once a line exceeds it, further bytes up to
   * the next {@code \n} are discarded without being stored and {@link #OVERSIZED_LINE} is returned.
   */
  static byte[] readLineBytes(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    boolean oversized = false;
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        break;
      }
      if (buffer.size() < MAX_LINE_BYTES) {
        buffer.write(b);
      } else {
        oversized = true;
      }
    }
    if (oversized) {
      return OVERSIZED_LINE;
    }
    if (b == -1 && buffer.size() == 0) {
      return null;
    }
    byte[] bytes = buffer.toByteArray();
    int length = bytes.length;
    if (length > 0 && bytes[length - 1] == '\r') {
      length--;
    }
    return length == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, length);
  }

  /**
   * The tag section's own byte length, {@code 0} if {@code lineBytes} doesn't start with a
   * message-tags {@code @} prefix at all — a tag value never contains a literal space (spaces are
   * escaped as {@code \s}), so the section always ends at the first raw space byte, if any. Per the
   * message-tags spec, the 4096-byte limit counts the leading {@code @} AND the trailing space
   * separating the tags from the command — so the space itself is included here, not excluded.
   */
  private static int tagSectionLength(byte[] lineBytes) {
    if (lineBytes.length == 0 || lineBytes[0] != '@') {
      return 0;
    }
    int spaceIndex = indexOfSpace(lineBytes);
    return spaceIndex >= 0 ? spaceIndex + 1 : lineBytes.length;
  }

  /** The command+params section's own byte length, excluding any leading tag section. */
  private static int commandSectionLength(byte[] lineBytes) {
    if (lineBytes.length == 0 || lineBytes[0] != '@') {
      return lineBytes.length;
    }
    int spaceIndex = indexOfSpace(lineBytes);
    return spaceIndex >= 0 ? lineBytes.length - spaceIndex - 1 : 0;
  }

  private static int indexOfSpace(byte[] lineBytes) {
    for (int i = 0; i < lineBytes.length; i++) {
      if (lineBytes[i] == ' ') {
        return i;
      }
    }
    return -1;
  }

  private void processLine(ClientSession session, byte[] lineBytes) {
    if (!Utf8Validator.isValidUtf8(lineBytes)) {
      // 005-fix-batch-conformance FR-012 — a definitive, client-visible connection end (the same
      // enqueue-then-cleanup shape QuitCommandHandler/KillCommandHandler/LivenessMonitor already
      // use), not a 421-and-return that leaves the session stranded with no path forward.
      if (session.writer() != null) {
        session
            .writer()
            .enqueueRaw(
                new Message(
                    Map.of(),
                    null,
                    Command.ERROR,
                    "ERROR",
                    java.util.List.of("Malformed message (invalid UTF-8)")));
      }
      disconnectCleanup.cleanup(session, "Malformed message (invalid UTF-8)");
      return;
    }
    if (tagSectionLength(lineBytes) > MAX_TAG_SECTION_BYTES
        || commandSectionLength(lineBytes) + 2 > MAX_COMMAND_SECTION_BYTES) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_INPUTTOOLONG, "Input line was too long");
      return;
    }
    String line = new String(lineBytes, StandardCharsets.UTF_8);

    Message message;
    try {
      message = MessageParser.parse(line);
    } catch (MalformedMessageException e) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_UNKNOWNCOMMAND, line, "Malformed message");
      return;
    }

    if (!session.rateLimitBucket().tryConsume()) {
      return; // FR-016: silently drop over-rate traffic rather than disconnecting immediately
    }

    if (message.command() == null) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNCOMMAND,
          message.rawCommand(),
          "Unknown command");
      return;
    }

    CommandHandler handler = handlers.get(message.command());
    if (handler == null) {
      Replies.send(
          session,
          serverName.get(),
          NumericReply.ERR_UNKNOWNCOMMAND,
          message.rawCommand(),
          "Unknown command");
      return;
    }

    if (!session.lifecycle().isRegistered()
        && !PRE_REGISTRATION_COMMANDS.contains(message.command())) {
      Replies.send(
          session, serverName.get(), NumericReply.ERR_NOTREGISTERED, "You have not registered");
      return;
    }

    try {
      handler.handle(session, message);
    } catch (RuntimeException e) {
      LOG.warn(
          "Command handler for {} threw on connection {}",
          message.command(),
          session.connectionId(),
          e);
    }
  }

  private boolean isAdmitted(String remoteAddress) {
    for (Extension extension : extensionRegistry.enabled()) {
      if (extension instanceof ServerExtension serverExtension
          && "connection-admission".equals(serverExtension.extensionPoint())
          && extension instanceof ConnectionAdmissionExtension admission) {
        return admission.admit(remoteAddress);
      }
    }
    return true; // nothing claims connection-admission this release — always permit
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
