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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.jircd.core.capability.Capability;
import net.jircd.core.extension.AbstractCapabilityExtension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.CapabilityTagRenderer;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.MemberReplies;
import net.jircd.core.session.MessageDelivery;
import net.jircd.core.session.NicknameRegistry;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.core.session.SessionWriter;
import net.jircd.core.session.command.AwayCommandHandler;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A capability that exists nowhere in core, built only from the registrations the six bundled
 * modules already use, composes with them without a change to core: a new capability name needs no
 * new branch. The test provider stands in for the four features the design maps onto this SPI.
 *
 * <p>Where each future feature attaches, and the domain work that stays outside this change:
 *
 * <table>
 *   <caption>Seams of the features this SPI is designed to carry</caption>
 *   <tr><th>Feature</th><th>Seam exercised here</th><th>Remaining domain work</th></tr>
 *   <tr><td>account-notify</td><td>committed event plus the event's audience and recipient
 *       negotiation (an {@code ACCOUNT_CHANGED} kind added where account state commits)</td>
 *       <td>account and authentication state, and the real publisher; no event is faked</td></tr>
 *   <tr><td>chathistory</td><td>provider command, finite group with a session prerequisite,
 *       historical envelope</td><td>storage, query, authorization, paging and the history batch
 *       type; never an unbounded stream</td></tr>
 *   <tr><td>labeled-response / label</td><td>inbound tag into a bounded request context, a
 *       sender-copy role that sees it, group framing</td><td>the label wire rules, one
 *       acknowledgement when an echo is the response, no labels on unsolicited peer traffic</td></tr>
 *   <tr><td>userhost-in-names</td><td>NAMES identity slot, independent of the status slot, with
 *       its own byte bound</td><td>the {@code !ident@host} wire form and its privacy and bound
 *       policy</td></tr>
 * </table>
 */
class CapabilityCompositionTest {

    /** A made-up capability using events, an identity slot, request context, roles and groups. */
    private static final class Experimental extends AbstractCapabilityExtension {
        final AtomicReference<RequestContext> contextSeenByRoles = new AtomicReference<>();

        Experimental() {
            super("experimental");
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            // account-notify seam: a committed event answered with a notice to the audience.
            registrar.onEvent(
                    EventKind.AWAY_CHANGED,
                    event ->
                            List.of(
                                    new EventIntent(
                                            new Message(
                                                    Map.of(),
                                                    event.subject(),
                                                    Command.NOTICE,
                                                    "NOTICE",
                                                    List.of("x", "seen")),
                                            event.audience().peers())));
            // userhost-in-names seam: its own slot, composing with another provider's status slot.
            registrar.onNamesIdentity(
                    limits -> 200,
                    member ->
                            member.nickname() + "!" + member.ident() + "@" + member.displayHost());
            // label seam: client tag into bounded context, then a role decision that can see it.
            registrar.onInboundTags(
                    (tags, context) ->
                            new InboundTagResult(
                                    Map.of(),
                                    tags.tags().containsKey("label")
                                            ? Map.of("experimental/label", tags.tags().get("label"))
                                            : Map.of()));
            registrar.onRecipients(
                    (intent, context) -> {
                        contextSeenByRoles.set(context);
                        return context.attributes().containsKey("experimental/label")
                                ? Set.of(DeliveryRole.SENDER_COPY)
                                : Set.of();
                    });
            // chathistory seam: a finite group framed only when its prerequisite is also
            // negotiated.
            registrar.onOutputGroup(
                    Set.of("tags"),
                    (group, context) -> {
                        List<Message> frames = new ArrayList<>();
                        frames.add(
                                new Message(
                                        Map.of(),
                                        null,
                                        null,
                                        "BATCH",
                                        List.of("+h", group.type())));
                        frames.addAll(group.members());
                        frames.add(new Message(Map.of(), null, null, "BATCH", List.of("-h")));
                        return new FramingPlan(frames);
                    });
        }
    }

    /** The status slot, from a second provider that knows nothing of the first. */
    private static final class AllStatuses extends AbstractCapabilityExtension {
        AllStatuses() {
            super("all-statuses");
        }

        @Override
        public void register(CapabilityRegistrar registrar) {
            registrar.onMemberStatus(limits -> 2, member -> String.join("", member.heldStatuses()));
        }
    }

