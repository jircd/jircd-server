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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jircd.protocol.Command;
import org.junit.jupiter.api.Test;

class CapabilityRegistrarTest {

  private static CapabilityRegistrar registrar(String id) {
    return new CapabilityRegistrar(id, id);
  }

  private static CapabilityDescriptor withTagHook(String id, String key) {
    CapabilityRegistrar registrar = registrar(id);
    registrar.onOutboundTags(Set.of(key), 64, message -> Map.of(key, "v"));
    return registrar.build();
  }

  @Test
  void rejectsEmptyOrUnsafeIdsAndOffers() {
    assertThatThrownBy(() -> new CapabilityRegistrar("", "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CapabilityRegistrar("a", "has space"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CapabilityRegistrar("a", "x=y"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CapabilityRegistrar(null, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    String longName = "x".repeat(65);
    assertThatThrownBy(() -> new CapabilityRegistrar("a", longName))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reservesCoreCapNotify() {
    assertThatThrownBy(() -> new CapabilityRegistrar("cap-notify", "cap-notify"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cap-notify");
    assertThatThrownBy(() -> new CapabilityRegistrar("mine", "cap-notify"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void snapshotRejectsDuplicateProviderIdsAndOffers() {
    CapabilityDescriptor first = withTagHook("alpha", "a");
    CapabilityRegistrar sameOffer = new CapabilityRegistrar("beta", "alpha");
    List<CapabilityDescriptor> descriptors1 = List.of(first, first);
    assertThatThrownBy(() -> RuntimeSnapshot.of(descriptors1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha");
    CapabilityDescriptor duplicateOffer = sameOffer.build();
    List<CapabilityDescriptor> descriptors2 = List.of(first, duplicateOffer);
    assertThatThrownBy(() -> RuntimeSnapshot.of(descriptors2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("alpha");
  }

  @Test
  void descriptorIsImmutableAndStagedRegistrationsAreFrozenAfterBuild() {
    CapabilityRegistrar registrar = registrar("alpha");
    registrar.onOutboundTags(Set.of("a"), 64, message -> Map.of("a", "v"));
    CapabilityDescriptor descriptor = registrar.build();
    List<HookRegistration> registrations = descriptor.registrations();
    HookRegistration first = registrations.getFirst();
    assertThatThrownBy(() -> registrations.add(first))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> registrar.onEvent(EventKind.JOINED, event -> List.of()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(registrar::build).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> registrar.requiresProviders("other"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void hardDependenciesRejectSelfDuplicateUnknownAndCyclicDeclarations() {
    CapabilityRegistrar self = registrar("alpha");
    assertThatThrownBy(() -> self.requiresProviders("alpha"))
        .isInstanceOf(IllegalArgumentException.class);
    CapabilityRegistrar duplicate = registrar("alpha");
    assertThatThrownBy(() -> duplicate.requiresProviders("beta", "beta"))
        .isInstanceOf(IllegalArgumentException.class);

    CapabilityRegistrar needsMissing = registrar("alpha");
    needsMissing.requiresProviders("missing");
    CapabilityDescriptor unknown = needsMissing.build();
    List<CapabilityDescriptor> descriptors3 = List.of(unknown);
    assertThatThrownBy(() -> RuntimeSnapshot.of(descriptors3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing");

    CapabilityRegistrar a = registrar("a");
    a.requiresProviders("b");
    CapabilityRegistrar b = registrar("b");
    b.requiresProviders("a");
    CapabilityDescriptor da = a.build();
    CapabilityDescriptor db = b.build();
    List<CapabilityDescriptor> descriptors4 = List.of(da, db);
    assertThatThrownBy(() -> RuntimeSnapshot.of(descriptors4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cyclic");
    assertThat(da.requiresProviders()).containsExactly("b");
  }

  @Test
  void rejectsServerTagKeyConflictsBetweenProviders() {
    CapabilityDescriptor first = withTagHook("alpha", "shared");
    CapabilityDescriptor second = withTagHook("beta", "shared");
    List<CapabilityDescriptor> descriptors5 = List.of(first, second);
    assertThatThrownBy(() -> RuntimeSnapshot.of(descriptors5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shared");
  }

  @Test
  void rejectsReplacementSlotConflictsBetweenProviders() {
    CapabilityRegistrar first = registrar("alpha");
    first.onMemberStatus(limits -> 2, member -> "");
    CapabilityRegistrar second = registrar("beta");
    second.onMemberStatus(limits -> 2, member -> "");
    CapabilityDescriptor a = first.build();
    CapabilityDescriptor b = second.build();
    List<CapabilityDescriptor> descriptors6 = List.of(a, b);
    assertThatThrownBy(() -> RuntimeSnapshot.of(descriptors6))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("STATUS");
  }

  @Test
  void rejectsSlotConflictInsideOneProvider() {
    CapabilityRegistrar registrar = registrar("alpha");
    registrar.onMemberStatus(limits -> 2, member -> "");
    assertThatThrownBy(() -> registrar.onMemberStatus(limits -> 2, member -> ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTagContributionRegistrationsThatCannotBeBounded() {
    CapabilityRegistrar registrar = registrar("alpha");
    Set<String> one = Set.of("a");
    Hooks.OutboundTagHook none = m -> Map.of();
    assertThatThrownBy(() -> registrar.onOutboundTags(one, 0, none))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registrar.onOutboundTags(one, 4095, none))
        .isInstanceOf(IllegalArgumentException.class);
    Set<String> empty = Set.of();
    assertThatThrownBy(() -> registrar.onOutboundTags(empty, 8, none))
        .isInstanceOf(IllegalArgumentException.class);
    Set<String> clientOnly = Set.of("+client");
    assertThatThrownBy(() -> registrar.onOutboundTags(clientOnly, 8, none))
        .isInstanceOf(IllegalArgumentException.class);
    Set<String> batch = Set.of("batch");
    assertThatThrownBy(() -> registrar.onOutboundTags(batch, 8, none))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batch");
    assertThatThrownBy(() -> registrar.onOutboundTags(one, 8, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsReplyBoundsThatAreNullNegativeOrExceedTheCommandLimit() {
    CapabilityRegistrar registrar = registrar("alpha");
    assertThatThrownBy(() -> registrar.onMemberStatus(null, m -> ""))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registrar.onMemberStatus(limits -> -1, m -> ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registrar.onNamesIdentity(limits -> 513, m -> "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                registrar.onNamesIdentity(
                    limits -> {
                      throw new IllegalStateException("boom");
                    },
                    m -> "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void replyFieldSlotsMustBeDeclaredBySchema() {
    ReplySlot declared = new ReplySlot("extra");
    CapabilityRegistrar registrar = new CapabilityRegistrar("alpha", "alpha", Set.of(declared));
    ReplySlot other = new ReplySlot("other");
    assertThatThrownBy(() -> registrar.onReplyField(other, limits -> 1, member -> "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("other");
    registrar.onReplyField(declared, limits -> 1, member -> "x");
    assertThat(registrar.build().registrations()).hasSize(1);
  }

  @Test
  void rejectsMoreRegistrationsAndPrerequisitesThanTheMetadataBounds() {
    CapabilityRegistrar registrar = registrar("alpha");
    for (int i = 0; i < CapabilityRegistrar.MAX_REGISTRATIONS; i++) {
      registrar.onEvent(EventKind.JOINED, event -> List.of());
    }
    assertThatThrownBy(() -> registrar.onEvent(EventKind.PARTED, event -> List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("registrations");
    CapabilityRegistrar deps = registrar("beta");
    for (int i = 0; i < CapabilityRegistrar.MAX_REQUIRED_PROVIDERS; i++) {
      deps.requiresProviders("dep" + i);
    }
    assertThatThrownBy(() -> deps.requiresProviders("one-more"))
        .isInstanceOf(IllegalStateException.class);
    CapabilityRegistrar gates = registrar("gamma");
    Set<String> tooMany = new java.util.HashSet<>();
    for (int i = 0; i <= CapabilityRegistrar.MAX_PREREQUISITES; i++) tooMany.add("g" + i);
    assertThatThrownBy(() -> gates.onOutputGroup(tooMany, (group, ctx) -> null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void closingRegistrationsAndRegistrarIsIdempotentAndRollsBackStaging() {
    CapabilityRegistrar registrar = registrar("alpha");
    CapabilityDescriptor descriptor;
    try (Registration kept = registrar.onEvent(EventKind.JOINED, event -> List.of());
        Registration dropped = registrar.onEvent(EventKind.PARTED, event -> List.of())) {
      dropped.close();
      dropped.close();
      CapabilityRegistrar second = registrar("beta");
      second.onEvent(EventKind.JOINED, event -> List.of());
      second.close();
      second.close();
      assertThatThrownBy(second::build).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> second.onEvent(EventKind.JOINED, event -> List.of()))
          .isInstanceOf(IllegalStateException.class);
      descriptor = registrar.build();
      assertThat(descriptor.registrations()).hasSize(1);
      kept.close();
    }
    // Closing handles after publication never changes the frozen descriptor.
    assertThat(descriptor.registrations()).hasSize(1);
  }

  @Test
  void commandRegistrationsAreOwnedByTheProviderAndConflictsAreRejected() {
    CapabilityRegistrar first = registrar("alpha");
    first.command(Command.TAGMSG, (session, message) -> {});
    CapabilityRegistrar second = registrar("beta");
    second.command(Command.TAGMSG, (session, message) -> {});
    CapabilityDescriptor a = first.build();
    CapabilityDescriptor b = second.build();
    List<CapabilityDescriptor> descriptors7 = List.of(a, b);
    assertThatThrownBy(() -> RuntimeSnapshot.of(descriptors7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TAGMSG");
  }

  @Test
  void snapshotIndexesHooksDeterministicallyByProviderId() {
    CapabilityDescriptor b = withTagHook("beta", "b");
    CapabilityDescriptor a = withTagHook("alpha", "a");
    RuntimeSnapshot snapshot = RuntimeSnapshot.of(List.of(b, a));
    assertThat(snapshot.hooks(HookKind.OUTBOUND_TAGS))
        .extracting(HookRegistration::providerId)
        .containsExactly("alpha", "beta");
    assertThat(snapshot.without("alpha").hooks(HookKind.OUTBOUND_TAGS)).hasSize(1);
    assertThat(RuntimeSnapshot.EMPTY.hooks(HookKind.EVENT)).isEmpty();
  }

  @Test
  void aRejectedRequiresProvidersCallStagesNoneOfItsIds() {
    CapabilityRegistrar registrar = registrar("me");
    assertThatThrownBy(() -> registrar.requiresProviders("fine", "fine"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registrar.requiresProviders("fine", "me"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registrar.requiresProviders("fine", "bad id"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(registrar.build().requiresProviders()).isEmpty();
  }
}
