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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Loads and validates the YAML Server Configuration file (contracts/server-configuration.md),
 * rejecting any invalid value with a specific, actionable error naming the offending key (FR-012,
 * SC-008) — never a partially-applied result.
 */
public final class ConfigurationLoader {

  private static final String WHO_MASK_ENABLED_KEY = "whoMaskEnabled";

  private static final Set<String> BASE_64_HASH_PREFIXES =
      Set.of("$2a$", "$2b$", "$2y$", "$argon2id$");

  private final Set<String> knownCapabilityIds;
  private final Set<String> knownServerExtensionIds;

  public ConfigurationLoader(Set<String> knownCapabilityIds, Set<String> knownServerExtensionIds) {
    this.knownCapabilityIds = knownCapabilityIds;
    this.knownServerExtensionIds = knownServerExtensionIds;
  }

  public ServerConfiguration load(InputStream yamlInput) throws ConfigurationException {
    Object rootObj;
    try {
      rootObj = new Yaml().load(yamlInput);
    } catch (YAMLException e) {
      String location = "";
      if (e instanceof MarkedYAMLException marked && marked.getProblemMark() != null) {
        var mark = marked.getProblemMark();
        location = " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
      }
      throw new ConfigurationException("root contains invalid YAML" + location);
    }
    Map<String, Object> root = rootObj == null ? Map.of() : asMap(rootObj, "root");

    String serverName = asString(root.get("serverName"), "serverName");
    if (serverName != null && !serverName.contains(".")) {
      throw new ConfigurationException("serverName must contain a '.': " + serverName);
    }

    int nicknameMaxLength =
        positiveIntWithinCeiling(
            root,
            "nicknameMaxLength",
            ServerConfiguration.DEFAULT_NICKNAME_MAX_LENGTH,
            ServerConfiguration.LENGTH_CEILING);
    int channelNameMaxLength =
        positiveIntWithinCeiling(
            root,
            "channelNameMaxLength",
            ServerConfiguration.DEFAULT_CHANNEL_NAME_MAX_LENGTH,
            ServerConfiguration.LENGTH_CEILING);
    int topicMaxLength =
        positiveIntWithinCeiling(
            root,
            "topicMaxLength",
            ServerConfiguration.DEFAULT_TOPIC_MAX_LENGTH,
            ServerConfiguration.LENGTH_CEILING);
    int maxModesPerCommand =
        positiveIntWithinCeiling(
            root,
            "maxModesPerCommand",
            ServerConfiguration.DEFAULT_MAX_MODES_PER_COMMAND,
            ServerConfiguration.MAX_MODES_CEILING);
    int operFailureThreshold =
        positiveIntWithinCeiling(
            root,
            "operFailureThreshold",
            ServerConfiguration.DEFAULT_OPER_FAILURE_THRESHOLD,
            ServerConfiguration.OPER_FAILURE_THRESHOLD_CEILING);
    int whowasHistorySize =
        positiveIntWithinCeiling(
            root,
            "whowasHistorySize",
            ServerConfiguration.DEFAULT_WHOWAS_HISTORY_SIZE,
            ServerConfiguration.WHOWAS_HISTORY_SIZE_CEILING);
    int keepAliveFrequencySeconds =
        positiveIntWithinCeiling(
            root,
            "keepAliveFrequencySeconds",
            ServerConfiguration.DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS,
            ServerConfiguration.KEEP_ALIVE_FREQUENCY_CEILING_SECONDS);

    boolean whoMaskEnabled =
        !root.containsKey(WHO_MASK_ENABLED_KEY)
            || asBoolean(root.get(WHO_MASK_ENABLED_KEY), WHO_MASK_ENABLED_KEY);

    List<ServerConfiguration.Listener> listeners = parseListeners(root.get("listeners"));

    ServerConfiguration.RateLimit rateLimit = parseRateLimit(root.get("rateLimit"));

    Map<String, Boolean> capabilityStates =
        parseExtensionStates(
            root.get("capabilities"), knownCapabilityIds, knownServerExtensionIds, "capabilities");
    Map<String, Boolean> serverExtensionStates =
        parseExtensionStates(
            root.get("server-extensions"),
            knownServerExtensionIds,
            knownCapabilityIds,
            "server-extensions");

    List<ServerConfiguration.AdministratorCredential> credentials =
        parseCredentials(root.get("administratorCredentials"));

    return new ServerConfiguration(
        capabilityStates,
        serverExtensionStates,
        listeners,
        rateLimit,
        credentials,
        serverName,
        nicknameMaxLength,
        channelNameMaxLength,
        topicMaxLength,
        whoMaskEnabled,
        maxModesPerCommand,
        operFailureThreshold,
        whowasHistorySize,
        keepAliveFrequencySeconds);
  }