    private final NicknameRegistry nicknames = new NicknameRegistry();
    private final ChannelRegistry channels = new ChannelRegistry();
    private final ExtensionRegistry extensions = new ExtensionRegistry();
    private final Map<ClientSession, ByteArrayOutputStream> outputs = new HashMap<>();
    private final List<SessionWriter> writers = new ArrayList<>();
    private final Experimental experimental = new Experimental();

    CapabilityCompositionTest() {
        extensions.register(experimental);
        extensions.enable("experimental");
        extensions.register(new AllStatuses());
        extensions.enable("all-statuses");
    }

    @AfterEach
    void closeWriters() {
        writers.forEach(SessionWriter::close);
    }

    private ClientSession user(String nick, String... negotiated) {
        ClientSession session =
                new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
        session.setNickname(nick);
        session.setIdent("ident");
        session.replaceNegotiatedCapabilities(List.of(negotiated));
        nicknames.claim(nick, session);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        outputs.put(session, out);
        @SuppressWarnings("PMD.CloseResource") // closed by closeWriters()
        SessionWriter writer =
                new SessionWriter(session, out, new CapabilityTagRenderer(extensions));
        writers.add(writer);
        session.attachWriter(writer);
        return session;
    }

    private String received(ClientSession session) {
        writers.forEach(SessionWriter::close);
        return outputs.get(session).toString(StandardCharsets.UTF_8);
    }

    @Test
    void aCommittedEventReachesOnlyPeersThatNegotiatedTheNewCapability() throws Exception {
        ClientSession alice = user("alice");
        ClientSession listening = user("listening", "experimental");
        ClientSession deaf = user("deaf");
        for (ClientSession member : List.of(alice, listening, deaf)) {
            channels.join("#c", member, ch -> true);
        }

        new AwayCommandHandler(() -> "server", extensions)
                .handle(alice, MessageParser.parse("AWAY :x"));

        assertThat(received(listening)).contains("NOTICE x seen");
        assertThat(received(deaf)).doesNotContain("seen");
    }

    @Test
    void anIdentitySlotAndAStatusSlotFromDifferentProvidersCompose() {
        ClientSession op = user("op");
        Channel channel = channels.join("#c", op, ch -> true).orElseThrow().channel();
        channel.grantVoice(op);
        ClientSession requester = user("requester", "experimental", "all-statuses");

        assertThat(new MemberReplies(extensions).namesTokens(requester, channel.memberFacts()))
                .containsExactly("@+op!ident@host.example");
    }

    @Test
    void aRequestContextCarriesAClientTagToTheRoleDecisionWithinItsBounds() {
        ClientSession sender = user("sender", "experimental");
        ClientSession peer = user("peer");
        MessageDelivery delivery =
                new MessageDelivery(channels, nicknames, extensions, () -> "server");

        MessageDelivery.Delivery labeled =
                delivery.deliver(
                        sender,
                        "PRIVMSG",
                        "peer",
                        "hi",
                        Map.of("label", "abc"),
                        delivery.authorize(sender, "peer", true),
                        r -> true);
        MessageDelivery.Delivery unlabeled =
                delivery.deliver(
                        sender,
                        "PRIVMSG",
                        "peer",
                        "hi",
                        Map.of(),
                        delivery.authorize(sender, "peer", true),
                        r -> true);

        assertThat(labeled.context().attributes()).containsEntry("experimental/label", "abc");
        assertThat(labeled.recipients().get(sender)).containsExactly(DeliveryRole.SENDER_COPY);
        assertThat(unlabeled.recipients()).doesNotContainKey(sender);
        // The correlation never travels to the peer's copy of the message.
        assertThat(received(peer)).doesNotContain("abc");
    }

    @Test
    void aFiniteGroupIsFramedOnlyWhenItsPrerequisiteIsAlsoNegotiated() {
        ClientSession both = user("both", "experimental", "tags");
        ClientSession ownerOnly = user("owner-only", "experimental");
        Message member =
                new Message(Map.of(), "s!i@h", Command.PRIVMSG, "PRIVMSG", List.of("#c", "page"));

        both.writer().enqueueGroup("history", List.of(), List.of(member));
        ownerOnly.writer().enqueueGroup("history", List.of(), List.of(member));

        assertThat(received(both)).contains("BATCH +h history").contains("BATCH -h");
        assertThat(received(ownerOnly)).doesNotContain("BATCH");
    }

    @Test
    void theMadeUpCapabilityNeededNoChangeToCoreAndOffersOnlyItsOwnName() {
        assertThat(extensions.offeredCapabilities())
                .extracting(Capability::name)
                .containsExactlyInAnyOrder("experimental", "all-statuses");
    }
}
