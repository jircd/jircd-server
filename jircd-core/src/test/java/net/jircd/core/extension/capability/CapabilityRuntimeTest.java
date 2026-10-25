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
package net.jircd.core.extension.capability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.capability.CapabilityNegotiator;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.session.CapabilityTagRenderer;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.TagRenderer;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Snapshot publication and the all-session prune happen as one step under the administrative mutex
 * that CAP state and live-session tracking share (#97). Work captured earlier may still finish
 * after DEL; the next capture sees the new state. Group admission alone makes a current
 * prerequisite choice. Provider callbacks run outside the mutex.
 */
class CapabilityRuntimeTest {

  private static final String SERVER = "irc.example";
  private static final long WAIT_SECONDS = 5;

  private static class Offer extends AbstractCapabilityExtension {
    Offer(String name) {
      super(name);
    }
  }

  /** Requires {@code alpha} as a hard provider dependency. */
  private static final class Dependent extends Offer {
    Dependent() {
      super("dependent");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.requiresProviders("alpha");
    }
  }

  /** Requires {@code requires}, if any, and records when it is stopped. */
  private static final class Chained extends Offer {
    private final String requires;
    private final List<String> stops;

    Chained(String id, String requires, List<String> stops) {
      super(id);
      this.requires = requires;
      this.stops = stops;
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      if (requires != null) registrar.requiresProviders(requires);
    }

    @Override
    public void stop() {
      stops.add(id());
    }
  }

  /** Frames groups for recipients that negotiated "framer" and "tags"; may hold the hook open. */
  private static final class Framer extends Offer {
    volatile CountDownLatch entered;
    volatile CountDownLatch release;

    Framer() {
      super("framer");
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      registrar.onOutputGroup(
          Set.of("tags"),
          (group, context) -> {
            CountDownLatch gate = release;
            if (gate != null) {
              entered.countDown();
              try {
                gate.await(WAIT_SECONDS, TimeUnit.SECONDS);
              } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
              }
            }
            List<Message> frames = new ArrayList<>();
            frames.add(new Message(Map.of(), null, null, "BATCH", List.of("+g", group.type())));
            frames.addAll(group.members());
            frames.add(new Message(Map.of(), null, null, "BATCH", List.of("-g")));
            return new FramingPlan(frames);
          });
    }
  }

  /** Records whether the administrative mutex was free while its callbacks ran. */
  private static final class Probe extends Offer {
    final AtomicReference<CapabilityControl> runtime = new AtomicReference<>();
    final AtomicBoolean startSawFreeMutex = new AtomicBoolean();
    final AtomicBoolean stopSawFreeMutex = new AtomicBoolean();

    Probe() {
      super("probe");
    }

    @Override
    public void start(ServerContext context) {
      startSawFreeMutex.set(mutexFreeFromAnotherThread(runtime.get()));
    }

    @Override
    public void stop() {
      stopSawFreeMutex.set(mutexFreeFromAnotherThread(runtime.get()));
    }
  }

  private static boolean mutexFreeFromAnotherThread(CapabilityControl runtime) {
    try {
      return CompletableFuture.supplyAsync(() -> runtime.underControl(() -> true))
          .get(WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception _) {
      return false;
    }
  }

  private ExtensionRegistry registry;
  private CapabilityControl runtime;
  private final Set<ClientSession> live = ConcurrentHashMap.newKeySet();

  @BeforeEach
  void setUp() {
    registry = new ExtensionRegistry();
    registry.attachContext(
        new ServerContext(
            null, null, registry, () -> SERVER, null, null, null, registry.capabilityServices()));
    runtime = registry.capabilityControl();
    registry.attachLiveSessions(live);
  }

  private void enable(Extension... extensions) {
    for (Extension extension : extensions) {
      registry.register(extension);
      registry.enable(extension.id());
    }
  }

  private ClientSession connect(String id, String... negotiated) {
    ClientSession session = new ClientSession(id, "host.example", RateLimitBucket.withDefaults());
    session.replaceNegotiatedCapabilities(List.of(negotiated));
    live.add(session);
    return session;
  }

  private static Message privmsg(String body) {
    return new Message(
        Map.of(), "sender!ident@host", Command.PRIVMSG, "PRIVMSG", List.of("#chan", body));
  }

  private static List<String> lines(ByteArrayOutputStream out) {
    String text = out.toString(StandardCharsets.UTF_8);
    return text.isEmpty() ? List.of() : List.of(text.split("\r\n"));
  }

  @Test
  void everyCaptureSeesEitherTheOldStateOrTheNewSnapshotWithEverySessionPruned() throws Exception {
    enable(new Offer("alpha"));
    List<ClientSession> sessions = new ArrayList<>();
    for (int i = 0; i < 200; i++) sessions.add(connect("c" + i, "alpha"));
    AtomicBoolean done = new AtomicBoolean();
    AtomicReference<String> violation = new AtomicReference<>();
    Thread observer =
        Thread.ofVirtual()
            .start(
                () -> {
                  while (!done.get()) {
                    runtime.runUnderControl(
                        () -> {
                          boolean offered =
                              registry.capabilitySnapshot().descriptor("alpha").isPresent();
                          long holders =
                              sessions.stream()
                                  .filter(s -> s.negotiatedCapabilities().contains("alpha"))
                                  .count();
                          if (holders != (offered ? sessions.size() : 0)) {
                            violation.set("offered=" + offered + " holders=" + holders);
                          }
                        });
                  }
                });
    registry.disable("alpha");
    done.set(true);
    observer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    assertThat(violation.get()).isNull();
    assertThat(sessions).allMatch(s -> s.negotiatedCapabilities().isEmpty());
  }

  @Test
  void aRequestRacingDisableNeverLeavesTheWithdrawnNameNegotiated() throws Exception {
    CapabilityNegotiator negotiator = new CapabilityNegotiator(registry);
    registry.register(new Offer("alpha"));
    for (int i = 0; i < 200; i++) {
      registry.enable("alpha");
      ClientSession session = connect("c" + i);
      CountDownLatch go = new CountDownLatch(1);
      Thread requester =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      go.await();
                    } catch (InterruptedException _) {
                      Thread.currentThread().interrupt();
                    }
                    negotiator.request(session, List.of("alpha"));
                  });
      go.countDown();
      registry.disable("alpha");
      requester.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
      assertThat(session.negotiatedCapabilities()).as("iteration %d", i).isEmpty();
    }
  }

  @Test
  void publicationWaitsForTheMutexThatCapStateAndSessionTrackingHold() throws Exception {
    enable(new Offer("alpha"));
    ClientSession holder = connect("c1", "alpha");
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread owner =
        Thread.ofVirtual()
            .start(
                () ->
                    runtime.runUnderControl(
                        () -> {
                          held.countDown();
                          try {
                            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                          } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> disable = CompletableFuture.runAsync(() -> registry.disable("alpha"));

    // Bounded: the disable cannot publish or prune while CAP or session tracking holds the mutex.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> disable.get(200, TimeUnit.MILLISECONDS))
        .isInstanceOf(java.util.concurrent.TimeoutException.class);
    assertThat(registry.capabilitySnapshot().descriptor("alpha")).isPresent();
    assertThat(holder.negotiatedCapabilities()).contains("alpha");

    release.countDown();
    disable.get(WAIT_SECONDS, TimeUnit.SECONDS);
    owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    assertThat(holder.negotiatedCapabilities()).isEmpty();
    assertThat(registry.capabilitySnapshot().descriptor("alpha")).isEmpty();
  }

  @Test
  void anOldCapturedViewStaysUsableAfterDelWhileTheNextCaptureUsesTheNewState() {
    Framer framer = new Framer();
    enable(framer, new Offer("tags"));
    ClientSession recipient = connect("c1", "framer", "tags");
    OutputGroup group =
        new OutputGroup("g1", "type", List.of(), List.of(privmsg("a")), Long.MAX_VALUE);

    CapabilityRuntime.CapturedView old = runtime.capture(recipient);
    registry.disable("framer");
    CapabilityRuntime.CapturedView current = runtime.capture(recipient);

    // The captured work finishes against its own view, framing included.
    FramingPlan late =
        registry
            .hookDispatcher()
            .outputGroup(old.snapshot(), old.negotiated(), group, RequestContext.of(0, Map.of()));
    assertThat(late.frames()).hasSize(3);
    assertThat(current.negotiated()).containsExactly("tags");
    assertThat(current.snapshot().hooksFor(HookKind.OUTPUT_GROUP, current.negotiated())).isEmpty();
  }

  @Test
  void aGroupStillPreparingWhenItsPrerequisiteGoesIneffectiveIsAdmittedPlain() throws Exception {
    Framer framer = new Framer();
    enable(framer, new Offer("tags"));
    ClientSession recipient = connect("c1", "framer", "tags");
    framer.entered = new CountDownLatch(1);
    framer.release = new CountDownLatch(1);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer =
        new SessionWriter(recipient, out, new CapabilityTagRenderer(registry))) {
      recipient.attachWriter(writer);
      Thread sender =
          Thread.ofVirtual()
              .start(() -> writer.enqueueGroup("type", List.of(), List.of(privmsg("a"))));
      assertThat(framer.entered.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
      registry.disable("tags");
      framer.release.countDown();
      sender.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    }
    assertThat(lines(out)).containsExactly(":sender!ident@host PRIVMSG #chan a");
  }

  @Test
  void anAdmittedGroupOnAStalledSocketKeepsItsCloseAcrossTheDisable() {
    Framer framer = new Framer();
    enable(framer, new Offer("tags"));
    ClientSession recipient = connect("c1", "framer", "tags");
    CountDownLatch unstall = new CountDownLatch(1);
    ByteArrayOutputStream written = new ByteArrayOutputStream();
    OutputStream stalled =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            try {
              unstall.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
            written.write(b); // ByteArrayOutputStream synchronizes its own writes and reads
          }
        };
    try (SessionWriter writer =
        new SessionWriter(recipient, stalled, new CapabilityTagRenderer(registry))) {
      recipient.attachWriter(writer);
      writer.enqueueGroup("type", List.of(), List.of(privmsg("a")));
      registry.disable("tags");
      registry.disable("framer");
      unstall.countDown();
    }
    assertThat(lines(written))
        .startsWith("BATCH +g type", ":sender!ident@host PRIVMSG #chan a", "BATCH -g");
  }

  @Test
  void anOverflowWhileQueuingDelClosesOnlyThatRecipient() {
    enable(new Offer("alpha"));
    ClientSession tiny = connect("tiny");
    tiny.recordCapVersion(302);
    ClientSession roomy = connect("roomy");
    roomy.recordCapVersion(302);
    ByteArrayOutputStream tinyOut = new ByteArrayOutputStream();
    ByteArrayOutputStream roomyOut = new ByteArrayOutputStream();
    try (SessionWriter tinyWriter = new SessionWriter(tiny, tinyOut, TagRenderer.NONE, 8);
        SessionWriter roomyWriter = new SessionWriter(roomy, roomyOut, TagRenderer.NONE)) {
      tiny.attachWriter(tinyWriter);
      roomy.attachWriter(roomyWriter);
      registry.disable("alpha");
    }
    assertThat(lines(tinyOut))
        .isNotEmpty()
        .noneMatch(l -> l.contains("DEL"))
        .anyMatch(l -> l.contains("SendQ"));
    assertThat(lines(roomyOut)).containsExactly(":irc.example CAP * DEL alpha");
    assertThat(registry.stateOf("alpha")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void aNotificationThatCannotBeBuiltForOneRecipientDoesNotStopTheOthersOrThePublication() {
    enable(new Offer("alpha"));
    ClientSession unrepresentable = connect("long", "alpha");
    unrepresentable.recordCapVersion(302);
    // No CAP line can carry a target this long, so its DEL cannot be built.
    unrepresentable.setNickname("n".repeat(600));
    ClientSession ordinary = connect("ordinary", "alpha");
    ordinary.recordCapVersion(302);
    ByteArrayOutputStream longOut = new ByteArrayOutputStream();
    ByteArrayOutputStream ordinaryOut = new ByteArrayOutputStream();
    try (SessionWriter longWriter = new SessionWriter(unrepresentable, longOut, TagRenderer.NONE);
        SessionWriter writer = new SessionWriter(ordinary, ordinaryOut, TagRenderer.NONE)) {
      unrepresentable.attachWriter(longWriter);
      ordinary.attachWriter(writer);
      registry.disable("alpha");
    }
    assertThat(lines(longOut)).isEmpty();
    assertThat(lines(ordinaryOut)).containsExactly(":irc.example CAP * DEL alpha");
    assertThat(unrepresentable.negotiatedCapabilities()).isEmpty();
    assertThat(registry.stateOf("alpha")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void disablingAHardPrerequisiteWithdrawsItsDependentsInTheSamePublication() {
    enable(new Offer("alpha"), new Dependent());
    ClientSession holder = connect("c1", "alpha", "dependent");
    holder.recordCapVersion(302);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (SessionWriter writer = new SessionWriter(holder, out, TagRenderer.NONE)) {
      holder.attachWriter(writer);
      registry.disable("alpha");
    }
    assertThat(lines(out)).containsExactly(":irc.example CAP * DEL :alpha dependent");
    assertThat(holder.negotiatedCapabilities()).isEmpty();
    assertThat(registry.stateOf("dependent")).isEqualTo(Extension.State.DISABLED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();

    // Enabling the prerequisite again does not silently bring the dependent back.
    registry.enable("alpha");
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("alpha");
  }

  @Test
  void transitiveDependentsAreStoppedBeforeWhatTheyRequire() {
    List<String> stops = java.util.Collections.synchronizedList(new ArrayList<>());
    // Names sort against dependency order, so only a dependency-aware order passes.
    enable(
        new Chained("root", null, stops),
        new Chained("a", "root", stops),
        new Chained("z", "a", stops));
    registry.disable("root");
    assertThat(stops).containsExactly("z", "a", "root");
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void anEnableWithAMissingHardPrerequisiteIsRejectedAndPublishesNothing() {
    registry.register(new Offer("alpha"));
    registry.register(new Dependent());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.enable("dependent"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha");
    // Like every other rejected enable, it is recorded FAILED until enabled again explicitly.
    assertThat(registry.stateOf("dependent")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void startAndStopRunOutsideTheAdministrativeMutex() {
    Probe probe = new Probe();
    probe.runtime.set(runtime);
    enable(probe);
    registry.disable("probe");
    assertThat(probe.startSawFreeMutex).isTrue();
    assertThat(probe.stopSawFreeMutex).isTrue();
  }

  @Test
  void aRegistrationIsEffectiveOnlyWhilePublishedAndItsOwnerAndPrerequisitesAreNegotiated() {
    Framer framer = new Framer();
    enable(framer, new Offer("tags"));
    HookRegistration owner = registry.capabilitySnapshot().hooks(HookKind.OUTPUT_GROUP).getFirst();
    RuntimeSnapshot published = registry.capabilitySnapshot();

    assertThat(published.effective(owner, Set.of("framer", "tags"))).isTrue();
    assertThat(published.effective(owner, Set.of("tags"))).isFalse();
    assertThat(published.effective(owner, Set.of("framer"))).isFalse();
    registry.disable("framer");
    assertThat(registry.capabilitySnapshot().effective(owner, Set.of("framer", "tags"))).isFalse();
  }
}