  private static int positiveIntWithinCeiling(
      Map<String, Object> root, String key, int defaultValue, int ceiling)
      throws ConfigurationException {
    if (!root.containsKey(key)) {
      return defaultValue;
    }
    Object raw = root.get(key);
    if (!(raw instanceof Integer value) || value <= 0 || value > ceiling) {
      throw new ConfigurationException(
          key
              + " must be a positive integer no greater than "
              + ceiling
              + ", got: "
              + redactSecrets(raw));
    }
    return value;
  }

  private static List<ServerConfiguration.Listener> parseListeners(Object raw)
      throws ConfigurationException {
    if (raw == null) {
      return List.of();
    }
    List<ServerConfiguration.Listener> listeners = new ArrayList<>();
    for (Object entry : asList(raw, "listeners")) {
      String path = "listeners[" + listeners.size() + "]";
      Map<String, Object> map = asMap(entry, path);
      Object port = map.get("port");
      if (!(port instanceof Integer portValue) || portValue < 0 || portValue > 65535) {
        throw new ConfigurationException(
            path + ".port must be an integer from 0 to 65535, got: " + redactSecrets(port));
      }
      boolean tls = map.containsKey("tls") && asBoolean(map.get("tls"), path + ".tls");
      InetAddress bindAddress = asBindAddress(map.get("bindAddress"), path + ".bindAddress");
      String certPath = asString(map.get("certPath"), path + ".certPath");
      String keyPath = asString(map.get("keyPath"), path + ".keyPath");
      String keystorePath = asString(map.get("keystorePath"), path + ".keystorePath");
      String keystorePassword = asString(map.get("keystorePassword"), path + ".keystorePassword");

      ServerConfiguration.Listener listener =
          new ServerConfiguration.Listener(
              (Integer) port, tls, bindAddress, certPath, keyPath, keystorePath, keystorePassword);
      // Port zero asks the OS for a distinct ephemeral port on each bind.
      if (portValue != 0) {
        for (ServerConfiguration.Listener earlier : listeners) {
          if (earlier.port() == portValue && sharesAnAddress(earlier, listener)) {
            throw new ConfigurationException(
                path + ".port conflicts with another listener: " + port);
          }
        }
      }

      // Cert fields on a tls: false listener are left untouched, not validated — the same
      // precedent this method already had of only reading recognized keys and ignoring
      // anything else present in a listener's map (004-fix-tls-certificate research.md
      // "Validation rules").
      if (tls) {
        validateAndLoadCertificate(listener);
      }
      listeners.add(listener);
    }
    return listeners;
  }

