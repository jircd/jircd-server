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

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class Story2CapabilityListTest {

  @Test
  void capLsReturnsExactlyTheThreeStory2CapabilitiesAndCoreCapNotify() throws Exception {
    try (TestServer server = TestServer.start(TestServer.ALL_CAPABILITIES_ENABLED_YAML);
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {

      client.send("CAP LS 302");
      String response = client.readUntil("CAP * LS", Duration.ofSeconds(5));

      String capsSection = response.substring(response.indexOf("LS :") + "LS :".length());
      List<String> offered = List.of(capsSection.trim().split(" "));

      assertThat(offered)
          .containsExactlyInAnyOrder("message-tags", "server-time", "echo-message", "cap-notify");
    }
  }
}
