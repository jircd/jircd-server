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

/** Repeated SAJOIN still broadcasts JOIN and sends fresh NAMES. */
class SajoinRepeatTest {
  @Test
  void alreadyJoinedAdminBroadcastsAgain() throws Exception {
    try (TestServer server = TestServer.start(TestServer.adminEnabledYaml());
        RawIrcClient admin = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient peer = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      admin.registerAndAwaitWelcome("root", "root");
      peer.registerAndAwaitWelcome("peer", "peer");
      admin.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
      admin.readUntil("381", Duration.ofSeconds(5));
      peer.send("JOIN #lobby");
      peer.readUntil("366", Duration.ofSeconds(5));
      admin.send("SAJOIN #lobby");
      admin.readUntil("366", Duration.ofSeconds(5));
      assertThat(peer.readUntil("JOIN #lobby", Duration.ofSeconds(5))).startsWith(":root!");

      admin.send("SAJOIN #lobby");
      List<String> replies = admin.readThroughPong(Duration.ofSeconds(5));
      assertThat(replies.stream().filter(l -> l.contains(" JOIN #lobby")))
          .singleElement()
          .asString()
          .startsWith(":root!");
      assertThat(replies.stream().filter(l -> l.contains(" 353 "))).hasSize(1);
      assertThat(replies.stream().filter(l -> l.contains(" 366 "))).hasSize(1);
      assertThat(
              peer.readThroughPong(Duration.ofSeconds(5)).stream()
                  .filter(l -> l.contains(" JOIN #lobby")))
          .singleElement()
          .asString()
          .startsWith(":root!");
    }
  }
}