  private static void validateAndLoadCertificate(ServerConfiguration.Listener listener)
      throws ConfigurationException {
    boolean hasCertPath = listener.certPath() != null;
    boolean hasKeyPath = listener.keyPath() != null;
    boolean hasKeystorePath = listener.keystorePath() != null;

    if (hasCertPath != hasKeyPath) {
      throw new ConfigurationException(
          "listener on port "
              + listener.port()
              + " requests TLS with an incomplete PEM certificate/key pair — both 'certPath'"
              + " and 'keyPath' are required together");
    }
    boolean hasPemPair = hasCertPath && hasKeyPath;
    if (hasPemPair && hasKeystorePath) {
      throw new ConfigurationException(
          "listener on port "
              + listener.port()
              + " requests TLS with both a PEM certificate/key pair and a 'keystorePath' —"
              + " exactly one certificate source is allowed per listener");
    }
    if (!hasPemPair && !hasKeystorePath) {
      throw new ConfigurationException(
          "listener on port "
              + listener.port()
              + " requests TLS but no certificate is configured — set 'certPath'/'keyPath' or"
              + " 'keystorePath'");
    }
    if (hasKeystorePath && listener.keystorePassword() == null) {
      throw new ConfigurationException(
          "listener on port "
              + listener.port()
              + " configures 'keystorePath' but no 'keystorePassword' — a password is required,"
              + " no default is substituted");
    }
    try {
      TlsCertificateLoader.load(listener);
    } catch (GeneralSecurityException | IOException e) {
      throw new ConfigurationException(
          "listener on port "
              + listener.port()
              + " has an invalid TLS certificate configuration: "
              + e.getMessage());
    }
  }

  private static ServerConfiguration.RateLimit parseRateLimit(Object raw)
      throws ConfigurationException {
    if (raw == null) {
      return new ServerConfiguration.RateLimit(
          ServerConfiguration.DEFAULT_RATE_LIMIT_BUCKET_SIZE,
          ServerConfiguration.DEFAULT_RATE_LIMIT_REFILL_PER_SECOND);
    }
    Map<String, Object> map = asMap(raw, "rateLimit");
    Object bucketSize =
        map.getOrDefault("bucketSize", ServerConfiguration.DEFAULT_RATE_LIMIT_BUCKET_SIZE);
    Object refillRate =
        map.getOrDefault(
            "refillRatePerSecond", ServerConfiguration.DEFAULT_RATE_LIMIT_REFILL_PER_SECOND);
    if (!(bucketSize instanceof Integer bucketSizeInt) || bucketSizeInt <= 0) {
      throw new ConfigurationException(
          "rateLimit.bucketSize must be a positive integer, got: " + redactSecrets(bucketSize));
    }
    if (!(refillRate instanceof Number number)
        || !Double.isFinite(number.doubleValue())
        || number.doubleValue() <= 0) {
      throw new ConfigurationException(
          "rateLimit.refillRatePerSecond must be finite and positive, got: "
              + redactSecrets(refillRate));
    }
    return new ServerConfiguration.RateLimit(bucketSizeInt, number.doubleValue());
  }

