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

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import net.jircd.core.extension.CapabilityExtension;
import net.jircd.core.session.ClientSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each of the six capability providers, absent from an isolated catalog, takes its offer and its
 * behavior with it, and nothing else. The catalog is the ServiceLoader view a server starts with: a
 * class loader that hides one provider class from the {@code CapabilityExtension} service file. No
 * jar or class file is touched or deleted, and the desired configuration enables only providers
 * that are present, as an administrator's would. Core cap-notify is offered whatever is missing.
 */
class CapabilityProviderAbsenceTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final List<String> PROVIDERS =
      List.of(
          "message-tags", "server-time", "echo-message", "away-notify", "batch", "multi-prefix");

  @TempDir Path directory;

  /** A class loader whose view of the capability service file lacks some provider classes. */
  private static final class WithoutProvider extends ClassLoader {
    private final Set<String> hiddenClassSuffixes;
    private final Path scratch;

    WithoutProvider(Set<String> hiddenClassSuffixes, Path scratch) {
      super(CapabilityProviderAbsenceTest.class.getClassLoader());
      this.hiddenClassSuffixes = hiddenClassSuffixes;
      this.scratch = scratch;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
      Enumeration<URL> original = super.getResources(name);
      if (!name.equals("META-INF/services/" + CapabilityExtension.class.getName())) {
        return original;
      }
      List<URL> filtered = new ArrayList<>();
      for (URL url : Collections.list(original)) {
        List<String> kept;
        try (var in = url.openStream()) {
          kept =
              new String(in.readAllBytes(), StandardCharsets.UTF_8)
                  .lines()
                  .filter(line -> hiddenClassSuffixes.stream().noneMatch(line.trim()::endsWith))
                  .toList();
        }
        Path file = Files.createTempFile(scratch, "services-", ".txt");
        Files.write(file, kept);
        filtered.add(file.toUri().toURL());
      }
      return Collections.enumeration(filtered);
    }
  }

  private static String providerClass(String capability) {
    return switch (capability) {
      case "message-tags" -> "MessageTagsExtension";
      case "server-time" -> "ServerTimeExtension";
      case "echo-message" -> "EchoMessageExtension";
      case "away-notify" -> "AwayNotifyExtension";
      case "batch" -> "BatchExtension";
      case "multi-prefix" -> "MultiPrefixExtension";
      default -> throw new IllegalArgumentException(capability);
    };
  }

  /** The configuration an administrator would write for the providers still installed. */
  private static String yamlWithout(String absent) {
    StringBuilder yaml = new StringBuilder("capabilities:\n");
    for (String provider : PROVIDERS) {
      if (!provider.equals(absent)) yaml.append("  ").append(provider).append(": enabled\n");
    }
    return yaml.toString();
  }

  private TestServer startWithout(String absent) throws Exception {
    return startHiding(Set.of(providerClass(absent)), yamlWithout(absent));
  }

  private TestServer startHiding(Set<String> hiddenClasses, String yaml) throws Exception {
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    thread.setContextClassLoader(new WithoutProvider(hiddenClasses, directory));
    try {
      return TestServer.start(yaml);
    } finally {
      thread.setContextClassLoader(original);
    }
  }

  /** What LS must offer: the given providers plus core cap-notify, which no provider owns. */
  private static List<String> withCapNotify(List<String> providers) {
    List<String> offers = new ArrayList<>(providers);
    offers.add("cap-notify");
    return offers;
  }

  private static Set<String> offered(RawIrcClient client) throws Exception {
    client.send("CAP LS 302");
    String response = client.readUntil("CAP * LS", TIMEOUT);
    String caps = response.substring(response.indexOf(" LS ") + " LS ".length()).trim();
    // A one-name list is sent without the trailing-parameter colon.
    if (caps.startsWith(":")) caps = caps.substring(1);
    return caps.isEmpty() ? Set.of() : Set.of(caps.split(" "));
  }

  private static RawIrcClient registered(TestServer server, String nick, String caps)
      throws Exception {
    RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort());
    if (!caps.isEmpty()) {
      client.send("CAP REQ :" + caps);
      client.readUntil("CAP * ACK", TIMEOUT);
      client.send("CAP END");
    }
    client.registerAndAwaitWelcome(nick, nick);
    return client;
  }

  @Test
  void theFullCatalogOffersAllSixAndTheBaselineIsWhatTheAbsenceTestsSubtract() throws Exception {
    try (TestServer server = TestServer.start(yamlWithout("none"));
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      assertThat(offered(client)).containsExactlyInAnyOrderElementsOf(withCapNotify(PROVIDERS));
    }
  }

  @Test
  void withEveryProviderMissingLsStillOffersCapNotifyAndItCanBeRequested() throws Exception {
    Set<String> all = new java.util.HashSet<>();
    PROVIDERS.forEach(p -> all.add(providerClass(p)));
    try (TestServer server = startHiding(all, "");
        RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
      assertThat(offered(client)).containsExactly("cap-notify");
      client.send("CAP REQ :cap-notify");
      assertThat(client.readUntil("CAP * ACK", TIMEOUT)).endsWith("ACK cap-notify");
      client.send("CAP LIST");
      assertThat(client.readUntil("CAP * LIST", TIMEOUT)).endsWith("LIST cap-notify");
    }
  }

  @Test
  void aMissingProviderIsNotOfferedAndCannotBeRequested() throws Exception {
    for (String absent : PROVIDERS) {
      try (TestServer server = startWithout(absent);
          RawIrcClient client = RawIrcClient.connectPlaintext(server.plaintextPort())) {
        assertThat(offered(client))
            .as("offers without %s", absent)
            .doesNotContain(absent)
            .containsExactlyInAnyOrderElementsOf(
                withCapNotify(PROVIDERS.stream().filter(p -> !p.equals(absent)).toList()));
        client.send("CAP REQ :" + absent);
        assertThat(client.readUntil("CAP * NAK", TIMEOUT)).contains(absent);
        client.send("CAP REQ :cap-notify");
        assertThat(client.readUntil("CAP * ACK", TIMEOUT)).endsWith("ACK cap-notify");
      }
    }
  }

  @Test
  void withoutMessageTagsTagmsgIsUnknownAndNoMsgidOrClientTagIsSent() throws Exception {
    try (TestServer server = startWithout("message-tags");
        RawIrcClient alice = registered(server, "alice", "server-time echo-message");
        RawIrcClient bob = registered(server, "bob", "server-time")) {
      alice.send("@+typing=active TAGMSG bob");
      assertThat(alice.readUntil("421", TIMEOUT)).contains("TAGMSG");

      alice.send("@+typing=active PRIVMSG bob :hello");
      String line = bob.readUntil("hello", TIMEOUT);
      assertThat(line).contains("time=").doesNotContain("msgid=").doesNotContain("+typing");
    }
  }

  @Test
  void withoutServerTimeNoTimeTagIsSentButMessageTagsStillWork() throws Exception {
    try (TestServer server = startWithout("server-time");
        RawIrcClient alice = registered(server, "alice", "");
        RawIrcClient bob = registered(server, "bob", "message-tags")) {
      alice.send("PRIVMSG bob :hello");
      assertThat(bob.readUntil("hello", TIMEOUT)).contains("msgid=").doesNotContain("time=");
    }
  }

  @Test
  void withoutEchoMessageTheSenderGetsNoCopy() throws Exception {
    try (TestServer server = startWithout("echo-message");
        RawIrcClient alice = registered(server, "alice", "message-tags");
        RawIrcClient bob = registered(server, "bob", "")) {
      alice.send("PRIVMSG bob :hello");
      bob.readUntil("hello", TIMEOUT);
      assertThat(alice.readThroughPong(TIMEOUT)).noneMatch(l -> l.contains("PRIVMSG bob"));
    }
  }

  @Test
  void withoutAwayNotifyAwayWorksButNoNoticeIsSent() throws Exception {
    try (TestServer server = startWithout("away-notify");
        RawIrcClient alice = registered(server, "alice", "");
        RawIrcClient bob = registered(server, "bob", "")) {
      alice.send("JOIN #lobby");
      alice.readUntil("353", TIMEOUT);
      bob.send("JOIN #lobby");
      bob.readUntil("353", TIMEOUT);
      alice.send("AWAY :lunch");
      assertThat(alice.readUntil("306", TIMEOUT)).contains("marked as being away");
      assertThat(bob.readThroughPong(TIMEOUT)).noneMatch(l -> l.contains(" AWAY"));
      assertThat(server.application.nicknameRegistry().lookup("alice").orElseThrow().awayReason())
          .isEqualTo("lunch");
    }
  }

  @Test
  void withoutMultiPrefixNamesAndWhoShowTheHighestStatusOnly() throws Exception {
    try (TestServer server = startWithout("multi-prefix");
        RawIrcClient alice = registered(server, "alice", "");
        RawIrcClient bob = registered(server, "bob", "")) {
      alice.send("JOIN #lobby");
      alice.readUntil("353", TIMEOUT);
      bob.send("JOIN #lobby");
      bob.readUntil("353", TIMEOUT);
      alice.send("MODE #lobby +v bob");
      alice.readUntil("MODE", TIMEOUT);
      alice.send("MODE #lobby +v alice");
      alice.readUntil("MODE", TIMEOUT);
      alice.send("NAMES #lobby");
      assertThat(alice.readUntil("353", TIMEOUT)).contains("@alice").doesNotContain("@+alice");
      alice.send("WHO #lobby");
      assertThat(alice.readUntil("352", TIMEOUT)).doesNotContain("@+");
    }
  }

  @Test
  void withoutBatchGroupsAreSentPlainAndNothingElseChanges() throws Exception {
    try (TestServer server = startWithout("batch");
        RawIrcClient alice = registered(server, "alice", "message-tags")) {
      ClientSession session = server.application.nicknameRegistry().lookup("alice").orElseThrow();
      session
          .writer()
          .enqueueGroup(
              "probe",
              List.of(),
              List.of(
                  new net.jircd.protocol.Message(
                      java.util.Map.of(),
                      "bot!bot@host",
                      net.jircd.protocol.Command.PRIVMSG,
                      "PRIVMSG",
                      List.of("alice", "one"))));
      List<String> lines = alice.readThroughPong(TIMEOUT);
      assertThat(lines).anyMatch(l -> l.contains("PRIVMSG alice one"));
      assertThat(lines).noneMatch(l -> l.contains("BATCH"));
    }
  }

  @Test
  void theUnrelatedProvidersAndCoreKeepWorkingWithAnyOneMissing() throws Exception {
    for (String absent : PROVIDERS) {
      try (TestServer server = startWithout(absent);
          RawIrcClient alice = registered(server, "alice", "");
          RawIrcClient bob = registered(server, "bob", "")) {
        alice.send("JOIN #core");
        alice.readUntil("353", TIMEOUT);
        bob.send("JOIN #core");
        bob.readUntil("353", TIMEOUT);
        alice.send("PRIVMSG #core :still works without " + absent);
        assertThat(bob.readUntil("still works", TIMEOUT)).contains("PRIVMSG #core");
        alice.send("NICK alicia");
        assertThat(bob.readUntil("NICK", TIMEOUT)).contains("alicia");
      }
    }
  }
}
