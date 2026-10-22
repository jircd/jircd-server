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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import net.jircd.core.config.ConfigurationException;
import net.jircd.server.JircdServerApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Story4LengthLimitConfigTest {

  @Test
  void configuredLengthLimitsAreEnforcedInsteadOfDefaults() throws Exception {
    try (TestServer server =
            TestServer.start(
                """
                nicknameMaxLength: 5
                channelNameMaxLength: 10
                topicMaxLength: 20
                """);
        RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      alice.send("NICK abcdef"); // 6 chars > 5
      assertThat(alice.readUntil("432", Duration.ofSeconds(5))).contains("432");

      alice.registerAndAwaitWelcome("abcde", "alice"); // exactly 5 chars — at the limit
      String isupport = alice.readUntil("NICKLEN=5", Duration.ofSeconds(5));
      assertThat(isupport).contains("NICKLEN=5").contains("CHANNELLEN=10").contains("TOPICLEN=20");

      alice.send("JOIN #0123456789"); // "#" + 10 chars = 11 > 10
      assertThat(alice.readUntil("476", Duration.ofSeconds(5))).contains("476");

      alice.send("JOIN #lobby"); // well within the limit
      alice.readUntil("353", Duration.ofSeconds(5));

      alice.send("TOPIC #lobby :" + "x".repeat(21)); // 21 > 20
      assertThat(alice.readUntil("417", Duration.ofSeconds(5))).contains("417");
    }
  }

  @Test
  void nonPositiveLengthValueRejectedAtStartup() throws Exception {
    assertThatThrownBy(() -> startWith("nicknameMaxLength: 0"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("nicknameMaxLength");
  }

  @Test
  void nonIntegerLengthValueRejectedAtStartup() throws Exception {
    assertThatThrownBy(() -> startWith("channelNameMaxLength: \"abc\""))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("channelNameMaxLength");
  }

  @Test
  void overCeilingLengthValueRejectedAtStartup() throws Exception {
    assertThatThrownBy(() -> startWith("topicMaxLength: 401"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("topicMaxLength");
  }

  @Test
  void coupledLengthBudgetsAreRejectedAtStartup() {
    assertThatThrownBy(() -> startWith("nicknameMaxLength: 217\nchannelNameMaxLength: 50"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("353=515");
  }

  @ParameterizedTest
  @ValueSource(strings = {"JOIN", "SAJOIN"})
  void channelAdmissionChecksUtf8BytesWithoutChangingIsupport(String command) throws Exception {
    try (TestServer server =
            TestServer.start(TestServer.adminEnabledYaml() + "channelNameMaxLength: 10\n");
        RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      root.registerAndAwaitWelcome("root", "root");
      assertThat(root.readUntil("CHANNELLEN=10", Duration.ofSeconds(5))).contains("CHANNELLEN=10");
      root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
      root.readUntil("381", Duration.ofSeconds(5));
      root.send(command + " #界界界a");
      assertThat(root.readUntil("476", Duration.ofSeconds(5))).contains("#界界界a");
      root.send(command + " #界界界");
      assertThat(root.readUntil("353", Duration.ofSeconds(5))).contains("#界界界");
      assertThat(root.readUntil("366", Duration.ofSeconds(5))).contains("#界界界");
    }
  }

  private static void startWith(String extraYaml) throws Exception {
    Path configPath = Files.createTempFile("jircd-test-", ".yaml");
    Files.writeString(configPath, TestServer.baseYaml() + extraYaml + "\n");
    new JircdServerApplication(configPath);
  }
}
