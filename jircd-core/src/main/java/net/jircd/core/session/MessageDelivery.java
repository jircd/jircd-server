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
package net.jircd.core.session;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.CapabilityRuntime;
import net.jircd.core.extension.capability.DeliveryIntent;
import net.jircd.core.extension.capability.DeliveryRole;
import net.jircd.core.extension.capability.HookDispatcher;
import net.jircd.core.extension.capability.InboundTagResult;
import net.jircd.core.extension.capability.InboundTags;
import net.jircd.core.extension.capability.RequestContext;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Hostmask;
import net.jircd.protocol.NumericReply;

/**
 * The shared path of every client-to-client message ({@code PRIVMSG}, {@code NOTICE} and a
 * provider's own tag-only command): target resolution with the core send gates, one immutable
 * envelope per message, and role-based recipient selection. A provider cannot supply an arbitrary
 * peer set; it can only add a {@link DeliveryRole#SENDER_COPY} for the sender through a recipient
 * hook, and core merges roles so a recipient gets the envelope once.
 *
 * <p>Each delivery captures the published capability hooks once and runs each hook once, however
 * many recipients there are. A {@link RequestContext} belongs to one inbound request: only the
 * recipient hooks see it, and it is never attached to the envelope, so peers do not inherit the
 * sender's request correlation.
 */
public final class MessageDelivery {
    private static final AtomicLong REQUEST_IDS = new AtomicLong();

    private final ChannelRegistry channelRegistry;
    private final NicknameRegistry nicknameRegistry;
    private final ExtensionRegistry extensionRegistry;
    private final Supplier<String> serverName;

    public MessageDelivery(
            ChannelRegistry channelRegistry,
            NicknameRegistry nicknameRegistry,
            ExtensionRegistry extensionRegistry,
            Supplier<String> serverName) {
        this.channelRegistry = Objects.requireNonNull(channelRegistry);
        this.nicknameRegistry = Objects.requireNonNull(nicknameRegistry);
        this.extensionRegistry = Objects.requireNonNull(extensionRegistry);
        this.serverName = Objects.requireNonNull(serverName);
    }

