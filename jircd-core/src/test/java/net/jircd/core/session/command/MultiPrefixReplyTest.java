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
package net.jircd.core.session.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

/**
 * {@code multi-prefix}: 353 and 352 list every status the member holds, in ISUPPORT PREFIX order,
 * only to a requester that negotiated it. The choice follows the requester, not the member listed.
 */
class MultiPrefixReplyTest {

    private static final String SERVER = "irc.test";

    private final HandlerHarness harness = new HandlerHarness();
    private final ChannelRegistry channels = new ChannelRegistry();
    private final ExtensionRegistry extensions = new ExtensionRegistry();
    private final ClientSession boss = harness.register("boss");
    private final ClientSession plain = harness.register("plain");
    private final ClientSession viewer = harness.register("viewer");
    private final Channel channel =
            channels.join("#c", boss, ignored -> true).orElseThrow().channel();

    MultiPrefixReplyTest() {
        MultiPrefixStub.enableIn(extensions);
        // boss is the first joiner, so already an operator; plain stays unprivileged.
        channel.grantVoice(boss);
        channels.join("#c", plain, ignored -> true);
        channels.join("#c", viewer, ignored -> true);
        channel.grantVoice(viewer);
    }

    private static void negotiateMultiPrefix(ClientSession session) {
        session.replaceNegotiatedCapabilities(List.of(CapabilityName.MULTI_PREFIX));
    }

    private List<Message> names(ClientSession requester) throws Exception {
        return HandlerHarness.run(
                new NamesCommandHandler(channels, extensions, () -> SERVER), requester, "NAMES #c");
    }

    private List<Message> who(ClientSession requester) throws Exception {
        return HandlerHarness.run(
                new WhoCommandHandler(
                        channels, harness.nicknames, extensions, () -> SERVER, () -> true),
                requester,
                "WHO #c");
    }

    private static List<String> listed(List<Message> replies, String requester) {
        Message namreply =
                replies.stream().filter(m -> "353".equals(m.rawCommand())).findFirst().get();
        assertThat(namreply.params().subList(0, 3)).containsExactly(requester, "=", "#c");
        return Arrays.asList(namreply.params().get(3).split(" "));
    }

    private static String whoFlags(List<Message> replies, String nick) {
        List<Message> match =
                replies.stream()
                        .filter(m -> "352".equals(m.rawCommand()) && nick.equals(m.params().get(5)))
                        .toList();
        assertThat(match).hasSize(1);
        return match.getFirst().params().get(6);
    }

    @Test
    void namesWithoutTheCapabilityKeepsOnlyTheHighestPrefix() throws Exception {
        assertThat(listed(names(viewer), "viewer"))
                .containsExactlyInAnyOrder("@boss", "plain", "+viewer");
    }

    @Test
    void namesWithTheCapabilityListsEveryPrefixOperatorFirst() throws Exception {
        negotiateMultiPrefix(viewer);

        assertThat(listed(names(viewer), "viewer"))
                .containsExactlyInAnyOrder("@+boss", "plain", "+viewer");
    }

    @Test
    void namesFollowsTheRequesterNotTheMemberListed() throws Exception {
        negotiateMultiPrefix(boss);

        // boss negotiated it; viewer did not, so viewer still sees one prefix for boss.
        assertThat(listed(names(viewer), "viewer")).contains("@boss");
        assertThat(listed(names(boss), "boss")).contains("@+boss");
    }

    @Test
    void bareNamesAppliesTheCapabilityToo() throws Exception {
        negotiateMultiPrefix(viewer);

        List<Message> replies =
                HandlerHarness.run(
                        new NamesCommandHandler(channels, extensions, () -> SERVER),
                        viewer,
                        "NAMES");

        assertThat(listed(replies, "viewer")).contains("@+boss");
    }

    @Test
    void joinListsEveryPrefixToAJoinerWithTheCapability() throws Exception {
        ClientSession joiner = harness.register("joiner");
        negotiateMultiPrefix(joiner);

        List<Message> replies =
                HandlerHarness.run(
                        new JoinCommandHandler(channels, extensions, () -> SERVER, () -> 50),
                        joiner,
                        "JOIN #c");

        Message namreply =
                replies.stream().filter(m -> "353".equals(m.rawCommand())).findFirst().get();
        assertThat(namreply.params().subList(0, 3)).containsExactly("joiner", "=", "#c");
        assertThat(Arrays.asList(namreply.params().get(3).split(" ")))
                .containsExactlyInAnyOrder("@+boss", "plain", "+viewer", "joiner");
    }

    @Test
    void joinKeepsTheSinglePrefixForAJoinerWithoutTheCapability() throws Exception {
        ClientSession joiner = harness.register("joiner");

        List<Message> replies =
                HandlerHarness.run(
                        new JoinCommandHandler(channels, extensions, () -> SERVER, () -> 50),
                        joiner,
                        "JOIN #c");

        Message namreply =
                replies.stream().filter(m -> "353".equals(m.rawCommand())).findFirst().get();
        assertThat(Arrays.asList(namreply.params().get(3).split(" ")))
                .containsExactlyInAnyOrder("@boss", "plain", "+viewer", "joiner");
    }

    @Test
    void whoWithoutTheCapabilityFlagsOnlyTheHighestPrefix() throws Exception {
        List<Message> replies = who(viewer);

        assertThat(whoFlags(replies, "boss")).isEqualTo("H@");
        assertThat(whoFlags(replies, "plain")).isEqualTo("H");
        assertThat(whoFlags(replies, "viewer")).isEqualTo("H+");
    }

    @Test
    void whoWithTheCapabilityFlagsEveryPrefixOperatorFirst() throws Exception {
        negotiateMultiPrefix(viewer);

        List<Message> replies = who(viewer);

        assertThat(whoFlags(replies, "boss")).isEqualTo("H@+");
        assertThat(whoFlags(replies, "plain")).isEqualTo("H");
        assertThat(whoFlags(replies, "viewer")).isEqualTo("H+");
    }

    @Test
    void whoPlacesTheExtraPrefixAfterTheOperatorFlagAndAwayMarker() throws Exception {
        negotiateMultiPrefix(viewer);
        boss.setAwayReason("gone");

        assertThat(whoFlags(who(viewer), "boss")).isEqualTo("G@+");
    }
}
