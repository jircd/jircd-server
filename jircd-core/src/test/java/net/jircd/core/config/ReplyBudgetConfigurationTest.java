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
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplyBudgetConfigurationTest {
  @TempDir Path directory;

  private final ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of());

  private ServerConfiguration load(String yaml) throws ConfigurationException {
    return loader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void rejects353OverflowWithComputedBudgetAndKeys() {
    assertThatThrownBy(
            () ->
                load(
                    "serverName: test.jircd.local\nnicknameMaxLength: 217\nchannelNameMaxLength: 50\n"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("serverName")
        .hasMessageContaining("nicknameMaxLength")
        .hasMessageContaining("channelNameMaxLength")
        .hasMessageContaining("353=515")
        .hasMessageContaining("budget=47")
        .hasMessageContaining("512");
  }

  @Test
  void checks366IndependentlyAtItsBoundary() throws Exception {
    String server = "s".repeat(73) + ".";
    ConfigurationLoader.validateNamesBudget(server, 9, 400);
    assertThatThrownBy(
            () ->
                load(
                    "serverName: x"
                        + server
                        + "\nnicknameMaxLength: 9\nchannelNameMaxLength: 400\n"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("366=513")
        .hasMessageContaining("budget=399");
  }

  @Test
  void accepts353ExactlyAtTheConservativeBoundary() throws Exception {
    ConfigurationLoader.validateNamesBudget("test.jircd.local", 215, 51);
    assertThatThrownBy(() -> ConfigurationLoader.validateNamesBudget("test.jircd.local", 216, 51))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("353=514");
  }

  @Test
  void rejectsTheIssue85ServerNameThatOverflowsAHostmaskReply() {
    // 387-byte server name, 30-byte nicks, 50-byte channel: 333 is 568 bytes with a 21-byte cloak.
    String server = "s".repeat(383) + ".net";
    assertThatThrownBy(() -> load("serverName: " + server + "\nnicknameMaxLength: 30\n"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("serverName")
        .hasMessageContaining("nicknameMaxLength")
        .hasMessageContaining("channelNameMaxLength")
        .hasMessageContaining("27 bytes")
        .hasMessageContaining("63 bytes")
        .hasMessageContaining("333 (TOPICWHOTIME)=")
        .hasMessageContaining("352 (WHO)=");
  }

  @Test
  void hostmaskBudgetAcceptsTheWidestServerNameAndRejectsOneByteMore() throws Exception {
    // WHO carries the server name twice: 2S + 2N + C + 27 + 63 + 22 <= 512 -> S <= 166 at 9/50.
    String widest = "s".repeat(162) + ".net";
    assertThat(load("serverName: " + widest + "\n").serverName()).isEqualTo(widest);
    assertThatThrownBy(() -> load("serverName: s" + widest + "\n"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("352 (WHO)=514");
  }

  @Test
  void reloadAppliesTheHostmaskBudgetToTheRunningServerName() throws Exception {
    ServerConfiguration initial = load("serverName: " + "s".repeat(162) + ".net\n");
    Path yaml = directory.resolve("wide-nick.yaml");
    Files.writeString(yaml, "serverName: s.x\nnicknameMaxLength: 30\n");
    ConfigurationReloader reloader =
        new ConfigurationReloader(yaml, loader, new ExtensionRegistry(), initial);
    assertThatThrownBy(reloader::reload)
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("352 (WHO)=");
    assertThat(reloader.current()).isSameAs(initial);
  }

  @Test
  void measuresTheServerNameInUtf8Bytes() {
    assertThatThrownBy(() -> load("serverName: " + "界".repeat(150) + ".\n"))
        .isInstanceOf(ConfigurationException.class)
        .hasMessageContaining("serverName")
        .hasMessageContaining("451");
  }

  @Test
  void resolvesAnOmittedServerNameBeforeValidation() throws Exception {
    String hostname;
    try {
      hostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException _) {
      hostname = null;
    }
    assertThat(load("{}").serverName()).isEqualTo(ConfigurationLoader.dottedServerName(hostname));
  }

  @Test
  void dottedServerNameKeepsDottedHostnamesAndFallsBackOtherwise() {
    assertThat(ConfigurationLoader.dottedServerName("irc.example.org"))
        .isEqualTo("irc.example.org");
    assertThat(ConfigurationLoader.dottedServerName("box")).isEqualTo("box.local");
    assertThat(ConfigurationLoader.dottedServerName(null)).isEqualTo("localhost.local");
  }

  @Test
  void rejectsUnsafeReloadBeforeReplacingConfigurationOrAdvertisedLengths() throws Exception {
    ServerConfiguration initial = load("serverName: test.jircd.local\n");
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.updateConfiguredLengths(9, 50, 390, 6);
    Path yaml = directory.resolve("reload.yaml");
    Files.writeString(yaml, "serverName: test.jircd.local\nnicknameMaxLength: 400\n");
    ConfigurationReloader reloader = new ConfigurationReloader(yaml, loader, extensions, initial);
    assertThatThrownBy(reloader::reload).isInstanceOf(ConfigurationException.class);
    assertThat(reloader.current()).isSameAs(initial);
    assertThat(extensions.supportedFeatures().nicknameMaxLength()).isEqualTo(9);
  }

  @Test
  void reloadValidatesAgainstTheRunningPrefixEvenIfTheFileNamesAShorterServer() throws Exception {
    ServerConfiguration initial = load("serverName: " + "s".repeat(70) + ".\n");
    Path yaml = directory.resolve("shorter-server.yaml");
    Files.writeString(yaml, "serverName: s.x\nnicknameMaxLength: 200\nchannelNameMaxLength: 50\n");
    ConfigurationReloader reloader =
        new ConfigurationReloader(yaml, loader, new ExtensionRegistry(), initial);
    assertThatThrownBy(reloader::reload).isInstanceOf(ConfigurationException.class);
    assertThat(reloader.current()).isSameAs(initial);
  }
}
