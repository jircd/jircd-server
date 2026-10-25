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

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import net.jircd.core.extension.Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #97 over real sockets: disabling a capability through {@code EXTENSION} or {@code REHASH} prunes
 * it from every connected session in the same step as the offer, sends {@code DEL} to exactly the
 * notification-eligible clients, and enabling it again sends {@code NEW} without restoring anyone's
 * negotiation. Each run covers the three client classes: explicit legacy cap-notify, implicit
 * {@code CAP LS 302}, and a legacy client without notifications, plus an unnamed observer.
 */
class CapabilityLifecycleTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String SERVER = ":test.jircd.local";

  private static final List<String> PROVIDERS =
      List.of(
          "message-tags", "server-time", "echo-message", "away-notify", "batch", "multi-prefix");

  enum Path {
    EXTENSION,
    REHASH
  }

  static Stream<Arguments> matrix() {
    return Stream.of(Path.values())
        .flatMap(
            path -> Stream.of("multi-prefix", "message-tags").map(cap -> Arguments.of(path, cap)));
  }

  private static String capabilitiesYaml(String disabled) {
    StringBuilder yaml = new StringBuilder("capabilities:\n");
    for (String provider : PROVIDERS) {
      yaml.append("  ")
          .append(provider)
          .append(provider.equals(disabled) ? ": disabled\n" : ": enabled\n");
    }
    return yaml.toString();
  }

  private static TestServer start() throws Exception {
    return TestServer.start(TestServer.adminEnabledYaml() + capabilitiesYaml(""));
  }

  private static RawIrcClient admin(TestServer server) throws Exception {
    RawIrcClient root = RawIrcClient.connectPlaintext(server.plaintextPort());
    root.registerAndAwaitWelcome("root", "root");
    root.send("OPER " + TestServer.ADMIN_USERNAME + " :" + TestServer.ADMIN_PASSWORD);
    root.readUntil("381", TIMEOUT);
    return root;
  }

  /** Changes {@code cap} through {@code path} and waits for the administrator's confirmation. */
  private static void toggle(
      TestServer server, RawIrcClient root, Path path, String cap, boolean on) throws Exception {
    if (path == Path.EXTENSION) {
      root.send("EXTENSION " + (on ? "ENABLE " : "DISABLE ") + cap);
      assertThat(root.readUntil("NOTICE", TIMEOUT))
          .contains(cap + " is now " + (on ? "enabled" : "disabled"));
    } else {
      Files.writeString(
          server.configPath,
          TestServer.baseYaml() + TestServer.adminEnabledYaml() + capabilitiesYaml(on ? "" : cap));
      root.send("REHASH");
      root.readUntil("382", TIMEOUT);
    }
  }

  private static RawIrcClient client(TestServer server, String nick, String... preamble)
      throws Exception {
    RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
    for (String line : preamble) client.send(line);
    client.send("CAP END");
    client.registerAndAwaitWelcome(nick, nick);
    return client;
  }

  private static List<String> capLines(RawIrcClient client) throws Exception {
    return client.readThroughPong(TIMEOUT).stream().filter(l -> l.contains(" CAP ")).toList();
  }

  private static String list(RawIrcClient client, String nick) throws Exception {
    client.send("CAP LIST");
    return client.readUntil("CAP " + nick + " LIST", TIMEOUT);
  }

  /** Whether {@code observer} currently sees {@code cap}'s behavior on newly prepared output. */
  private static boolean behaves(RawIrcClient root, RawIrcClient observer, String cap)
      throws Exception {
    if (cap.equals("multi-prefix")) {
      observer.send("NAMES #room");
      return observer.readUntil("353", TIMEOUT).contains("@+implicit");
    }
    root.send("PRIVMSG implicit :probe line");
    return observer.readUntil("probe line", TIMEOUT).contains("msgid=");
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("matrix")
  void disableAndReEnableReachEveryClientClassConsistently(Path path, String cap) throws Exception {
    try (TestServer server = start();
        RawIrcClient root = admin(server);
        RawIrcClient explicit = client(server, "explicit", "CAP LS", "CAP REQ :cap-notify " + cap);
        RawIrcClient implicit = client(server, "implicit", "CAP LS 302", "CAP REQ :" + cap);
        RawIrcClient legacy = client(server, "legacy", "CAP REQ :" + cap);
        RawIrcClient unnamed = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      // A legacy client discovers core cap-notify in LS before requesting it.
      explicit.send("CAP LS");
      assertThat(explicit.readUntil("CAP explicit LS", TIMEOUT)).contains("cap-notify");
      unnamed.send("CAP LS 302");
      unnamed.readUntil("CAP * LS", TIMEOUT);
      implicit.send("JOIN #room");
      implicit.readUntil("366", TIMEOUT);
      implicit.send("MODE #room +v implicit");
      implicit.readUntil("MODE", TIMEOUT);
      assertThat(behaves(root, implicit, cap)).isTrue();

      toggle(server, root, path, cap, false);

      assertThat(capLines(explicit)).containsExactly(SERVER + " CAP explicit DEL " + cap);
      assertThat(capLines(implicit)).containsExactly(SERVER + " CAP implicit DEL " + cap);
      assertThat(capLines(unnamed)).containsExactly(SERVER + " CAP * DEL " + cap);
      assertThat(capLines(legacy)).isEmpty();

      implicit.send("CAP LS 302");
      assertThat(implicit.readUntil("CAP implicit LS", TIMEOUT))
          .contains("cap-notify")
          .doesNotContain(cap);
      assertThat(list(explicit, "explicit")).endsWith("LIST cap-notify");
      assertThat(list(implicit, "implicit")).endsWith("LIST :");
      assertThat(list(legacy, "legacy")).endsWith("LIST :");
      assertThat(behaves(root, implicit, cap)).isFalse();

      // A known name already pruned by the disable: an acknowledged no-op.
      legacy.send("CAP REQ :-" + cap);
      assertThat(legacy.readUntil("CAP legacy", TIMEOUT)).endsWith("ACK -" + cap);
      legacy.send("CAP REQ :" + cap);
      assertThat(legacy.readUntil("CAP legacy", TIMEOUT)).endsWith("NAK " + cap);

      toggle(server, root, path, cap, true);

      assertThat(capLines(explicit)).containsExactly(SERVER + " CAP explicit NEW " + cap);
      assertThat(capLines(implicit)).containsExactly(SERVER + " CAP implicit NEW " + cap);
      assertThat(capLines(unnamed)).containsExactly(SERVER + " CAP * NEW " + cap);
      assertThat(capLines(legacy)).isEmpty();
      // Enable restores no negotiation: behavior resumes only after a new request.
      assertThat(list(implicit, "implicit")).endsWith("LIST :");
      assertThat(behaves(root, implicit, cap)).isFalse();
      implicit.send("CAP REQ :" + cap);
      assertThat(implicit.readUntil("CAP implicit", TIMEOUT)).endsWith("ACK " + cap);
      assertThat(behaves(root, implicit, cap)).isTrue();
    }
  }

  @Test
  void aRepeatedToggleSendsNoDuplicateNotification() throws Exception {
    try (TestServer server = start();
        RawIrcClient root = admin(server);
        RawIrcClient implicit = client(server, "implicit", "CAP LS 302")) {
      toggle(server, root, Path.EXTENSION, "multi-prefix", false);
      assertThat(capLines(implicit)).hasSize(1);
      toggle(server, root, Path.EXTENSION, "multi-prefix", false);
      toggle(server, root, Path.REHASH, "multi-prefix", false);
      assertThat(capLines(implicit)).isEmpty();
      toggle(server, root, Path.REHASH, "multi-prefix", true);
      assertThat(capLines(implicit)).hasSize(1);
      toggle(server, root, Path.EXTENSION, "multi-prefix", true);
      assertThat(capLines(implicit)).isEmpty();
    }
  }

  @Test
  void anInvalidReloadChangesNoStateAndSendsNothing() throws Exception {
    try (TestServer server = start();
        RawIrcClient root = admin(server);
        RawIrcClient implicit = client(server, "implicit", "CAP LS 302", "CAP REQ :multi-prefix")) {
      Files.writeString(
          server.configPath,
          TestServer.baseYaml()
              + TestServer.adminEnabledYaml()
              + capabilitiesYaml("multi-prefix")
              + "nicknameMaxLength: 0\n");
      root.send("REHASH");
      assertThat(root.readUntil("NOTICE", TIMEOUT)).contains("Rehash failed");

      assertThat(capLines(implicit)).isEmpty();
      assertThat(list(implicit, "implicit")).endsWith("LIST multi-prefix");
      assertThat(server.application.extensionRegistry().stateOf("multi-prefix"))
          .isEqualTo(Extension.State.ENABLED);
    }
  }
}
