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
package net.jircd.capabilities.userhostinnames;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jircd.core.extension.capability.CapabilityDescriptor;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.FaultCategory;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.MemberSnapshot;
import net.jircd.core.extension.capability.ReplyBudgetCatalog;
import net.jircd.core.extension.capability.ReplyLayout;
import net.jircd.core.extension.capability.ReplyLimits;
import net.jircd.core.extension.capability.ReplySlot;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import org.junit.jupiter.api.Test;

class UserhostInNamesExtensionTest {
    private static final String CAP = "userhost-in-names";

    private static CapabilityDescriptor descriptor() {
        UserhostInNamesExtension extension = new UserhostInNamesExtension();
        assertThat(extension.id()).isEqualTo(CAP);
        CapabilityRegistrar registrar = new CapabilityRegistrar(extension.id(), extension.id());
        extension.register(registrar);
        return registrar.build();
    }

    @Test
    void ownsOnlyIdentityAndBoundsAdditionalUtf8BytesUsingProvidedLimits() {
        var descriptor = descriptor();
        assertThat(descriptor.requiresProviders()).isEmpty();
        assertThat(descriptor.registrations())
                .singleElement()
                .satisfies(
                        hook -> {
                            assertThat(hook.kind()).isEqualTo(HookKind.NAMES_IDENTITY);
                            assertThat(hook.slot()).isEqualTo(ReplySlot.IDENTITY);
                            assertThat(hook.replyBound().maxBytes(ReplyLimits.REFERENCE))
                                    .isEqualTo(92);
                            assertThat(hook.replyBound().maxBytes(new ReplyLimits(1, 2, 3, 7, 11)))
                                    .isEqualTo(20);
                            assertThat(
                                            hook.replyBound()
                                                    .maxBytes(
                                                            new ReplyLimits(
                                                                    1,
                                                                    2,
                                                                    3,
                                                                    Integer.MAX_VALUE,
                                                                    Integer.MAX_VALUE)))
                                    .isEqualTo(4294967296L);
                        });
    }

    @Test
    void rendersOnlyPermittedIdentityIndependentlyOfStatusAndNegotiation() {
        HookDispatcher dispatcher =
                new HookDispatcher(
                        (provider, kind, category) -> {
                            throw new AssertionError(provider + "/" + kind + "/" + category);
                        });
        RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY.with(descriptor());
        MemberSnapshot member =
                new MemberSnapshot("nick", "界".repeat(9), "é".repeat(31) + "x", List.of("@", "+"));
        assertThat(dispatcher.namesIdentity(snapshot, Set.of(CAP), member))
                .isEqualTo("nick!" + member.ident() + "@" + member.displayHost());
        assertThat(dispatcher.namesIdentity(snapshot, Set.of(), member)).isEqualTo("nick");
        assertThat(dispatcher.memberStatus(snapshot, Set.of(CAP), member)).isEqualTo("@");
        assertThat(
                        ReplyBudgetCatalog.fromDescriptors(List.of(descriptor()))
                                .worstCaseBytes(ReplyLayout.WHO_ENTRY, ReplyLimits.REFERENCE))
                .isEqualTo(
                        ReplyBudgetCatalog.empty()
                                .worstCaseBytes(ReplyLayout.WHO_ENTRY, ReplyLimits.REFERENCE));
    }

    @Test
    void invalidIdentityFactsFallBackWithoutEmittingMalformedOrOverBoundHostmasks() {
        List<FaultCategory> faults = new ArrayList<>();
        HookDispatcher dispatcher =
                new HookDispatcher((provider, kind, category) -> faults.add(category));
        RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY.with(descriptor());
        // Upstream facts are normally validated/bounded; the SPI must still reject unsafe output.
        List<MemberSnapshot> invalid =
                List.of(
                        new MemberSnapshot("nick", "ident", "bad\u007fhost", List.of()),
                        new MemberSnapshot("nick", "bad ident", "host", List.of()),
                        new MemberSnapshot("nick", "界".repeat(10), "h".repeat(63), List.of()));
        for (MemberSnapshot member : invalid) {
            assertThat(dispatcher.namesIdentity(snapshot, Set.of(CAP), member)).isEqualTo("nick");
        }
        assertThat(faults)
                .containsExactly(
                        FaultCategory.INVALID_RESULT,
                        FaultCategory.INVALID_RESULT,
                        FaultCategory.INVALID_RESULT);
    }

    @Test
    void unsafeConfiguredDeclarationIsRejectedBeforeIdentityRendering() {
        List<FaultCategory> faults = new ArrayList<>();
        HookDispatcher dispatcher =
                new HookDispatcher((provider, kind, category) -> faults.add(category));
        RuntimeSnapshot snapshot =
                RuntimeSnapshot.EMPTY
                        .with(descriptor())
                        .withLimits(new ReplyLimits(16, 9, 50, 27, 600));
        MemberSnapshot member = new MemberSnapshot("nick", "ident", "host", List.of());
        assertThat(dispatcher.namesIdentity(snapshot, Set.of(CAP), member)).isEqualTo("nick");
        assertThat(faults).containsExactly(FaultCategory.INVALID_RESULT);
    }
}
