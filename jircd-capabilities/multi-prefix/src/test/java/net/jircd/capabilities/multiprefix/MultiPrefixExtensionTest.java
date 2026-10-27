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
package net.jircd.capabilities.multiprefix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRegistrar;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.extension.capability.MemberSnapshot;
import net.jircd.core.extension.capability.ReplyBudgetCatalog;
import net.jircd.core.extension.capability.ReplyLayout;
import net.jircd.core.extension.capability.ReplyLimits;
import net.jircd.core.extension.capability.ReplySlot;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.MemberReplies;
import net.jircd.core.session.RateLimitBucket;
import org.junit.jupiter.api.Test;

class MultiPrefixExtensionTest {
    private final List<String> faults = new ArrayList<>();
    private final HookDispatcher dispatcher =
            new HookDispatcher((provider, kind, category) -> faults.add(provider + "/" + kind));

    private static HookRegistration hook() {
        CapabilityRegistrar registrar = new CapabilityRegistrar("multi-prefix", "multi-prefix");
        new MultiPrefixExtension().register(registrar);
        List<HookRegistration> registrations = registrar.build().registrations();
        assertThat(registrations).hasSize(1);
        return registrations.getFirst();
    }

    private static MemberSnapshot member(String... statuses) {
        return new MemberSnapshot("nick", "ident", "host", List.of(statuses));
    }

    @Test
    void ownsTheStatusSlotAndDeclaresAnExactBoundForTheServerPrefix() {
        HookRegistration hook = hook();
        assertThat(hook.kind()).isEqualTo(HookKind.MEMBER_STATUS);
        assertThat(hook.slot()).isEqualTo(ReplySlot.STATUS);
        // PREFIX=(ov)@+ : the operator and voice symbols, one byte each.
        assertThat(hook.replyBound().maxBytes(ReplyLimits.REFERENCE)).isEqualTo(2);
    }

    @Test
    void listsEveryHeldStatusInPrefixOrder() {
        RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY.with(registrar());
        Set<String> negotiated = Set.of("multi-prefix");

        assertThat(dispatcher.memberStatus(snapshot, negotiated, member("@", "+"))).isEqualTo("@+");
        assertThat(dispatcher.memberStatus(snapshot, negotiated, member("@"))).isEqualTo("@");
        assertThat(dispatcher.memberStatus(snapshot, negotiated, member("+"))).isEqualTo("+");
        assertThat(dispatcher.memberStatus(snapshot, negotiated, member())).isEmpty();
        assertThat(faults).isEmpty();
    }

    @Test
    void aRequesterWithoutItGetsCoresHighestStatus() {
        RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY.with(registrar());
        assertThat(dispatcher.memberStatus(snapshot, Set.of(), member("@", "+"))).isEqualTo("@");
    }

    private static net.jircd.core.extension.capability.CapabilityDescriptor registrar() {
        CapabilityRegistrar registrar = new CapabilityRegistrar("multi-prefix", "multi-prefix");
        new MultiPrefixExtension().register(registrar);
        return registrar.build();
    }

    @Test
    void reservesTheWidthOfAllStatusesInNamesAndWhoLayouts() {
        ReplyBudgetCatalog catalog = ReplyBudgetCatalog.fromDescriptors(List.of(registrar()));
        ReplyLimits limits = ReplyLimits.REFERENCE;

        assertThat(catalog.slotBytes(limits)).containsEntry(ReplySlot.STATUS, 2L);
        assertThat(catalog.worstCaseBytes(ReplyLayout.NAMES_MEMBER, limits))
                .isEqualTo(
                        ReplyBudgetCatalog.empty().worstCaseBytes(ReplyLayout.NAMES_MEMBER, limits)
                                + 1);
        assertThat(catalog.worstCaseBytes(ReplyLayout.WHO_ENTRY, limits))
                .isEqualTo(
                        ReplyBudgetCatalog.empty().worstCaseBytes(ReplyLayout.WHO_ENTRY, limits)
                                + 1);
    }

    @Test
    void namesAndWhoThroughTheRenderingServiceUseTheProvider() {
        ExtensionRegistry extensions = new ExtensionRegistry();
        extensions.register(new MultiPrefixExtension());
        extensions.enable(MultiPrefixExtension.CAPABILITY_ID);
        MemberReplies replies = new MemberReplies(extensions);
        ClientSession operator = new ClientSession("o", "host", RateLimitBucket.withDefaults());
        operator.setNickname("op");
        Channel channel =
                new ChannelRegistry().join("#c", operator, ignored -> true).orElseThrow().channel();
        channel.grantVoice(operator);
        ClientSession requester = new ClientSession("r", "host", RateLimitBucket.withDefaults());
        requester.replaceNegotiatedCapabilities(List.of("multi-prefix"));

        assertThat(replies.namesTokens(requester, channel.memberFacts())).containsExactly("@+op");
        assertThat(replies.whoStatus(requester, channel.memberFacts(operator))).isEqualTo("@+");
    }
}
