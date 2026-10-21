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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ServerConfigurationTest {

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void listenerRejectsNonpositiveBacklog(int backlog) {
    assertThatThrownBy(
            () ->
                new ServerConfiguration.Listener(
                    6667, false, null, null, null, null, null, backlog))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("backlog must be a positive integer, got: " + backlog);
  }

  @ParameterizedTest
  @CsvSource({"0,1,64", "-1,1,64", "1,0,64", "1,-1,64", "1,1,0", "1,1,129"})
  void connectionLimitsRejectInvalidDirectConstruction(int total, int perIp, int prefix) {
    assertThatThrownBy(
            () -> new ServerConfiguration.ConnectionLimits(total, perIp, prefix, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void connectionLimitsRequireOverrides() {
    assertThatThrownBy(() -> new ServerConfiguration.ConnectionLimits(1, 1, 64, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("overrides");
  }
}