  private static Map<String, Boolean> parseExtensionStates(
      Object raw, Set<String> ownKnownIds, Set<String> otherKindKnownIds, String sectionName)
      throws ConfigurationException {
    Map<String, Boolean> states = new LinkedHashMap<>();
    if (raw == null) {
      return states;
    }
    Map<String, Object> map = asMap(raw, sectionName);
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      String id = entry.getKey();
      if (otherKindKnownIds.contains(id)) {
        throw new ConfigurationException(
            "'"
                + id
                + "' is not a valid entry under '"
                + sectionName
                + "' — section/kind mismatch");
      }
      if (!ownKnownIds.contains(id)) {
        throw new ConfigurationException("Unknown extension id under '" + sectionName + "': " + id);
      }
      String value = String.valueOf(entry.getValue());
      if (!value.equals("enabled") && !value.equals("disabled")) {
        throw new ConfigurationException(
            "'"
                + sectionName
                + "."
                + id
                + "' must be 'enabled' or 'disabled', got: "
                + redactSecrets(entry.getValue()));
      }
      states.put(id, value.equals("enabled"));
    }
    return states;
  }

  private static List<ServerConfiguration.AdministratorCredential> parseCredentials(Object raw)
      throws ConfigurationException {
    if (raw == null) {
      return List.of();
    }
    List<ServerConfiguration.AdministratorCredential> credentials = new ArrayList<>();
    for (Object entry : asList(raw, "administratorCredentials")) {
      String path = "administratorCredentials[" + credentials.size() + "]";
      Map<String, Object> map = asMap(entry, path);
      String username = requiredString(map.get("username"), path + ".username");
      String hashedPassword = requiredString(map.get("hashedPassword"), path + ".hashedPassword");
      if (BASE_64_HASH_PREFIXES.stream().noneMatch(hashedPassword::startsWith)) {
        throw new ConfigurationException(
            "administratorCredentials."
                + username
                + ".hashedPassword is not a recognized hash format"
                + " (plain-text or unrecognized credential storage is rejected)");
      }
      credentials.add(new ServerConfiguration.AdministratorCredential(username, hashedPassword));
    }
    return credentials;
  }

  private static boolean isSecretKey(Object key) {
    return "keystorePassword".equals(key) || "hashedPassword".equals(key);
  }

  // Malformed containers may contain secrets even when their enclosing key is not secret.
  private static Object redactSecrets(Object value) {
    return redactSecrets(value, new IdentityHashMap<>());
  }

  private static Object redactSecrets(Object value, Map<Object, Boolean> visiting) {
    if (!(value instanceof Map<?, ?>) && !(value instanceof Collection<?>)) {
      return value;
    }
    if (visiting.put(value, Boolean.TRUE) != null) {
      return "[recursive]";
    }
    try {
      if (value instanceof Map<?, ?> map) {
        Map<Object, Object> redacted = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
          redacted.put(
              redactSecrets(entry.getKey(), visiting),
              isSecretKey(entry.getKey())
                  ? "[redacted]"
                  : redactSecrets(entry.getValue(), visiting));
        }
        return redacted;
      }
      return ((Collection<?>) value).stream().map(entry -> redactSecrets(entry, visiting)).toList();
    } finally {
      visiting.remove(value);
    }
  }

  private static Map<String, Object> asMap(Object value, String path)
      throws ConfigurationException {
    if (!(value instanceof Map<?, ?> map)) {
      throw new ConfigurationException(path + " must be a mapping, got: " + redactSecrets(value));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new ConfigurationException(
            path + " must have string keys, got: " + redactSecrets(entry.getKey()));
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static List<?> asList(Object value, String path) throws ConfigurationException {
    if (!(value instanceof List<?> list)) {
      throw new ConfigurationException(path + " must be a list, got: " + redactSecrets(value));
    }
    return list;
  }

  private static String asString(Object value, String path) throws ConfigurationException {
    return value == null ? null : requiredString(value, path);
  }

  private static String requiredString(Object value, String path) throws ConfigurationException {
    if (!(value instanceof String string)) {
      throw new ConfigurationException(
          path
              + " must be a string"
              + (isSecretKey(path.substring(path.lastIndexOf('.') + 1))
                  ? ""
                  : ", got: " + redactSecrets(value)));
    }
    return string;
  }

  /**
   * A literal IPv4 or IPv6 address only: a hostname would need a DNS lookup at startup, and could
   * resolve differently from one start to the next.
   */
  private static InetAddress asBindAddress(Object value, String path)
      throws ConfigurationException {
    String literal = asString(value, path);
    if (literal == null) {
      return null;
    }
    try {
      return InetAddress.ofLiteral(literal);
    } catch (IllegalArgumentException notALiteral) {
      throw new ConfigurationException(
          path + " must be a literal IPv4 or IPv6 address, got: " + literal);
    }
  }

  /** Two listeners on one port collide on the same address, or when either takes them all. */
  private static boolean sharesAnAddress(
      ServerConfiguration.Listener first, ServerConfiguration.Listener second) {
    return first.bindsWildcard()
        || second.bindsWildcard()
        || first.bindAddress().equals(second.bindAddress());
  }

  private static boolean asBoolean(Object value, String path) throws ConfigurationException {
    if (!(value instanceof Boolean bool)) {
      throw new ConfigurationException(path + " must be a boolean, got: " + redactSecrets(value));
    }
    return bool;
  }
}
