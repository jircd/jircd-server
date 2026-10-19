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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jircd.core.session.ConnectionMonitorLog;
import org.junit.jupiter.api.Test;

/**
 * 009-connection-monitoring-log: connecting and disconnecting a client produces a connect-event and
 * a disconnect-event through {@link ConnectionMonitorLog} — a facility distinct from {@code
 * SecurityEventLog} — sharing the same, non-sequential connection token.
 */
class ConnectionMonitorLogTest {

  private static final Pattern CONNECTION_TOKEN = Pattern.compile("connection=(\\S+)");

  static String extractToken(String logLine) {
    Matcher matcher = CONNECTION_TOKEN.matcher(logLine);
    if (!matcher.find()) {
      throw new AssertionError("No connection= token found in: " + logLine);
    }
    return matcher.group(1);
  }

  @Test
  void connectAndDisconnectProduceMatchingLogEntries() throws Exception {
    try (LogCapture capture = new LogCapture(ConnectionMonitorLog.class);
        TestServer server = TestServer.start()) {

      String connectedMessage;
      try (RawIrcClient alice = RawIrcClient.connectPlaintext(server.plaintextPort())) {
        alice.registerAndAwaitWelcome("alice", "alice");

        connectedMessage =
            capture.awaitMessage(
                message -> message.startsWith("connection-event=connected"), Duration.ofSeconds(5));
      }

      String disconnectedMessage =
          capture.awaitMessage(
              message -> message.startsWith("connection-event=disconnected"),
              Duration.ofSeconds(5));

      String connectedToken = extractToken(connectedMessage);
      String disconnectedToken = extractToken(disconnectedMessage);
      assertThat(connectedToken).isEqualTo(disconnectedToken);
      assertThat(connectedToken).doesNotMatch("^c\\d+$");
      assertThat(connectedMessage).contains("remoteAddress=");
      assertThat(disconnectedMessage).contains("durationMs=").contains("reason=");
    }
  }

  @Test
  void sendqOverflowLineFollowsContractAndTotalGrowsByOnePerCall() throws Exception {
    Pattern line =
        Pattern.compile(
            "connection-event=sendq-overflow connection=conn-x limitBytes=16384 queuedBytes=(\\d+)"
                + " overflowTotal=(\\d+)");
    try (LogCapture capture = new LogCapture(ConnectionMonitorLog.class)) {
      ConnectionMonitorLog.sendqOverflow("conn-x", 16384, 16900L);
      ConnectionMonitorLog.sendqOverflow("conn-x", 16384, 17000L);
      var entries =
          capture.messages().stream()
              .filter(m -> m.startsWith("connection-event=sendq-overflow"))
              .toList();
      assertThat(entries).hasSize(2);
      Matcher first = line.matcher(entries.get(0));
      Matcher second = line.matcher(entries.get(1));
      assertThat(first.matches()).as(entries.get(0)).isTrue();
      assertThat(second.matches()).as(entries.get(1)).isTrue();
      assertThat(first.group(1)).isEqualTo("16900");
      assertThat(Long.parseLong(second.group(2))).isEqualTo(Long.parseLong(first.group(2)) + 1);
    }
  }
}
