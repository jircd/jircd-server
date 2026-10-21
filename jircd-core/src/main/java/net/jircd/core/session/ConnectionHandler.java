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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.jircd.core.config.ConnectionLimitPolicy;
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

  /** Production rejection deadline; tests can override it through {@link Settings}. */
  public static final Duration DEFAULT_REJECTION_DEADLINE = Duration.ofSeconds(1);

  // A TLS error write implicitly handshakes. Bound the entire rejection, even if a peer drips
  // handshake bytes or never reads. One shared daemon timer bounds the rejection workers.
  private static final ScheduledThreadPoolExecutor REJECTION_TIMER =
      daemonTimer("connection-rejection-deadline");
  // Logging has its own executor: a slow summary appender must never delay socket deadlines.
  private static final ScheduledThreadPoolExecutor SUPPRESSED_LOG_TIMER =
      daemonTimer("connection-rejection-summary");

  private static ScheduledThreadPoolExecutor daemonTimer(String name) {
    ScheduledThreadPoolExecutor timer =
        new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon(true).name(name).factory());
    timer.setRemoveOnCancelPolicy(true);
    return timer;
  }

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
  private final Supplier<Integer> sendqLimitBytes;
  private final Supplier<Duration> registrationTimeout;
  private final Supplier<ServerConfiguration.ConnectionLimits> connectionLimits;
  // Guarded by this, together with admission and connection removal.
  private final Map<String, Integer> connectionsPerBucket = new HashMap<>();
  private final ThreadFactory workers;
  private final Duration rejectionDeadline;
  private final SuppressedConnectionLog suppressedLog =
      new SuppressedConnectionLog(
          SUPPRESSED_LOG_TIMER, SecurityEventLog::connectionLimitSuppressed);
  private final TagRenderer tagRenderer;
  private final Consumer<Socket> acceptedSocketCustomizer;
  private boolean accepting = true;
  private final Map<Thread, Socket> connections = new ConcurrentHashMap<>();
  private final Semaphore rejectionSlots = new Semaphore(64);
  private final Map<Thread, Socket> rejections = new ConcurrentHashMap<>();
  private final Map<Command, CommandHandler> handlers = new ConcurrentHashMap<>();

  public ConnectionHandler(
      ExtensionRegistry extensionRegistry,
      DisconnectCleanup disconnectCleanup,
      Supplier<String> serverName,
      Supplier<ServerConfiguration.RateLimit> rateLimit,
      Supplier<Integer> keepAliveFrequencySeconds,
      Supplier<Integer> sendqLimitBytes) {
    this(
        extensionRegistry,
        disconnectCleanup,
        serverName,
        rateLimit,
        keepAliveFrequencySeconds,
        sendqLimitBytes,
        socket -> {});
  }

  /**
   * Live configuration suppliers and a test-overridable rejection deadline. {@code
   * registrationTimeout} is read once per accepted connection, so a reload affects new connections
   * only.
   */
  public record Settings(
      Supplier<ServerConfiguration.RateLimit> rateLimit,
      Supplier<Integer> keepAliveFrequencySeconds,
      Supplier<Integer> sendqLimitBytes,
      Supplier<ServerConfiguration.ConnectionLimits> connectionLimits,
      Duration rejectionDeadline,
      Supplier<Duration> registrationTimeout) {
    /** Uses the production one-second rejection deadline and the default registration timeout. */
    public Settings(
        Supplier<ServerConfiguration.RateLimit> rateLimit,
        Supplier<Integer> keepAliveFrequencySeconds,
        Supplier<Integer> sendqLimitBytes,
        Supplier<ServerConfiguration.ConnectionLimits> connectionLimits) {
      this(
          rateLimit,
          keepAliveFrequencySeconds,
          sendqLimitBytes,
          connectionLimits,
          DEFAULT_REJECTION_DEADLINE);
    }

    /** Uses the default registration timeout. */
    public Settings(
        Supplier<ServerConfiguration.RateLimit> rateLimit,
        Supplier<Integer> keepAliveFrequencySeconds,
        Supplier<Integer> sendqLimitBytes,
        Supplier<ServerConfiguration.ConnectionLimits> connectionLimits,
        Duration rejectionDeadline) {
      this(
          rateLimit,
          keepAliveFrequencySeconds,
          sendqLimitBytes,
          connectionLimits,
          rejectionDeadline,
          () -> Duration.ofSeconds(ServerConfiguration.DEFAULT_REGISTRATION_TIMEOUT_SECONDS));
    }

    public Settings {
      java.util.Objects.requireNonNull(rateLimit);
      java.util.Objects.requireNonNull(keepAliveFrequencySeconds);
      java.util.Objects.requireNonNull(sendqLimitBytes);
      java.util.Objects.requireNonNull(connectionLimits);
      java.util.Objects.requireNonNull(rejectionDeadline);
      java.util.Objects.requireNonNull(registrationTimeout);
      if (rejectionDeadline.isZero() || rejectionDeadline.isNegative())
        throw new IllegalArgumentException("rejectionDeadline must be positive");
    }
  }

  /** Customizes accepted sockets on their tracked connection thread before protocol handling. */
  public ConnectionHandler(
      ExtensionRegistry extensionRegistry,
      DisconnectCleanup disconnectCleanup,
      Supplier<String> serverName,
      Supplier<ServerConfiguration.RateLimit> rateLimit,
      Supplier<Integer> keepAliveFrequencySeconds,
      Supplier<Integer> sendqLimitBytes,
      Consumer<Socket> acceptedSocketCustomizer) {
    this(
        extensionRegistry,
        disconnectCleanup,
        serverName,
        new Settings(
            rateLimit,
            keepAliveFrequencySeconds,
            sendqLimitBytes,
            () ->
                new ServerConfiguration.ConnectionLimits(
                    ServerConfiguration.DEFAULT_MAX_CONNECTIONS,
                    ServerConfiguration.DEFAULT_MAX_CONNECTIONS_PER_IP)),
        acceptedSocketCustomizer);
  }

  /** Uses the current settings for every accept, shared across all listeners. */
  public ConnectionHandler(
      ExtensionRegistry extensionRegistry,
      DisconnectCleanup disconnectCleanup,
      Supplier<String> serverName,
      Settings settings,
      Consumer<Socket> acceptedSocketCustomizer) {
    this(
        extensionRegistry,
        disconnectCleanup,
        serverName,
        settings,
        acceptedSocketCustomizer,
        Thread.ofVirtual().factory());
  }

  /** Test seam for failures constructing or starting an unstarted worker. */
  ConnectionHandler(
      ExtensionRegistry extensionRegistry,
      DisconnectCleanup disconnectCleanup,
      Supplier<String> serverName,
      Settings settings,
      Consumer<Socket> acceptedSocketCustomizer,
      ThreadFactory workers) {
    this.workers = java.util.Objects.requireNonNull(workers);
    this.rejectionDeadline = settings.rejectionDeadline();
    this.connectionLimits = settings.connectionLimits();
    this.acceptedSocketCustomizer = java.util.Objects.requireNonNull(acceptedSocketCustomizer);
    this.extensionRegistry = extensionRegistry;
    this.disconnectCleanup = disconnectCleanup;
    this.serverName = serverName;
    this.rateLimit = settings.rateLimit();
    this.keepAliveFrequencySeconds = settings.keepAliveFrequencySeconds();
    this.sendqLimitBytes = settings.sendqLimitBytes();
    this.registrationTimeout = settings.registrationTimeout();
    this.tagRenderer = new CapabilityTagRenderer(extensionRegistry);
  }

  public void registerHandler(Command command, CommandHandler handler) {
    handlers.put(command, handler);
  }

  /** Reserves admission before spawning one virtual thread to own the connection's lifetime. */
  public synchronized void accept(Socket socket) {
    if (!accepting) {
      closeQuietly(socket);
      return;
    }
    String remoteAddress = socket.getInetAddress().getHostAddress();
    ServerConfiguration.ConnectionLimits limits = connectionLimits.get();
    ConnectionLimitPolicy.AddressLimit addressLimit =
        ConnectionLimitPolicy.resolve(socket.getInetAddress(), limits);
    int ipCount = connectionsPerBucket.getOrDefault(addressLimit.bucket(), 0);
    String exceeded =
        exceededLimit(limits.maxConnections(), ipCount, addressLimit.maxConnectionsPerIp());
    if (exceeded == null) {
      startConnection(socket, addressLimit.bucket(), ipCount);
    } else {
      int appliedLimit =
          exceeded.equals("maxConnections")
              ? limits.maxConnections()
              : addressLimit.maxConnectionsPerIp();
      startRejection(socket, remoteAddress, exceeded, addressLimit.bucket(), appliedLimit);
    }
  }

  private String exceededLimit(int maxConnections, int bucketCount, int bucketLimit) {
    if (connections.size() >= maxConnections) return "maxConnections";
    if (bucketCount >= bucketLimit) return "maxConnectionsPerIp";
    return null;
  }

  private void startRejection(
      Socket socket, String remoteAddress, String exceeded, String bucket, int appliedLimit) {
    if (!rejectionSlots.tryAcquire()) {
      closeQuietly(socket);
      suppressedLog.recordDrop();
      return;
    }
    Thread rejection = null;
    boolean started = false;
    try {
      rejection =
          workers.newThread(
              () -> {
                try {
                  rejectConnection(socket, remoteAddress, exceeded, bucket, appliedLimit);
                } finally {
                  try {
                    closeQuietly(socket);
                  } finally {
                    rejections.remove(Thread.currentThread());
                    rejectionSlots.release();
                  }
                }
              });
      rejection.setName("connection-rejection");
      rejections.put(rejection, socket);
      rejection.start();
      started = true;
    } finally {
      if (!started) {
        if (rejection != null) rejections.remove(rejection);
        rejectionSlots.release();
        closeQuietly(socket);
      }
    }
  }

  private void startConnection(Socket socket, String bucket, int ipCount) {
    Thread connection = null;
    boolean reserved = false;
    boolean started = false;
    try {
      String connectionId = UUID.randomUUID().toString();
      // Capture the sendq budget at accept time, independently of subsequent reloads.
      int acceptedSendqLimit = sendqLimitBytes.get();
      Duration acceptedRegistrationTimeout = registrationTimeout.get();
      Instant acceptedAt = Clock.systemUTC().instant();
      connection =
          workers.newThread(
              () -> {
                try {
                  handleConnection(
                      socket,
                      connectionId,
                      acceptedSendqLimit,
                      acceptedRegistrationTimeout,
                      acceptedAt);
                } finally {
                  try {
                    closeQuietly(socket);
                  } finally {
                    releaseConnection(Thread.currentThread(), bucket);
                  }
                }
              });
      connection.setName("connection-" + connectionId);
      connectionsPerBucket.put(bucket, ipCount + 1);
      reserved = true;
      connections.put(connection, socket);
      connection.start();
      started = true;
    } finally {
      if (!started) {
        if (reserved) releaseConnection(connection, bucket);
        closeQuietly(socket);
      }
    }
  }

  private synchronized void releaseConnection(Thread connection, String bucket) {
    connections.remove(connection);
    connectionsPerBucket.computeIfPresent(bucket, (_, count) -> count == 1 ? null : count - 1);
  }

  private void rejectConnection(
      Socket socket, String remoteAddress, String exceeded, String bucket, int appliedLimit) {
    var deadline =
        REJECTION_TIMER.schedule(
            () -> closeQuietly(socket), rejectionDeadline.toNanos(), TimeUnit.NANOSECONDS);
    try {
      SecurityEventLog.connectionLimit(remoteAddress, exceeded, bucket, appliedLimit);
      socket
          .getOutputStream()
          .write("ERROR :Too many connections\r\n".getBytes(StandardCharsets.UTF_8));
      socket.getOutputStream().flush();
    } catch (IOException failure) {
      LOG.debug("Unable to send connection limit error to {}", remoteAddress, failure);
    } finally {
      deadline.cancel(false);
      closeQuietly(socket);
    }
  }

  /** Allows listener accepts when the application starts again. */
  public synchronized void startAccepting() {
    accepting = true;
  }

  /** Rejects late accepts before shutdown starts draining owned connections. */
  public synchronized void stopAccepting() {
    accepting = false;
  }

  /**
   * Closes accepted and rejected sockets and waits for connection, rejection, liveness and writer
   * work to finish. Rejects late accepts even if a listener failed to stop. A timeout leaves
   * outstanding connections tracked so a subsequent call can wait again.
   */
  public void closeConnections(Duration timeout) throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    stopAccepting();
    connections.values().forEach(ConnectionHandler::closeQuietly);
    rejections.values().forEach(ConnectionHandler::closeQuietly);
    awaitConnections(connections, deadline);
    awaitConnections(rejections, deadline);
  }

  private static void awaitConnections(Map<Thread, Socket> owned, long deadline)
      throws IOException {
    for (Thread connection : owned.keySet()) {
      try {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !connection.join(Duration.ofNanos(remaining))) {
          throw new IOException("Timed out waiting for " + owned.size() + " connections to stop");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Interrupted waiting for connections to stop", e);
      }
    }
  }

  private void handleConnection(
      Socket socket,
      String connectionId,
      int sendqLimit,
      Duration registrationTimeout,
      Instant acceptedAt) {
    acceptedSocketCustomizer.accept(socket);
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
      writer = new SessionWriter(session, socket.getOutputStream(), tagRenderer, sendqLimit);
    } catch (IOException e) {
      closeQuietly(socket);
      return;
    }
    session.attachWriter(writer);
    AtomicReference<Thread> overflowCleanup = new AtomicReference<>();
    writer.setOnSendqOverflow(
        () -> startOverflowCleanup(session, writer, socket, connectionId, overflowCleanup));
    writer.setOnWriteFailure(() -> disconnectCleanup.cleanup(session, "Excess Flood"));

    LivenessMonitor livenessMonitor =
        new LivenessMonitor(
            session,
            disconnectCleanup,
            Duration.ofSeconds(keepAliveFrequencySeconds.get()),
            KEEP_ALIVE_TIMEOUT,
            registrationTimeout,
            acceptedAt,
            Clock.systemUTC());
    session.attachLivenessMonitor(livenessMonitor);
    Thread liveness =
        Thread.ofVirtual()
            .name("liveness-" + connectionId)
            .start(() -> runLivenessLoop(session, livenessMonitor, socket));

    try {
      readCommands(session, socket);
    } finally {
      awaitTeardown(socket, liveness, writer, overflowCleanup);
    }
  }

  /** Reads and dispatches client lines until EOF, close or I/O failure, then cleans up. */
  private void readCommands(ClientSession session, Socket socket) {
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
    }
  }

  private static void awaitTeardown(
      Socket socket,
      Thread liveness,
      SessionWriter writer,
      AtomicReference<Thread> overflowCleanup) {
    closeQuietly(socket);
    liveness.interrupt();
    try {
      liveness.join();
      writer.awaitTermination();
      Thread cleanup = overflowCleanup.get();
      if (cleanup != null) cleanup.join();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }

  /** Hands an overflow off to its own thread so the enqueueing sender never blocks on cleanup. */
  private void startOverflowCleanup(
      ClientSession session,
      SessionWriter writer,
      Socket socket,
      String connectionId,
      AtomicReference<Thread> overflowCleanup) {
    Thread cleanup =
        Thread.ofVirtual()
            .name("sendq-disconnect-" + connectionId)
            .unstarted(
                () -> {
                  ConnectionMonitorLog.sendqOverflow(
                      connectionId, writer.limitBytes(), writer.overflowQueuedBytes());
                  try {
                    disconnectCleanup.cleanup(session, "SendQ exceeded");
                  } finally {
                    // A racing trigger may own cleanup. Drain its final lines before closing this
                    // socket, without waiting for the liveness tick.
                    writer.close();
                    closeQuietly(socket);
                  }
                });
    overflowCleanup.set(cleanup);
    cleanup.start();
  }

  /**
   * Ticks {@link LivenessMonitor#checkNow()} on its own virtual thread (FR-039) — the connection's
   * main thread is blocked inside {@link #readLineBytes} waiting for socket input, so probing idle
   * connections needs a separate thread. {@link DisconnectCleanup#cleanup} only closes the writer
   * and marks the session {@code CLOSING}; it has no reference to the socket itself, so a
   * disconnect triggered from another thread could leave the main thread blocked in {@code read()}
   * waiting for input that will never arrive. Sendq cleanup closes its socket directly after the
   * final drain; this loop remains the backstop for other disconnect triggers. Whenever the session
   * becomes {@code CLOSING}, it closes the socket, unblocking the read loop's cleanup path. It also
   * wakes at the registration deadline of an unregistered session, so that deadline is not rounded
   * up to the next tick; once the session registers, the loop falls back to the tick.
   */
  static void runLivenessLoop(
      ClientSession session, LivenessMonitor livenessMonitor, Socket socket) {
    while (!session.lifecycle().isClosing()) {
      try {
        Thread.sleep(livenessMonitor.nextCheckDelay(LIVENESS_CHECK_TICK));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      if (session.lifecycle().isClosing()) {
        break;
      }
      livenessMonitor.checkNow();
    }
    // DisconnectCleanup flips the session to CLOSING before it drains the writer, so this thread
    // can get here while the final ERROR/numeric is still queued. Wait for the drain (SessionWriter
    // #close is safe to call twice) or the client sees EOF instead of that line (#38).
    @SuppressWarnings("PMD.CloseResource")
    SessionWriter writer = session.writer();
    if (writer != null) {
      writer.close();
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
