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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.DeliveryRole;
import net.jircd.core.extension.capability.EventIntent;
import net.jircd.core.extension.capability.EventKind;
import net.jircd.core.extension.capability.FaultReporter;
import net.jircd.core.extension.capability.FramingPlan;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.InboundTagResult;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * #102 over real sockets: a registered probe faults in one wire-affecting hook. The output that
 * triggered it uses the hook's documented default, the probe is withdrawn as FAILED with one {@code
 * DEL} to eligible clients, committed core work stands, an unrelated provider keeps contributing,
 * clients without notifications see no {@code DEL}, and the diagnostic carries no payload.
 */
class CapabilityFailureIsolationTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String SECRET = "s3cr3t-payload";

  /** Only the probes: every discovered provider is off, so each fallback is exact. */
  private static final String YAML =
      """
      capabilities:
        message-tags: disabled
        server-time: disabled
        echo-message: disabled
        away-notify: disabled
        batch: disabled
        multi-prefix: disabled
      """;

  /** Every wire-affecting hook; the one named by {@link #faulting} throws. */
  private static final class Probe extends AbstractCapabilityExtension {
    volatile HookKind faulting;
    final CountDownLatch stopped = new CountDownLatch(1);

    Probe() {
      super("probe");
    }

    @Override
    public void stop() {
      stopped.countDown();
    }

    private void maybeFault(HookKind kind) {
      if (faulting == kind) throw new IllegalStateException(SECRET);
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onEvent(
          EventKind.AWAY_CHANGED,
          event -> {
            maybeFault(HookKind.EVENT);
            Message notice =
                new Message(
                    Map.of(),
                    "test.jircd.local",
                    Command.NOTICE,
                    "NOTICE",
                    List.of("*", "probe away " + event.subject().split("!")[0]));
            return List.of(new EventIntent(notice, event.audience().peers()));
          });
      registrar.onInboundTags(
          (tags, context) -> {
            maybeFault(HookKind.INBOUND_TAGS);
            String value = tags.tags().get("+probe");
            return value == null
                ? InboundTagResult.NONE
                : new InboundTagResult(Map.of("+probe", value), Map.of());
          });
      registrar.onRecipients(
          (intent, context) -> {
            maybeFault(HookKind.RECIPIENTS);
            return Set.of(DeliveryRole.SENDER_COPY);
          });
      registrar.onOutboundTags(
          Set.of("probe"),
          16,
          message -> {
            maybeFault(HookKind.OUTBOUND_TAGS);
            return Map.of("probe", "ok");
          });
      registrar.onMemberStatus(
          limits -> 2,
          member -> {
            maybeFault(HookKind.MEMBER_STATUS);
            return String.join("", member.heldStatuses());
          });
      registrar.onNamesIdentity(
          limits -> 6,
          member -> {
            maybeFault(HookKind.NAMES_IDENTITY);
            return member.nickname() + "!probe";
          });
      registrar.onOutputGroup(
          Set.of(),
          (group, context) -> {
            maybeFault(HookKind.OUTPUT_GROUP);
            List<Message> frames = new ArrayList<>();
            frames.add(new Message(Map.of(), null, null, "PROBE", List.of("+g", group.type())));
            frames.addAll(group.members());
            frames.add(new Message(Map.of(), null, null, "PROBE", List.of("-g")));
            return new FramingPlan(frames);
          });
      registrar.command(
          Command.SQUERY,
          (session, message) -> {
            maybeFault(HookKind.COMMAND);
            session
                .writer()
                .enqueueRaw(
                    new Message(
                        Map.of(),
                        "test.jircd.local",
                        Command.NOTICE,
                        "NOTICE",
                        List.of(session.nickname(), "probe handled")));
          });
    }
  }

  /** An unrelated provider whose tag must survive the probe's failure. */
  private static final class Witness extends AbstractCapabilityExtension {
    Witness() {
      super("witness");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onOutboundTags(Set.of("witness"), 16, message -> Map.of("witness", "w"));
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

  private static ClientSession live(TestServer server, String nick) {
    return server.application.nicknameRegistry().lookup(nick).orElseThrow();
  }

  /** The probe's behavior on new output while it is healthy, then with {@code kind} faulting. */
  private static List<String> exercise(
      HookKind kind, TestServer server, RawIrcClient alice, RawIrcClient bob) throws Exception {
    switch (kind) {
      case EVENT -> {
        bob.send("AWAY :" + SECRET);
        bob.readUntil("306", TIMEOUT);
      }
      case INBOUND_TAGS, RECIPIENTS, OUTBOUND_TAGS ->
          bob.send("@+probe=" + SECRET + " PRIVMSG #room :hello");
      case MEMBER_STATUS, NAMES_IDENTITY -> alice.send("NAMES #room");
      case OUTPUT_GROUP ->
          live(server, "alice")
              .writer()
              .enqueueGroup(
                  "probe",
                  List.of(),
                  List.of(
                      new Message(
                          Map.of(),
                          "bot!bot@host",
                          Command.PRIVMSG,
                          "PRIVMSG",
                          List.of("#room", "one"))));
      case COMMAND -> alice.send("SQUERY x");
      default -> throw new IllegalArgumentException(kind.toString());
    }
    if (kind == HookKind.RECIPIENTS) return relevant(bob.readThroughPong(TIMEOUT));
    // bob's commands run in order and alice's queue is FIFO, so his next line is a barrier.
    bob.send("PRIVMSG alice :barrier");
    List<String> lines = new ArrayList<>();
    String line;
    do {
      line = alice.readUntil(" ", TIMEOUT);
      lines.add(line);
    } while (!line.endsWith("PRIVMSG alice barrier"));
    lines.addAll(alice.readThroughPong(TIMEOUT));
    return relevant(lines);
  }

  private static List<String> relevant(List<String> lines) {
    return lines.stream()
        .filter(
            l ->
                l.contains("probe")
                    || l.contains("PROBE")
                    || l.contains("PRIVMSG #room")
                    || l.contains(" 353 ")
                    || l.contains(" 421 ")
                    || l.contains(" CAP "))
        .filter(l -> !l.startsWith("PONG") && !l.endsWith(" barrier"))
        .toList();
  }

  /** Exactly what each hook's documented default produces for the faulting operation. */
  private static List<String> fallback(HookKind kind) {
    String from = " :bob!bob@127.0.0.1 PRIVMSG #room hello";
    return switch (kind) {
      case EVENT -> List.of(); // no notice: the committed AWAY itself stands (checked below)
      case INBOUND_TAGS -> List.of("@witness=w" + from);
      case OUTBOUND_TAGS -> List.of("@+probe=" + SECRET + ";witness=w" + from);
      case RECIPIENTS -> List.of(); // no sender copy
      case MEMBER_STATUS ->
          List.of(":test.jircd.local 353 alice = #room :@alice!probe +bob!probe carol!probe");
      case NAMES_IDENTITY -> List.of(":test.jircd.local 353 alice = #room :@+alice +bob carol");
      case OUTPUT_GROUP -> List.of(":bot!bot@host PRIVMSG #room one");
      case COMMAND -> List.of(); // nothing: a faulting handler has no reply to send
      default -> throw new IllegalArgumentException(kind.toString());
    };
  }

  /**
   * The probe's own outbound tag on the message whose inbound hook faulted. It is there if alice's
   * view was captured before the withdrawal published and absent if after; both are allowed, so
   * only the dropped {@code +probe} forward is asserted.
   */
  private static String withoutLateProbeTag(String line) {
    return line.replace(";probe=ok;", ";").replace("@probe=ok;", "@");
  }

  /**
   * A line with its tags, and a NAMES line's member tokens, sorted: neither order is under test.
   */
  private static String normalized(String line) {
    String tags = "";
    String rest = line;
    if (line.startsWith("@")) {
      List<String> sorted =
          new ArrayList<>(List.of(line.substring(1, line.indexOf(' ')).split(";")));
      sorted.sort(null);
      tags = "@" + String.join(";", sorted) + " ";
      rest = line.substring(line.indexOf(' ') + 1);
    }
    int list = rest.indexOf(" :", 1);
    if (rest.contains(" 353 ") && list >= 0) {
      List<String> tokens = new ArrayList<>(List.of(rest.substring(list + 2).split(" ")));
      tokens.sort(null);
      rest = rest.substring(0, list + 2) + String.join(" ", tokens);
    }
    return tags + rest;
  }

  @ParameterizedTest
  @EnumSource(
      value = HookKind.class,
      names = {
        "EVENT",
        "INBOUND_TAGS",
        "RECIPIENTS",
        "OUTBOUND_TAGS",
        "MEMBER_STATUS",
        "NAMES_IDENTITY",
        "OUTPUT_GROUP",
        "COMMAND"
      })
  void aFaultingHookFallsBackWithdrawsTheProbeAsFailedAndLeavesEverythingElseWorking(HookKind kind)
      throws Exception {
    Probe probe = new Probe();
    try (TestServer server = TestServer.start(YAML);
        LogCapture logs = new LogCapture(FaultReporter.class)) {
      ExtensionRegistry registry = server.application.extensionRegistry();
      registry.register(probe);
      registry.enable("probe");
      registry.register(new Witness());
      registry.enable("witness");
      try (RawIrcClient alice = client(server, "alice", "CAP LS 302", "CAP REQ :probe witness");
          RawIrcClient bob = client(server, "bob", "CAP LS 302", "CAP REQ :probe witness");
          RawIrcClient carol = client(server, "carol", "CAP REQ :witness")) {
        alice.send("JOIN #room");
        alice.readUntil("366", TIMEOUT);
        alice.send("MODE #room +v alice");
        bob.send("JOIN #room");
        bob.readUntil("366", TIMEOUT);
        carol.send("JOIN #room");
        carol.readUntil("366", TIMEOUT);
        alice.send("MODE #room +v bob");
        alice.readThroughPong(TIMEOUT);
        bob.readThroughPong(TIMEOUT);
        carol.readThroughPong(TIMEOUT);

        // Healthy: the probe's behavior is visible.
        List<String> healthy = exercise(kind, server, alice, bob);
        assertThat(
                healthy.stream()
                    .filter(l -> !l.contains(" CAP "))
                    .map(CapabilityFailureIsolationTest::normalized)
                    .toList())
            .isNotEmpty()
            .isNotEqualTo(
                fallback(kind).stream().map(CapabilityFailureIsolationTest::normalized).toList());
        carol.readThroughPong(TIMEOUT);

        probe.faulting = kind;
        List<String> faulted = exercise(kind, server, alice, bob);
        Withdrawals.awaitFailed(registry, "probe", probe.stopped);

        List<String> output =
            faulted.stream()
                .filter(l -> !l.contains(" CAP "))
                .map(CapabilityFailureIsolationTest::normalized)
                .map(l -> kind == HookKind.INBOUND_TAGS ? withoutLateProbeTag(l) : l)
                .toList();
        assertThat(output)
            .containsExactlyElementsOf(
                fallback(kind).stream().map(CapabilityFailureIsolationTest::normalized).toList());
        List<String> aliceLater = alice.readThroughPong(TIMEOUT);
        List<String> notices = new ArrayList<>(faulted);
        notices.addAll(aliceLater);
        assertThat(notices.stream().filter(l -> l.contains(" CAP alice ")))
            .containsExactly(":test.jircd.local CAP alice DEL probe");
        // A client without notifications learns nothing, and keeps its own provider.
        assertThat(carol.readThroughPong(TIMEOUT)).noneMatch(l -> l.contains(" CAP "));
        bob.readThroughPong(TIMEOUT);
        assertThat(registry.stateOf("witness")).isEqualTo(Extension.State.ENABLED);

        bob.send("PRIVMSG #room :after");
        assertThat(carol.readUntil("after", TIMEOUT))
            .isEqualTo("@witness=w :bob!bob@127.0.0.1 PRIVMSG #room after");
        if (kind == HookKind.EVENT) {
          // The AWAY committed before its hook faulted.
          alice.send("WHOIS bob");
          assertThat(alice.readUntil("301", TIMEOUT)).endsWith(" " + SECRET);
        }
        if (kind == HookKind.COMMAND) {
          alice.send("SQUERY x");
          assertThat(alice.readUntil("421", TIMEOUT)).contains("SQUERY");
        }
        alice.send("CAP LIST");
        assertThat(alice.readUntil("CAP alice LIST", TIMEOUT)).endsWith("LIST witness");

        // Logged during the withdrawal, which has finished.
        assertThat(logs.messages())
            .filteredOn(m -> m.contains("FAILED"))
            .singleElement()
            .asString()
            .contains("'probe'", kind.toString(), "EXCEPTION");
        assertThat(logs.messages()).noneMatch(m -> m.contains(SECRET));
      }
    }
  }
}
