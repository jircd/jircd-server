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
package net.jircd.core.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.jircd.core.capability.Capability;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.ReplyBudgetCatalog;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/** Staging, atomic publication, withdrawal and failure cleanup of capability hooks. */
class ExtensionRegistryCapabilityTest {

  private static final class Provider implements CapabilityExtension {
    final String id;
    final Consumer<CapabilityRegistrar> staging;
    final RuntimeException startFailure;
    volatile RuntimeException stopFailure;
    volatile Error startError;
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();

    Provider(String id, Consumer<CapabilityRegistrar> staging, RuntimeException startFailure) {
      this.id = id;
      this.staging = staging;
      this.startFailure = startFailure;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public void start(ServerContext context) {
      starts.incrementAndGet();
      if (startFailure != null) throw startFailure;
      if (startError != null) throw startError;
    }

    @Override
    public void stop() {
      stops.incrementAndGet();
      if (stopFailure != null) throw stopFailure;
    }

    @Override
    public Capability providedCapability() {
      return new Capability(id);
    }

    @Override
    public void register(CapabilityRegistrar registrar) {
      staging.accept(registrar);
    }
  }

  private static Provider tagProvider(String id, String key) {
    return new Provider(
        id, r -> r.onOutboundTags(Set.of(key), 16, message -> Map.of(key, "v")), null);
  }

  private final List<String> registeredCommands = new ArrayList<>();
  private final Map<Command, CommandHandler> handlers = new java.util.EnumMap<>(Command.class);
  private int failAfterRegistrations = Integer.MAX_VALUE;
  private boolean failUnregister;
  private Error unregisterError;
  private RuntimeException sharedUnregisterFailure;
  private Runnable onRegister = () -> {};

  /** Core's command table as a provider sees it: ownership query, install and removal. */
  private final CommandRegistrar commands =
      new CommandRegistrar() {
        @Override
        public void register(Command command, CommandHandler handler) {
          onRegister.run();
          if (handlers.size() >= failAfterRegistrations) throw new NoClassDefFoundError("x");
          registeredCommands.add(command.name());
          handlers.put(command, handler);
        }

        @Override
        public boolean isRegistered(Command command) {
          return handlers.containsKey(command);
        }

        @Override
        public void unregister(Command command, CommandHandler installed) {
          if (unregisterError != null) throw unregisterError;
          if (sharedUnregisterFailure != null) throw sharedUnregisterFailure;
          if (failUnregister) throw new IllegalStateException("cannot remove");
          handlers.remove(command, installed);
        }
      };

  private ExtensionRegistry registry(String serverName) {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.attachContext(
        new ServerContext(
            null,
            null,
            registry,
            () -> serverName,
            null,
            null,
            commands,
            registry.capabilityServices()));
    return registry;
  }

