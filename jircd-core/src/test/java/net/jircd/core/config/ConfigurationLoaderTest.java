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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigurationLoaderTest {

  private static final Set<String> CAPABILITY_IDS =
      Set.of("message-tags", "server-time", "echo-message");
  private static final Set<String> SERVER_EXTENSION_IDS = Set.of("cloak", "admin");

  private ConfigurationLoader loader() {
    return new ConfigurationLoader(CAPABILITY_IDS, SERVER_EXTENSION_IDS);
  }

  private ServerConfiguration load(String yaml) throws ConfigurationException {
    return loader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @ParameterizedTest
  @MethodSource("malformedConfigurations")
  void rejectsMalformedConfigurationWithKey(String yaml, String key) {
    assertThatThrownBy(() -> load(yaml))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining(key);
  }

  @ParameterizedTest
  @MethodSource("secretConfigurations")
  void validationMessagesDoNotExposeSecrets(String yaml, String key, String expectedType) {
    assertThatThrownBy(() -> load(yaml))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining(key)
        .hasMessageContaining(expectedType)
        .hasMessageNotContaining("987654321");
  }

  static Stream<Arguments> secretConfigurations() {
    return Stream.of(
        Arguments.of(
            "listeners: [{port: 6667, keystorePassword: 987654321}]",
            "listeners[0].keystorePassword",
            "string"),
        Arguments.of(
            "administratorCredentials: [{username: admin, hashedPassword: 987654321}]",
            "administratorCredentials[0].hashedPassword",
            "string"),
        Arguments.of("listeners: {keystorePassword: 987654321}", "listeners", "list"),
        Arguments.of(
            "listeners: !!set { ? {hashedPassword: 987654321} : null }", "listeners", "list"),
        Arguments.of("!!set { ? {hashedPassword: 987654321} : null }", "root", "mapping"),
        Arguments.of(
            "listeners: &loop {self: *loop, keystorePassword: 987654321}", "listeners", "list"),
        Arguments.of(
            "[{administratorCredentials: [{hashedPassword: 987654321}]}]", "root", "mapping"),
        Arguments.of("listeners: [{port: 6667, keystorePassword: 987654321]", "root", "YAML"));
  }

  @ParameterizedTest
  @MethodSource("nonSecretConfigurations")
  void nonSecretValidationMessagesIncludeValues(String yaml, String key, String value) {
    assertThatThrownBy(() -> load(yaml))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining(key)
        .hasMessageContaining("got: " + value);
  }

  static Stream<Arguments> nonSecretConfigurations() {
    return Stream.of(
        Arguments.of("rateLimit: {bucketSize: -7}", "rateLimit.bucketSize", "-7"),
        Arguments.of(
            "rateLimit: {refillRatePerSecond: nope}", "rateLimit.refillRatePerSecond", "nope"),
        Arguments.of("keepAliveFrequencySeconds: 3601", "keepAliveFrequencySeconds", "3601"),
        Arguments.of("listeners: [{port: 65536}]", "listeners[0].port", "65536"),
        Arguments.of(
            "listeners: [{port: 6667, bindAddress: localhost}]",
            "listeners[0].bindAddress",
            "localhost"),
        Arguments.of("capabilities: {message-tags: nope}", "capabilities.message-tags", "nope"),
        Arguments.of("serverName: 42", "serverName", "42"),
        Arguments.of("whoMaskEnabled: nope", "whoMaskEnabled", "nope"),
        Arguments.of("listeners: nope", "listeners", "nope"),
        Arguments.of("rateLimit: nope", "rateLimit", "nope"),
        Arguments.of("{42: value}", "root", "42"));
  }

  @Test
  void syntaxErrorsIncludeLocationWithoutSourceText() {
    assertThatThrownBy(
            () ->
                load(
                    "serverName: irc.example.net\nlisteners:\n  - keystorePassword: [987654321}\n"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("invalid YAML at line 3, column 33")
        .hasMessageNotContaining("987654321")
        .hasMessageNotContaining("keystorePassword");
  }

  static Stream<Arguments> malformedConfigurations() {
    Stream.Builder<Arguments> cases = Stream.builder();
    for (String root : new String[] {"[]", "42", "true", "hello", "{42: value}"}) {
      cases.add(Arguments.of(root, "root"));
    }
    for (String section : new String[] {"listeners", "administratorCredentials"}) {
      for (String shape : new String[] {"{}", "nope", "42", "[null]", "[nope]", "[{42: value}]"}) {
        cases.add(Arguments.of(section + ": " + shape, section));
      }
    }
    for (String section : new String[] {"rateLimit", "capabilities", "server-extensions"}) {
      for (String shape : new String[] {"[]", "nope", "42", "{42: value}"}) {
        cases.add(Arguments.of(section + ": " + shape, section));
      }
    }
    for (String value : new String[] {"[]", "{}", "42", "true"}) {
      cases.add(Arguments.of("serverName: " + value, "serverName"));
      cases.add(
          Arguments.of(
              "administratorCredentials: [{username: " + value + ", hashedPassword: '$2b$hash'}]",
              "administratorCredentials[0].username"));
      cases.add(
          Arguments.of(
              "administratorCredentials: [{username: admin, hashedPassword: " + value + "}]",
              "administratorCredentials[0].hashedPassword"));
      for (String field :
          new String[] {"certPath", "keyPath", "keystorePath", "keystorePassword"}) {
        cases.add(
            Arguments.of(
                "listeners: [{port: 6667, " + field + ": " + value + "}]",
                "listeners[0]." + field));
      }
    }
    for (String value : new String[] {"[]", "{}", "nope", "1", "null", "'true'"}) {
      cases.add(Arguments.of("whoMaskEnabled: " + value, "whoMaskEnabled"));
      cases.add(Arguments.of("listeners: [{port: 6667, tls: " + value + "}]", "listeners[0].tls"));
    }
    for (String value :
        new String[] {"nope", "null", "[]", "{}", "true", ".nan", ".inf", "-.inf", "0", "-1"}) {
      cases.add(
          Arguments.of(
              "rateLimit: {refillRatePerSecond: " + value + "}", "rateLimit.refillRatePerSecond"));
    }
    for (String value :
        new String[] {
          "-1", "65536", "1.5", "'6667'", "null", "[]", "true", "999999999999999999999"
        }) {
      cases.add(Arguments.of("listeners: [{port: " + value + "}]", "listeners[0].port"));
    }
    for (String tls : new String[] {"false", "true"}) {
      cases.add(
          Arguments.of(
              "listeners: [{port: 6667}, {port: 6667, tls: " + tls + "}]", "listeners[1].port"));
    }
    for (String value :
        new String[] {
          "localhost", "irc.example.net", "'256.0.0.1'", "''", "'::1::'", "42", "true", "[]", "{}"
        }) {
      cases.add(
          Arguments.of(
              "listeners: [{port: 6667, bindAddress: " + value + "}]", "listeners[0].bindAddress"));
    }
    // Same port: equal addresses, or the wildcard on either side (omitted, 0.0.0.0 or ::).
    for (String[] addresses :
        new String[][] {
          {"127.0.0.1", "127.0.0.1"},
          {"'::1'", "'::1'"},
          {"127.0.0.1", "'::ffff:127.0.0.1'"},
          {null, "127.0.0.1"},
          {"127.0.0.1", null},
          {"0.0.0.0", "127.0.0.1"},
          {"'::1'", "'::'"}
        }) {
      cases.add(
          Arguments.of(
              "listeners: ["
                  + listenerOn(6667, addresses[0])
                  + ", "
                  + listenerOn(6667, addresses[1])
                  + "]",
              "listeners[1].port"));
    }
    cases.add(
        Arguments.of("administratorCredentials: [{}]", "administratorCredentials[0].username"));
    cases.add(
        Arguments.of(
            "administratorCredentials: [{username: admin}]",
            "administratorCredentials[0].hashedPassword"));
    cases.add(Arguments.of("capabilities: {message-tags: []}", "capabilities.message-tags"));
    cases.add(Arguments.of("server-extensions: {admin: true}", "server-extensions.admin"));
    cases.add(Arguments.of("listeners: [", "root"));
    return cases.build();
  }

  @Test
  void acceptsListenerPortBoundariesAndFiniteRates() throws Exception {
    ServerConfiguration config =
        load(
            "listeners: [{port: 0}, {port: 0}, {port: 1}, {port: 65535}]\n"
                + "rateLimit: {bucketSize: 1, refillRatePerSecond: 0.5}");
    assertThat(config.listeners())
        .extracting(ServerConfiguration.Listener::port)
        .containsExactly(0, 0, 1, 65535);
    assertThat(config.rateLimit().refillRatePerSecond()).isEqualTo(0.5);
  }

  @Test
  void bindAddressIsAnOptionalLiteralIpAddress() throws Exception {
    ServerConfiguration config =
        load(
            "listeners: [{port: 6667}, {port: 6668, bindAddress: 127.0.0.1},"
                + " {port: 6669, bindAddress: '::1'}]");
    assertThat(config.listeners())
        .extracting(ServerConfiguration.Listener::bindAddress)
        .containsExactly(null, InetAddress.ofLiteral("127.0.0.1"), InetAddress.ofLiteral("::1"));
  }

  @Test
  void samePortOnDifferentSpecificAddressesDoesNotConflict() throws Exception {
    ServerConfiguration config =
        load(
            "listeners: ["
                + listenerOn(6667, "127.0.0.1")
                + ", "
                + listenerOn(6667, "'::1'")
                + ", "
                + listenerOn(0, null)
                + ", "
                + listenerOn(0, "127.0.0.1")
                + ", "
                + listenerOn(0, "127.0.0.1")
                + "]");
    assertThat(config.listeners())
        .extracting(ServerConfiguration.Listener::port)
        .containsExactly(6667, 6667, 0, 0, 0);
  }

  private static String listenerOn(int port, String bindAddress) {
    return "{port: " + port + (bindAddress == null ? "" : ", bindAddress: " + bindAddress) + "}";
  }

  @Test
  void loadsDefaultsWhenEmpty() throws Exception {
    ServerConfiguration config = load("{}");
    assertThat(config.nicknameMaxLength()).isEqualTo(9);
    assertThat(config.channelNameMaxLength()).isEqualTo(50);
    assertThat(config.topicMaxLength()).isEqualTo(390);
    assertThat(config.maxModesPerCommand()).isEqualTo(6);
    assertThat(config.operFailureThreshold()).isEqualTo(5);
    assertThat(config.whoMaskEnabled()).isTrue();
  }

  @Test
  void unknownExtensionIdIsRejected() {
    assertThatThrownBy(() -> load("capabilities:\n  nonexistent: enabled\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void sectionKindMismatchIsRejected() {
    assertThatThrownBy(() -> load("capabilities:\n  cloak: enabled\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("server-extensions:\n  message-tags: enabled\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void malformedListenerIsRejected() {
    assertThatThrownBy(() -> load("listeners:\n  - tls: false\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void malformedRateLimitIsRejected() {
    assertThatThrownBy(() -> load("rateLimit:\n  bucketSize: -1\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void dotFreeServerNameIsRejected() {
    assertThatThrownBy(() -> load("serverName: localhost\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void outOfRangeLengthValuesAreRejected() {
    assertThatThrownBy(() -> load("nicknameMaxLength: 0\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("channelNameMaxLength: 401\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("topicMaxLength: -5\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void outOfRangeOperFailureThresholdIsRejected() {
    assertThatThrownBy(() -> load("operFailureThreshold: 0\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("operFailureThreshold: 21\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void validKeepAliveFrequencyParsesCleanly() throws Exception {
    ServerConfiguration config = load("keepAliveFrequencySeconds: 60\n");
    assertThat(config.keepAliveFrequencySeconds()).isEqualTo(60);
  }

  @Test
  void keepAliveFrequencyDefaultsWhenUnset() throws Exception {
    ServerConfiguration config = load("");
    assertThat(config.keepAliveFrequencySeconds())
        .isEqualTo(ServerConfiguration.DEFAULT_KEEP_ALIVE_FREQUENCY_SECONDS);
  }

  @Test
  void outOfRangeKeepAliveFrequencyIsRejected() {
    assertThatThrownBy(() -> load("keepAliveFrequencySeconds: 0\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("keepAliveFrequencySeconds: -5\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(() -> load("keepAliveFrequencySeconds: 3601\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void plainTextCredentialIsRejected() {
    assertThatThrownBy(
            () ->
                load(
                    "administratorCredentials:\n  - username: root\n    hashedPassword: hunter2\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void unsupportedArgon2VariantIsRejected() {
    assertThatThrownBy(
            () ->
                load(
                    "administratorCredentials:\n"
                        + "  - username: root\n"
                        + "    hashedPassword: \"$argon2i$v=19$m=65536,t=3,p=4$c2FsdA$aGFzaA\"\n"))
        .isInstanceOf(ConfigurationException.class);
    assertThatThrownBy(
            () ->
                load(
                    "administratorCredentials:\n"
                        + "  - username: root\n"
                        + "    hashedPassword: \"$argon2d$v=19$m=65536,t=3,p=4$c2FsdA$aGFzaA\"\n"))
        .isInstanceOf(ConfigurationException.class);
  }

  @Test
  void argon2idCredentialParsesCleanly() throws Exception {
    ServerConfiguration config =
        load(
            "administratorCredentials:\n"
                + "  - username: root\n"
                + "    hashedPassword: \"$argon2id$v=19$m=65536,t=3,p=4$c2FsdA$aGFzaA\"\n");
    assertThat(config.administratorCredentials()).hasSize(1);
  }

  @Test
  void validConfigurationParsesCleanly() throws Exception {
    String yaml =
        """
                serverName: irc.example.net
                nicknameMaxLength: 5
                listeners:
                  - port: 6667
                    tls: false
                capabilities:
                  message-tags: enabled
                server-extensions:
                  admin: enabled
                administratorCredentials:
                  - username: root-admin
                    hashedPassword: "$2b$10$abcdefghijklmnopqrstuv"
                """;
    ServerConfiguration config = load(yaml);
    assertThat(config.serverName()).isEqualTo("irc.example.net");
    assertThat(config.nicknameMaxLength()).isEqualTo(5);
    assertThat(config.listeners()).containsExactly(new ServerConfiguration.Listener(6667, false));
    assertThat(config.capabilityStates()).containsEntry("message-tags", true);
    assertThat(config.serverExtensionStates()).containsEntry("admin", true);
    assertThat(config.administratorCredentials()).hasSize(1);
  }
}
