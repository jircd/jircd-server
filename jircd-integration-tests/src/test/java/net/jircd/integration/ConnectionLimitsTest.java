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

import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.session.SecurityEventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConnectionLimitsTest {
  private static final Duration WAIT = Duration.ofSeconds(5);

  @ParameterizedTest
  @ValueSource(strings = {"maxConnections", "maxConnectionsPerIp"})
  void rejectsAcrossPlaintextAndTlsAndRehashChangesAdmission(String key) throws Exception {
    String admin = TestServer.adminEnabledYaml();
    try (LogCapture logs = new LogCapture(SecurityEventLog.class);
        TestServer server = TestServer.start(admin + key + ": 1\n");
        RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      root.registerAndAwaitWelcome("root", "root");
      root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
      root.readUntil("381", WAIT);
      assertRejected(server, false);
      assertRejected(server, true);
      assertThat(logs.messages())
          .anyMatch(
              message ->
                  message.contains("security-event=connection-limit") && message.contains(key));

      Files.writeString(server.configPath, TestServer.baseYaml() + admin + key + ": 0\n");
      root.send("REHASH");
      assertThat(root.readUntil("NOTICE", WAIT)).contains("Rehash failed");
      assertRejected(server, false);

      Files.writeString(server.configPath, TestServer.baseYaml() + admin + key + ": 2\n");
      root.send("REHASH");
      root.readUntil("382", WAIT);
      try (RawIrcClient second = RawIrcClient.connectTls(server.tlsPort())) {
        second.registerAndAwaitWelcome("second", "second");
        assertRejected(server, false);
        // Lowering the limit preserves existing connections but rejects new ones.
        Files.writeString(server.configPath, TestServer.baseYaml() + admin + key + ": 1\n");
        root.send("REHASH");
        root.readUntil("382", WAIT);
        assertThat(second.readThroughPong(WAIT)).isNotEmpty();
        assertRejected(server, true);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void disconnectReleasesBothLimits(boolean quit) throws Exception {
    AtomicReference<Thread> owner = new AtomicReference<>();
    try (TestServer server =
        TestServer.start(
            "maxConnections: 1\nmaxConnectionsPerIp: 1\n",
            socket -> owner.set(Thread.currentThread()))) {
      Thread firstOwner;
      try (RawIrcClient first = RawIrcClient.connectPlaintext(server.plaintextPort())) {
        first.registerAndAwaitWelcome("first", "first");
        firstOwner = owner.get();
        assertRejected(server, false);
        if (quit) {
          first.send("QUIT :leaving");
          first.readUntil("ERROR", WAIT);
        }
      }
      // Synchronize on completed teardown, rather than retrying connects until a slot opens.
      assertThat(firstOwner.join(WAIT)).isTrue();
      try (RawIrcClient replacement = RawIrcClient.connectTls(server.tlsPort())) {
        replacement.registerAndAwaitWelcome("next", "next");
      }
    }
  }

  // The loop's socket aliases are borrowed from the four try-with-resources declarations below.
  @SuppressWarnings("PMD.CloseResource")
  @Test
  void silentTlsRejectionsDoNotDelayAValidTlsClient() throws Exception {
    AtomicReference<Thread> owner = new AtomicReference<>();
    try (LogCapture logs = new LogCapture(SecurityEventLog.class);
        TestServer server =
            TestServer.start("maxConnections: 1\n", socket -> owner.set(Thread.currentThread()));
        RawIrcClient first = RawIrcClient.connectTls(server.tlsPort())) {
      // Warm up TLS before measuring admission; this connection occupies the sole slot.
      first.registerAndAwaitWelcome("first", "first");
      Thread firstOwner = owner.get();
      long started = System.nanoTime();
      try (Socket silent1 = new Socket(TestServer.LOOPBACK, server.tlsPort());
          Socket silent2 = new Socket(TestServer.LOOPBACK, server.tlsPort());
          Socket silent3 = new Socket(TestServer.LOOPBACK, server.tlsPort());
          Socket silent4 = new Socket(TestServer.LOOPBACK, server.tlsPort())) {
        logs.awaitMessage(
            message ->
                logs.messages().stream()
                        .filter(line -> line.contains("security-event=connection-limit"))
                        .count()
                    == 4,
            Duration.ofMillis(200));
        first.close();
        assertThat(firstOwner.join(Duration.ofMillis(200))).isTrue();
        try (RawIrcClient valid = RawIrcClient.connectTls(server.tlsPort())) {
          valid.registerAndAwaitWelcome("valid", "valid");
          assertThat(Duration.ofNanos(System.nanoTime() - started))
              .isLessThan(Duration.ofMillis(200));
        }
        // All four rejected peers must still be pending, rather than having timed out in series.
        for (Socket silent : java.util.List.of(silent1, silent2, silent3, silent4)) {
          silent.setSoTimeout(1);
          assertThatThrownBy(() -> silent.getInputStream().read())
              .isInstanceOf(SocketTimeoutException.class);
        }
      }
    }
  }

  @Test
  void rehashRaisesAndLowersOverridesAndLogsTheAppliedBucketLimit() throws Exception {
    String admin = TestServer.adminEnabledYaml();
    String base = admin + "maxConnectionsPerIp: 1\n";
    String override = "connectionLimitOverrides: [{cidr: '127.0.0.0/8', maxConnectionsPerIp: ";
    try (LogCapture logs = new LogCapture(SecurityEventLog.class);
        TestServer server = TestServer.start(base + override + "2}]\n");
        RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
        RawIrcClient second = RawIrcClient.connectTls(server.tlsPort())) {
      root.registerAndAwaitWelcome("root", "root");
      second.registerAndAwaitWelcome("second", "second");
      root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
      root.readUntil("381", WAIT);
      Files.writeString(server.configPath, TestServer.baseYaml() + base + override + "1}]\n");
      root.send("REHASH");
      root.readUntil("382", WAIT);
      assertRejected(server, false);
      assertThat(logs.messages())
          .anyMatch(
              message ->
                  message.contains("security-event=connection-limit")
                      && message.contains("bucket=127.0.0.1/32")
                      && message.contains("limitApplied=1"));
      assertThat(second.readThroughPong(WAIT)).isNotEmpty();
      Files.writeString(server.configPath, TestServer.baseYaml() + base + override + "3}]\n");
      root.send("REHASH");
      root.readUntil("382", WAIT);
      try (RawIrcClient third = RawIrcClient.connectPlaintext(server.plaintextPort())) {
        third.registerAndAwaitWelcome("third", "third");
        assertRejected(server, true);
      }
    }
  }

  private static void assertRejected(TestServer server, boolean tls) throws Exception {
    try (RawIrcClient rejected =
        tls
            ? RawIrcClient.connectTls(server.tlsPort())
            : RawIrcClient.connectPlaintext(server.plaintextPort())) {
      assertThat(rejected.readUntil("ERROR", WAIT)).isEqualTo("ERROR :Too many connections");
      assertThat(rejected.readLine()).isNull();
    }
  }
}