  @Test
  void enablePublishesTheHooksAndDisableWithdrawsThemBeforeStop() {
    ExtensionRegistry registry = registry("test.jircd.local");
    Provider provider = tagProvider("alpha", "a");
    registry.register(provider);
    registry.enable("alpha");
    assertThat(registry.capabilityServices().snapshot().providerIds()).containsExactly("alpha");
    assertThat(registry.capabilitySnapshot().hooks(HookKind.OUTBOUND_TAGS)).hasSize(1);
    registry.disable("alpha");
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    assertThat(provider.stops).hasValue(1);
    assertThat(registry.stateOf("alpha")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void aStopFailureAfterWithdrawalKeepsTheProviderWithdrawnAndRecordsItFailed() {
    ExtensionRegistry registry = registry("test.jircd.local");
    Provider provider = tagProvider("alpha", "a");
    registry.register(provider);
    registry.enable("alpha");
    provider.stopFailure = new IllegalStateException("stop failed");
    assertThatThrownBy(() -> registry.disable("alpha")).isInstanceOf(IllegalStateException.class);
    assertThat(registry.stateOf("alpha")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    provider.stopFailure = null;
    registry.enable("alpha");
    assertThat(registry.stateOf("alpha")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("alpha");
  }

  @Test
  void aProviderThatStagesNothingPublishesAnEmptyDescriptor() {
    ExtensionRegistry registry = registry("test.jircd.local");
    registry.register(new Provider("legacy", r -> {}, null));
    registry.enable("legacy");
    assertThat(registry.capabilitySnapshot().descriptor("legacy")).isPresent();
  }

  @Test
  void invalidStagingFailsTheProviderPublishesNothingAndStopsItsResources() {
    ExtensionRegistry registry = registry("test.jircd.local");
    Provider bad =
        new Provider(
            "bad",
            r -> {
              r.onOutboundTags(Set.of("a"), 16, m -> Map.of());
              r.onOutboundTags(Set.of("a"), 16, m -> Map.of()); // duplicate key
            },
            null);
    registry.register(bad);
    registry.register(tagProvider("good", "g"));
    assertThatThrownBy(() -> registry.enable("bad")).isInstanceOf(IllegalArgumentException.class);
    registry.enable("good");
    assertThat(registry.stateOf("bad")).isEqualTo(Extension.State.FAILED);
    assertThat(bad.stops).hasValue(1);
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("good");
  }

  @Test
  void aStartFailureStillCleansUpWhateverWasAcquiredBeforeItThrew() {
    ExtensionRegistry registry = registry("test.jircd.local");
    Provider broken = new Provider("broken", r -> {}, new IllegalStateException("no"));
    registry.register(broken);
    assertThatThrownBy(() -> registry.enable("broken")).isInstanceOf(IllegalStateException.class);
    assertThat(registry.stateOf("broken")).isEqualTo(Extension.State.FAILED);
    assertThat(broken.stops).hasValue(1);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void aLinkageErrorWhileStagingFailsTheProviderAndStopsItsResources() {
    ExtensionRegistry registry = registry("test.jircd.local");
    Provider broken =
        new Provider(
            "linked",
            r -> {
              throw new NoClassDefFoundError("missing");
            },
            null);
    registry.register(broken);
    assertThatThrownBy(() -> registry.enable("linked")).isInstanceOf(NoClassDefFoundError.class);
    assertThat(registry.stateOf("linked")).isEqualTo(Extension.State.FAILED);
    assertThat(broken.starts).hasValue(1);
    assertThat(broken.stops).hasValue(1);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void aConflictingProviderIsRejectedWithoutDisturbingTheEnabledOne() {
    ExtensionRegistry registry = registry("test.jircd.local");
    registry.register(tagProvider("first", "shared"));
    registry.register(tagProvider("second", "shared"));
    registry.enable("first");
    assertThatThrownBy(() -> registry.enable("second"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(registry.stateOf("first")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.stateOf("second")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("first");
  }

  @Test
  void disablingAProviderThatAnotherRequiresIsRefusedAndChangesNothing() {
    ExtensionRegistry registry = registry("test.jircd.local");
    registry.register(tagProvider("base", "b"));
    registry.register(new Provider("dependent", r -> r.requiresProviders("base"), null));
    registry.enable("base");
    registry.enable("dependent");
    assertThatThrownBy(() -> registry.disable("base")).isInstanceOf(IllegalStateException.class);
    assertThat(registry.stateOf("base")).isEqualTo(Extension.State.ENABLED);
    assertThat(registry.capabilitySnapshot().providerIds())
        .containsExactlyInAnyOrder("base", "dependent");
  }

  @Test
  void enablingWithoutItsRequiredProviderFails() {
    ExtensionRegistry registry = registry("test.jircd.local");
    registry.register(new Provider("dependent", r -> r.requiresProviders("base"), null));
    assertThatThrownBy(() -> registry.enable("dependent"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("base");
  }

  @Test
  void providerCommandsAreInstalledOnEnableAndRemovedOnDisable() {
    ExtensionRegistry registry = registry("test.jircd.local");
    AtomicInteger handled = new AtomicInteger();
    registry.register(
        new Provider(
            "cmd", r -> r.command(Command.TAGMSG, (s, m) -> handled.incrementAndGet()), null));
    registry.enable("cmd");
    assertThat(registeredCommands).containsExactly("TAGMSG");
    ClientSession session = new ClientSession("c", "host", RateLimitBucket.withDefaults());
    handlers.get(Command.TAGMSG).handle(session, Message.of(Command.TAGMSG, "#c"));
    assertThat(handled).hasValue(1);
    registry.disable("cmd");
    assertThat(handlers).doesNotContainKey(Command.TAGMSG);
    registry.enable("cmd");
    assertThat(handlers).containsKey(Command.TAGMSG);
  }

  @Test
  void aProviderCannotReplaceACommandCoreOrAnotherExtensionAlreadyOwns() {
    ExtensionRegistry registry = registry("test.jircd.local");
    CommandHandler core = (session, message) -> {};
    handlers.put(Command.JOIN, core);
    registry.register(new Provider("greedy", r -> r.command(Command.JOIN, (s, m) -> {}), null));
    assertThatThrownBy(() -> registry.enable("greedy"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JOIN");
    assertThat(handlers).containsEntry(Command.JOIN, core);
    assertThat(registry.stateOf("greedy")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    registry.disable("greedy");
    assertThat(handlers).containsEntry(Command.JOIN, core);
  }

  @Test
  void aFailureInstallingLaterCommandsRollsBackTheEarlierOnesAndThePublication() {
    ExtensionRegistry registry = registry("test.jircd.local");
    failAfterRegistrations = 1;
    registry.register(
        new Provider(
            "two",
            r -> {
              r.command(Command.TAGMSG, (s, m) -> {});
              r.command(Command.AWAY, (s, m) -> {});
            },
            null));
    assertThatThrownBy(() -> registry.enable("two")).isInstanceOf(NoClassDefFoundError.class);
    assertThat(handlers).isEmpty();
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    assertThat(registry.stateOf("two")).isEqualTo(Extension.State.FAILED);
  }

  @Test
  void aRegistrarMustSupportOwnershipAwareRemovalSoNoProviderHandlerOutlivesWithdrawal()
      throws Exception {
    assertThat(
            java.lang.reflect.Modifier.isAbstract(
                CommandRegistrar.class
                    .getMethod("unregister", Command.class, CommandHandler.class)
                    .getModifiers()))
        .isTrue();
    assertThat(
            java.lang.reflect.Modifier.isAbstract(
                CommandRegistrar.class.getMethod("isRegistered", Command.class).getModifiers()))
        .isTrue();
  }

  @Test
  void disablingAProviderLeavesAReplacementInstalledByAnotherExtensionAlone() {
    ExtensionRegistry registry = registry("test.jircd.local");
    registry.register(new Provider("early", r -> r.command(Command.OPER, (s, m) -> {}), null));
    registry.enable("early");
    CommandHandler replacement = (session, message) -> {};
    handlers.put(Command.OPER, replacement); // e.g. admin enabled later and took OPER over
    registry.disable("early");
    assertThat(handlers).containsEntry(Command.OPER, replacement);
  }

  @Test
  void anUnregisterFailureDuringRollbackStillWithdrawsThePublication() {
    ExtensionRegistry registry = registry("test.jircd.local");
    failAfterRegistrations = 1;
    failUnregister = true;
    registry.register(
        new Provider(
            "two",
            r -> {
              r.command(Command.TAGMSG, (s, m) -> {});
              r.command(Command.AWAY, (s, m) -> {});
            },
            null));
    assertThatThrownBy(() -> registry.enable("two")).isInstanceOf(NoClassDefFoundError.class);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    assertThat(registry.stateOf("two")).isEqualTo(Extension.State.FAILED);
  }

  @Test
  void anUnregisterFailureDuringDisableStillStopsAndFinalizesTheProvider() {
    ExtensionRegistry registry = registry("test.jircd.local");
    Provider provider =
        new Provider(
            "two",
            r -> {
              r.command(Command.TAGMSG, (s, m) -> {});
              r.command(Command.AWAY, (s, m) -> {});
            },
            null);
    registry.register(provider);
    registry.enable("two");
    failUnregister = true;
    assertThatThrownBy(() -> registry.disable("two"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot remove");
    assertThat(provider.stops).hasValue(1);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    assertThat(registry.stateOf("two")).isEqualTo(Extension.State.FAILED);
  }

  @Test
  void oneSharedExceptionFromUnregisterAndStopStillFinalizesTheProvider() {
    ExtensionRegistry registry = registry("test.jircd.local");
    IllegalStateException shared = new IllegalStateException("shared");
    Provider provider =
        new Provider(
            "two",
            r -> {
              r.command(Command.TAGMSG, (s, m) -> {});
              r.command(Command.AWAY, (s, m) -> {});
            },
            null);
    registry.register(provider);
    registry.enable("two");
    sharedUnregisterFailure = shared;
    provider.stopFailure = shared;
    assertThatThrownBy(() -> registry.disable("two")).isSameAs(shared);
    assertThat(provider.stops).hasValue(1);
    assertThat(registry.stateOf("two")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void aLinkageErrorFromUnregisterStillStopsAndFinalizesTheProvider() {
    ExtensionRegistry registry = registry("test.jircd.local");
    Provider provider = new Provider("one", r -> r.command(Command.TAGMSG, (s, m) -> {}), null);
    registry.register(provider);
    registry.enable("one");
    unregisterError = new NoClassDefFoundError("gone");
    assertThatThrownBy(() -> registry.disable("one")).isSameAs(unregisterError);
    assertThat(provider.stops).hasValue(1);
    assertThat(registry.stateOf("one")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void capabilityServicesExposeOnlyAReadOnlySnapshot() {
    ExtensionRegistry registry = registry("test.jircd.local");
    registry.register(tagProvider("alpha", "a"));
    registry.enable("alpha");
    var services = registry.capabilityServices();
    assertThat(services.snapshot().providerIds()).containsExactly("alpha");
    for (var method : services.getClass().getMethods()) {
      assertThat(method.getReturnType().getSimpleName()).isNotEqualTo("CapabilityRuntime");
    }
    for (var method : ExtensionRegistry.class.getMethods()) {
      assertThat(method.getReturnType().getSimpleName()).isNotEqualTo("CapabilityRuntime");
    }
  }

  @Test
  void duplicateProviderIdsAreRejectedAtRegistrationAndDiscoveryReportsThem(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
    ExtensionRegistry direct = new ExtensionRegistry();
    direct.register(tagProvider("same", "a"));
    Provider other = tagProvider("same", "b");
    assertThatThrownBy(() -> direct.register(other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("same");

    java.nio.file.Path services = dir.resolve("META-INF/services");
    java.nio.file.Files.createDirectories(services);
    java.nio.file.Files.writeString(
        services.resolve(CapabilityExtension.class.getName()),
        DupOne.class.getName()
            + "\n"
            + DupTwo.class.getName()
            + "\n"
            + Unique.class.getName()
            + "\n");
    ExtensionRegistry discovered = new ExtensionRegistry();
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ExtensionRegistry.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try (var loader =
        new java.net.URLClassLoader(
            new java.net.URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
      discovered.discover(loader);
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list)
        .anySatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .contains("DupTwo")
                    .contains("Duplicate extension id: dup"));
    assertThat(discovered.all())
        .extracting(Extension::id)
        .containsExactlyInAnyOrder("dup", "unique");
    assertThat(discovered.find("dup").orElseThrow()).isInstanceOf(DupOne.class);
  }

  public static class DupOne extends Named {
    public DupOne() {
      super("dup");
    }
  }

  public static class DupTwo extends Named {
    public DupTwo() {
      super("dup");
    }
  }

  public static class Unique extends Named {
    public Unique() {
      super("unique");
    }
  }

  public abstract static class Named implements CapabilityExtension {
    private final String id;

    Named(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public void start(ServerContext context) {
      // No resources.
    }

    @Override
    public void stop() {
      // No resources.
    }

    @Override
    public Capability providedCapability() {
      return new Capability(id);
    }
  }

  @Test
  void aFaultingProviderCommandIsContainedAtTheCoreBoundary() {
    ExtensionRegistry registry = registry("test.jircd.local");
    CommandHandler failing =
        (session, message) -> {
          throw new IllegalStateException("boom");
        };
    registry.register(new Provider("cmd", r -> r.command(Command.TAGMSG, failing), null));
    registry.enable("cmd");
    ClientSession session = new ClientSession("c", "host", RateLimitBucket.withDefaults());
    CommandHandler registered = handlers.get(Command.TAGMSG);
    Message tagmsg = Message.of(Command.TAGMSG, "#c");
    assertThatCode(() -> registered.handle(session, tagmsg)).doesNotThrowAnyException();
  }

  @Test
  void aRejectedCommandRegistrationLeavesNothingPublished() {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.attachContext(
        new ServerContext(
            null,
            null,
            registry,
            () -> "test.jircd.local",
            null,
            null,
            new CommandRegistrar() {
              @Override
              public void register(Command command, CommandHandler handler) {
                throw new IllegalStateException("taken");
              }

              @Override
              public boolean isRegistered(Command command) {
                return false;
              }

              @Override
              public void unregister(Command command, CommandHandler installed) {
                // Nothing was installed.
              }
            },
            registry.capabilityServices()));
    registry.register(new Provider("cmd", r -> r.command(Command.TAGMSG, (s, m) -> {}), null));
    assertThatThrownBy(() -> registry.enable("cmd")).isInstanceOf(IllegalStateException.class);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
    assertThat(registry.stateOf("cmd")).isEqualTo(Extension.State.FAILED);
  }

  @Test
  void enableRejectsDecorationsThatWouldOverflowTheConfiguredReplyLimits() {
    // 353 with the legacy 2-byte status already needs S+2N+C+15; this provider adds 300 identity
    // bytes.
    ExtensionRegistry registry = registry("s".repeat(200) + ".net");
    registry.register(
        new Provider("wide", r -> r.onNamesIdentity(limits -> 300, m -> m.nickname()), null));
    assertThatThrownBy(() -> registry.enable("wide"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("353");
    assertThat(registry.stateOf("wide")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void discoveredCatalogCoversDisabledProvidersAndSkipsOnesThatCannotStage() {
    ExtensionRegistry registry = registry("test.jircd.local");
    registry.register(new Provider("decorates", r -> r.onMemberStatus(limits -> 2, m -> ""), null));
    registry.register(
        new Provider(
            "broken",
            r -> {
              throw new IllegalStateException("cannot stage");
            },
            null));
    ReplyBudgetCatalog catalog = registry.discoveredReplyBudgetCatalog();
    assertThat(catalog.contributions()).hasSize(1);
    assertThat(registry.stateOf("decorates")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void stagingWithoutServerContextStillWorksForUnitSetups() {
    ExtensionRegistry registry = new ExtensionRegistry();
    registry.register(tagProvider("alpha", "a"));
    registry.enable("alpha");
    assertThat(registry.capabilitySnapshot().providerIds()).containsExactly("alpha");
  }

  // ---- Sourcery review of PR #104

  @Test
  void aLeftoverHandlerRefusesToRunAndIsRemovedByRetryingTheDisable() {
    ExtensionRegistry registry = registry("test.jircd.local");
    AtomicInteger handled = new AtomicInteger();
    registry.register(
        new Provider(
            "cmd", r -> r.command(Command.TAGMSG, (s, m) -> handled.incrementAndGet()), null));
    registry.enable("cmd");
    failUnregister = true;
    assertThatThrownBy(() -> registry.disable("cmd")).isInstanceOf(IllegalStateException.class);
    ClientSession session = new ClientSession("c", "host", RateLimitBucket.withDefaults());
    Message tagmsg = Message.of(Command.TAGMSG, "#c");
    assertThat(handlers).containsKey(Command.TAGMSG);
    handlers.get(Command.TAGMSG).handle(session, tagmsg);
    assertThat(handled).hasValue(0);
    failUnregister = false;
    registry.disable("cmd");
    assertThat(handlers).doesNotContainKey(Command.TAGMSG);
    assertThat(registry.stateOf("cmd")).isEqualTo(Extension.State.DISABLED);
    registry.enable("cmd");
    handlers.get(Command.TAGMSG).handle(session, tagmsg);
    assertThat(handled).hasValue(1);
  }

  @Test
  void aFailedRollbackKeepsTheLeftoverForRetryAndItsHandlerRefusesToRun() {
    ExtensionRegistry registry = registry("test.jircd.local");
    AtomicInteger handled = new AtomicInteger();
    failAfterRegistrations = 1;
    failUnregister = true;
    registry.register(
        new Provider(
            "two",
            r -> {
              r.command(Command.TAGMSG, (s, m) -> handled.incrementAndGet());
              r.command(Command.AWAY, (s, m) -> {});
            },
            null));
    assertThatThrownBy(() -> registry.enable("two")).isInstanceOf(NoClassDefFoundError.class);
    ClientSession session = new ClientSession("c", "host", RateLimitBucket.withDefaults());
    assertThat(handlers).containsKey(Command.TAGMSG);
    handlers.get(Command.TAGMSG).handle(session, Message.of(Command.TAGMSG, "#c"));
    assertThat(handled).hasValue(0);
    failUnregister = false;
    registry.disable("two");
    assertThat(handlers).isEmpty();
    assertThat(registry.stateOf("two")).isEqualTo(Extension.State.DISABLED);
  }

  @Test
  void hooksAreNeverVisibleBeforeTheCommandsAreInstalledNorAfterAFailedEnable() {
    ExtensionRegistry registry = registry("test.jircd.local");
    List<Set<String>> seen = new ArrayList<>();
    onRegister = () -> seen.add(registry.capabilitySnapshot().providerIds());
    failAfterRegistrations = 1;
    registry.register(
        new Provider(
            "two",
            r -> {
              r.onOutboundTags(Set.of("a"), 16, message -> Map.of("a", "v"));
              r.command(Command.TAGMSG, (s, m) -> {});
              r.command(Command.AWAY, (s, m) -> {});
            },
            null));
    assertThatThrownBy(() -> registry.enable("two")).isInstanceOf(NoClassDefFoundError.class);
    assertThat(seen).isNotEmpty().allSatisfy(ids -> assertThat(ids).isEmpty());
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  @Test
  void providerCallbacksRunOutsideTheRegistryMonitor() {
    ExtensionRegistry registry = registry("test.jircd.local");
    boolean[] otherThreadFinished = {false};
    String[] readResult = {null};
    registry.register(
        new CapabilityExtension() {
          @Override
          public String id() {
            return "blocker";
          }

          @Override
          public void start(ServerContext context) {
            CountDownLatch done = new CountDownLatch(1);
            Thread other =
                new Thread(
                    () -> {
                      synchronized (registry) {
                        readResult[0] =
                            registry.enabled().size() + "/" + registry.stateOf("blocker");
                        done.countDown();
                      }
                    });
            other.start();
            try {
              otherThreadFinished[0] = done.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
          }

          @Override
          public void stop() {
            // No resources.
          }

          @Override
          public Capability providedCapability() {
            return new Capability("blocker");
          }
        });
    registry.enable("blocker");
    assertThat(otherThreadFinished[0]).isTrue();
    assertThat(readResult[0]).isNotNull();
  }

  @Test
  void anyErrorFromStartOrStagingStillStopsTheProviderAndRecordsItFailed() {
    ExtensionRegistry registry = registry("test.jircd.local");
    Provider failingStart = new Provider("start", r -> {}, null);
    failingStart.startError = new AssertionError("start");
    Provider failingStaging =
        new Provider(
            "stage",
            r -> {
              throw new AssertionError("stage");
            },
            null);
    registry.register(failingStart);
    registry.register(failingStaging);
    assertThatThrownBy(() -> registry.enable("start")).isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> registry.enable("stage")).isInstanceOf(AssertionError.class);
    assertThat(failingStart.stops).hasValue(1);
    assertThat(failingStaging.stops).hasValue(1);
    assertThat(registry.stateOf("start")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.stateOf("stage")).isEqualTo(Extension.State.FAILED);
    assertThat(registry.capabilitySnapshot().providerIds()).isEmpty();
  }

  private static final class PointOwner implements ServerExtension {
    private final String id;
    volatile RuntimeException stopFailure;
    volatile RuntimeException startFailure;

    PointOwner(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String extensionPoint() {
      return "shared-point";
    }

    @Override
    public void start(ServerContext context) {
      if (startFailure != null) throw startFailure;
    }

    @Override
    public void stop() {
      if (stopFailure != null) throw stopFailure;
    }
  }

  @Test
  void aFailedStopKeepsTheExtensionPointClaimUntilARetriedDisableSucceeds() {
    ExtensionRegistry registry = registry("test.jircd.local");
    PointOwner first = new PointOwner("first");
    registry.register(first);
    registry.register(new PointOwner("second"));
    registry.enable("first");
    first.stopFailure = new IllegalStateException("stuck");
    assertThatThrownBy(() -> registry.disable("first")).isSameAs(first.stopFailure);
    assertThat(registry.stateOf("first")).isEqualTo(Extension.State.FAILED);
    assertThatThrownBy(() -> registry.enable("second"))
        .isInstanceOf(ExtensionRegistry.ExtensionPointConflictException.class);
    first.stopFailure = null;
    registry.disable("first");
    assertThat(registry.stateOf("first")).isEqualTo(Extension.State.DISABLED);
    registry.enable("second");
    assertThat(registry.stateOf("second")).isEqualTo(Extension.State.ENABLED);
  }

  @Test
  void aFailedReEnableAfterAFailedStopFinishesThePendingCleanupAndReleasesTheClaim() {
    ExtensionRegistry registry = registry("test.jircd.local");
    PointOwner first = new PointOwner("first");
    registry.register(first);
    registry.register(new PointOwner("second"));
    registry.enable("first");
    first.stopFailure = new IllegalStateException("stuck");
    assertThatThrownBy(() -> registry.disable("first")).isSameAs(first.stopFailure);
    first.stopFailure = null;
    first.startFailure = new IllegalStateException("will not start");
    assertThatThrownBy(() -> registry.enable("first")).isSameAs(first.startFailure);
    assertThat(registry.stateOf("first")).isEqualTo(Extension.State.FAILED);
    registry.enable("second");
    assertThat(registry.stateOf("second")).isEqualTo(Extension.State.ENABLED);
  }

  @Test
  void aProviderCallbackCannotReEnterTheLifecycle() {
    ExtensionRegistry registry = registry("test.jircd.local");
    registry.register(tagProvider("other", "o"));
    java.util.concurrent.atomic.AtomicReference<Throwable> enableAttempt =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<Throwable> disableAttempt =
        new java.util.concurrent.atomic.AtomicReference<>();
    registry.register(
        new CapabilityExtension() {
          @Override
          public String id() {
            return "reentrant";
          }

          @Override
          public void start(ServerContext context) {
            enableAttempt.set(catchThrowable(() -> registry.enable("other")));
            disableAttempt.set(catchThrowable(() -> registry.disable("other")));
          }

          @Override
          public void stop() {
            // No resources.
          }

          @Override
          public Capability providedCapability() {
            return new Capability("reentrant");
          }
        });
    registry.enable("reentrant");
    assertThat(enableAttempt.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("lifecycle");
    assertThat(disableAttempt.get()).isInstanceOf(IllegalStateException.class);
    assertThat(registry.stateOf("other")).isEqualTo(Extension.State.DISABLED);
  }
}
