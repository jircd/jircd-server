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
package net.jircd.core.config;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

/**
 * The administrator-controlled settings loaded at startup and re-applied on reload (data-model.md
 * "ServerConfiguration", Aggregate Root).
 */
public record ServerConfiguration(
    Map<String, Boolean> capabilityStates,
    Map<String, Boolean> serverExtensionStates,
    List<Listener> listeners,
    RateLimit rateLimit,
    List<AdministratorCredential> administratorCredentials,
    String serverName,
    int nicknameMaxLength,
    int channelNameMaxLength,
    int topicMaxLength,
    boolean whoMaskEnabled,
    int maxModesPerCommand,
    int operFailureThreshold,
    int whowasHistorySize,
    int keepAliveFrequencySeconds,
    int sendqLimitBytes) {

  /**
   * {@code certPath}/{@code keyPath} (a PEM certificate/chain and its PEM private key) and {@code
   * keystorePath}/{@code keystorePassword} (a PKCS12 keystore) are two mutually exclusive ways to
   * supply a certificate for a {@code tls: true} listener (004-fix-tls-certificate FR-001, FR-002,
   * FR-005) — at most one form may be set, and a {@code tls: true} listener MUST have exactly one
   * of them set (validated at load time, {@code ConfigurationLoader}). Ignored for a {@code tls:
   * false} listener.
   *
   * <p>{@code bindAddress} is the one local address the listener binds; {@code null} binds the
   * wildcard address (every local address). {@code backlog} is the requested positive accept queue
   * capacity, capped by the operating system; changes take effect after a server restart.
   */
  public record Listener(
      int port,
      boolean tls,
      InetAddress bindAddress,
      String certPath,
      String keyPath,
      String keystorePath,
      String keystorePassword,
      int backlog) {

    public Listener(
        int port,
        boolean tls,
        InetAddress bindAddress,
        String certPath,
        String keyPath,
        String keystorePath,
        String keystorePassword) {
      this(
          port,
          tls,
          bindAddress,
          certPath,
          keyPath,
          keystorePath,
          keystorePassword,
          DEFAULT_LISTENER_BACKLOG);
    }

    public Listener(int port, boolean tls) {
      this(port, tls, null, null, null, null, null);
    }

    /** Whether this listener accepts on every local address, explicitly or by omission. */
    public boolean bindsWildcard() {
      return bindAddress == null || bindAddress.isAnyLocalAddress();
    }
  }

  public record RateLimit(int bucketSize, double refillRatePerSecond) {}

  public record AdministratorCredential(String username, String hashedPassword) {}

  /** Default requested accept queue capacity for each plaintext or TLS listener. */
  public static final int DEFAULT_LISTENER_BACKLOG = 1024;

  /** Default outbound queue budget per connection, in bytes. */
  public static final int DEFAULT_SENDQ_LIMIT_BYTES = 524288;

  /** Minimum outbound queue budget, large enough for one maximum-size protocol line. */
  public static final int MIN_SENDQ_LIMIT_BYTES = 16384;

  /** Maximum administrator-configurable outbound queue budget per connection, in bytes. */
  public static final int SENDQ_LIMIT_CEILING_BYTES = 2097152;

  public static final int DEFAULT_NICKNAME_MAX_LENGTH = 9;
  public static final int DEFAULT_CHANNEL_NAME_MAX_LENGTH = 50;
  public static final int DEFAULT_TOPIC_MAX_LENGTH = 390;
  public static final int DEFAULT_MAX_MODES_PER_COMMAND = 6;
  public static final int DEFAULT_OPER_FAILURE_THRESHOLD = 5;
  public static final int LENGTH_CEILING = 400;
  public static final int MAX_MODES_CEILING = 20;
  public static final int OPER_FAILURE_THRESHOLD_CEILING = 20;

  /** 002-extended-irc-commands contracts/server-configuration-extensions.md. */
  public static final int DEFAULT_WHOWAS_HISTORY_SIZE = 100;

  public static final int WHOWAS_HISTORY_SIZE_CEILING = 10_000;

  /**
   * The server-initiated keep-alive's idle interval (009-connection-monitoring-log) — how long a
   * connection may sit idle before the server probes it with a PING. Previously a hardcoded,
   * deliberately-non-configurable 30-second constant in {@code ConnectionHandler}.
   */
  public static final int DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS = 120;

  public static final int KEEP_ALIVE_FREQUENCY_CEILING_SECONDS = 3600;

  /** The single source of truth for {@link RateLimit}'s default — never redefine this elsewhere. */
  public static final int DEFAULT_RATE_LIMIT_BUCKET_SIZE = 20;

  public static final double DEFAULT_RATE_LIMIT_REFILL_PER_SECOND = 10.0;
}