    /**
     * One completed delivery: the envelope all recipients share, the request it answered and who
     * received it in which roles.
     */
    public record Delivery(
            OutboundMessage envelope,
            RequestContext context,
            Map<ClientSession, Set<DeliveryRole>> recipients) {
        public Delivery {
            recipients = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(recipients));
        }
    }

    /**
     * The outcome of {@link #authorize}: the members {@code sender} may address at {@code target},
     * empty if it may address none. Only {@code authorize} creates one, and {@link #deliver}
     * accepts nothing else, so a caller cannot supply its own peer set and skip the target and send
     * gates.
     */
    public static final class AuthorizedTarget implements Iterable<ClientSession> {
        private final MessageDelivery issuer;
        private final ClientSession sender;
        private final String target;
        private final Set<ClientSession> recipients;

        private AuthorizedTarget(
                MessageDelivery issuer,
                ClientSession sender,
                String target,
                Set<ClientSession> recipients) {
            this.issuer = issuer;
            this.sender = sender;
            this.target = target;
            this.recipients = Set.copyOf(recipients);
        }

        public boolean isEmpty() {
            return recipients.isEmpty();
        }

        public Set<ClientSession> recipients() {
            return recipients;
        }

        @Override
        public java.util.Iterator<ClientSession> iterator() {
            return recipients.iterator();
        }
    }

    /**
     * Resolves {@code target} into the members that may be addressed: a channel name must exist and
     * pass the {@code SEND} gates, a nickname must be connected. Returns an empty set on failure,
     * having already sent the appropriate error to {@code sender} when {@code reportErrors} is
     * {@code true} ({@code NOTICE}-style silence otherwise).
     */
    public AuthorizedTarget authorize(ClientSession sender, String target, boolean reportErrors) {
        return new AuthorizedTarget(this, sender, target, resolve(sender, target, reportErrors));
    }

    private Set<ClientSession> resolve(ClientSession sender, String target, boolean reportErrors) {
        if (target.startsWith("#")) {
            var channel = channelRegistry.lookup(target);
            if (channel.isEmpty() || !passesSendGate(channel.get(), sender)) {
                if (reportErrors) {
                    Replies.sendFitted(
                            sender,
                            serverName.get(),
                            NumericReply.ERR_CANNOTSENDTOCHAN,
                            List.of(target),
                            "Cannot send to channel");
                }
                return Set.of();
            }
            return channel.get().members();
        }
        var recipient = nicknameRegistry.lookup(target);
        if (recipient.isEmpty()) {
            if (reportErrors) {
                Replies.sendFitted(
                        sender,
                        serverName.get(),
                        NumericReply.ERR_NOSUCHNICK,
                        List.of(target),
                        "No such nick/channel");
            }
            return Set.of();
        }
        return Set.of(recipient.get());
    }

    /**
     * Builds the envelope for {@code authorized} and enqueues it to every recipient that passes
     * {@code recipientGate}. A direct target always receives it, even when it is the sender itself,
     * exactly once; a channel's own member set drops the sender unless a recipient hook added a
     * sender copy, and a direct message to someone else is copied to the sender only that way.
     *
     * @param authorized what {@link #authorize} returned for this sender and target
     * @param clientTags the sender's tags; only those an inbound-tag hook selects are forwarded
     * @param body the text, or {@code null} for a tag-only message
     */
    public Delivery deliver(
            ClientSession sender,
            String command,
            String target,
            String body,
            Map<String, String> clientTags,
            AuthorizedTarget authorized,
            Predicate<ClientSession> recipientGate) {
        if (authorized.issuer != this
                || authorized.sender != sender
                || !authorized.target.equals(target)) {
            throw new IllegalArgumentException(
                    "The authorization was issued for another sender, target or delivery service");
        }
        if (authorized.isEmpty()) {
            // Nothing was authorized, so there is nothing to copy to the sender either.
            OutboundMessage unsent = OutboundMessage.now("", command, target, body);
            return new Delivery(unsent, RequestContext.of(0, Map.of()), Map.of());
        }
        // One coherent view: a disable between two separate reads could pair the old snapshot with
        // the sender's newly pruned set and skip hooks the sender had negotiated.
        CapabilityRuntime.CapturedView view = extensionRegistry.capabilityControl().capture(sender);
        RuntimeSnapshot snapshot = view.snapshot();
        HookDispatcher dispatcher = extensionRegistry.hookDispatcher();
        Set<String> senderNegotiated = view.negotiated();

        long requestId = REQUEST_IDS.incrementAndGet();
        RequestContext opening = RequestContext.of(requestId, Map.of());
        HookDispatcher.InboundSelection selection =
                dispatcher.inboundSelection(
                        snapshot, senderNegotiated, new InboundTags(command, clientTags), opening);
        InboundTagResult inbound = selection.merged();
        RequestContext context = RequestContext.of(requestId, inbound.context());

        String presentedForm = PresentedIdentity.presentedForm(sender, extensionRegistry);
        OutboundMessage envelope =
                OutboundMessage.now(presentedForm, command, target, body, inbound.forwarded())
                        .forwardedBy(selection.ownersByKey());

        Set<DeliveryRole> senderRoles =
                dispatcher.recipientRoles(
                        snapshot,
                        senderNegotiated,
                        new DeliveryIntent(
                                envelope.messageId(), EnumSet.of(DeliveryRole.ORDINARY_TARGET)),
                        context);
        boolean senderCopy = senderRoles.contains(DeliveryRole.SENDER_COPY);

        Map<ClientSession, Set<DeliveryRole>> roles = new LinkedHashMap<>();
        boolean channelTarget = target.startsWith("#");
        for (ClientSession member : authorized.recipients) {
            // A direct target always receives it, even when it is the sender; a channel's own
            // member set drops the sender, who is re-added below only as a copy.
            if (member != sender || !channelTarget) {
                roles.put(member, EnumSet.of(DeliveryRole.ORDINARY_TARGET));
            }
        }
        if (senderCopy) {
            roles.computeIfAbsent(sender, ignored -> EnumSet.noneOf(DeliveryRole.class))
                    .add(DeliveryRole.SENDER_COPY);
        }

        Map<ClientSession, Set<DeliveryRole>> delivered = new LinkedHashMap<>();
        roles.forEach(
                (recipient, recipientRoles) -> {
                    if (recipientGate.test(recipient)) {
                        delivered.put(recipient, Set.copyOf(recipientRoles));
                        // Borrowed reference to the recipient's own writer, closed by its
                        // connection.
                        @SuppressWarnings("PMD.CloseResource")
                        SessionWriter writer = recipient.writer();
                        if (writer != null) writer.enqueue(envelope);
                    }
                });
        return new Delivery(envelope, context, delivered);
    }

    /**
     * The {@code SEND}-gate check point: every currently-recognized flag whose {@code gates}
     * includes {@code SEND}, checked independently — {@code members-only} requires membership;
     * {@code moderated} requires operator or voice; {@code ban-mask} requires neither the sender's
     * presented nor real identity to match any active ban (dual-matched to resist {@code cloak}
     * evasion) — muting a matched sender without removing them from members.
     */
    private boolean passesSendGate(Channel channel, ClientSession session) {
        if (channel.activeModes().contains(CoreChannelModes.MEMBERS_ONLY)
                && !channel.members().contains(session)) {
            return false;
        }
        if (channel.activeModes().contains(CoreChannelModes.MODERATED)
                && !channel.operators().contains(session)
                && !channel.voiced().contains(session)) {
            return false;
        }
        if (!channel.bans().isEmpty()) {
            String presented = PresentedIdentity.presentedForm(session, extensionRegistry);
            String real =
                    Hostmask.format(session.nickname(), session.ident(), session.realHostname());
            return !channel.matchesMask(CoreChannelModes.BAN_MASK, presented, real)
                    || channel.matchesMask(CoreChannelModes.BAN_EXCEPTION, presented, real);
        }
        return true;
    }
}
