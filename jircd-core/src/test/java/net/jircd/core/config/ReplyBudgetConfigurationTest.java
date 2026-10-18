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
        .hasMessageContaining("353=514")
        .hasMessageContaining("budget=48")
        .hasMessageContaining("512");
  }

  @Test
  void checks366IndependentlyAtItsBoundary() throws Exception {
    String server = "s".repeat(73) + ".";
    assertThat(
            load("serverName: " + server + "\nnicknameMaxLength: 9\nchannelNameMaxLength: 400\n")
                .channelNameMaxLength())
        .isEqualTo(400);
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
    assertThat(
            load("serverName: test.jircd.local\nnicknameMaxLength: 216\nchannelNameMaxLength: 50\n")
                .nicknameMaxLength())
        .isEqualTo(216);
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
    assertThat(load("{}").serverName()).contains(".");
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
