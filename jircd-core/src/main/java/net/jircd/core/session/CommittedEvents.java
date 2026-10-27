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

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.capability.Audience;
import net.jircd.core.extension.capability.CommittedEvent;
import net.jircd.core.extension.capability.EventIntent;
import net.jircd.core.extension.capability.EventKind;
import net.jircd.core.extension.capability.HookKind;
import net.jircd.core.extension.capability.HookRegistration;
import net.jircd.core.extension.capability.RuntimeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes facts about state transitions that have already committed to the capability providers
 * that listen for them, and delivers whatever they ask for. Core calls this after the transition,
 * with every registry and channel monitor released, and never rolls back or retries because of a
 * provider: a faulty hook is contained by the dispatcher and the committed state stands.
 *
 * <p>Nothing is collected unless a provider listens for the kind: the presented identity is
 * resolved and the audience is traversed only on demand, at most once per event, so an event costs
 * nothing when no capability wants it. A rejected or no-op operation publishes nothing; the caller
 * decides what counts as a transition.
 *
 * <p>Ordering: events caused by one session's own commands are published in the order that session
 * issued them (its commands run serially). An event another session causes about it, such as {@code
 * KICKED}, has no order relative to those, and nothing is ordered across subjects; no lock or
 * global sequence is kept for it.
 */
public final class CommittedEvents {
    private static final Logger LOG = LoggerFactory.getLogger(CommittedEvents.class);

    private static final AtomicLong OPERATION_IDS = new AtomicLong();

    private final ExtensionRegistry extensionRegistry;

    public CommittedEvents(ExtensionRegistry extensionRegistry) {
        this.extensionRegistry = Objects.requireNonNull(extensionRegistry);
    }

    /**
     * Publishes one event. {@code subject} is resolved, and {@code audience} collected, only if
     * some provider listens for {@code kind}. Empty strings stand for a missing {@code
     * before}/{@code after} value.
     */
    public void publish(
            EventKind kind,
            Supplier<String> subject,
            String before,
            String after,
            Supplier<? extends Collection<ClientSession>> audience) {
        publish(kind, subject, () -> "", before, after, audience);
    }

    private void publish(
            EventKind kind,
            Supplier<String> subject,
            Supplier<String> actor,
            String before,
            String after,
            Supplier<? extends Collection<ClientSession>> audience) {
        RuntimeSnapshot snapshot = extensionRegistry.capabilitySnapshot();
        if (!listened(snapshot, kind)) return;
        try {
            CommittedEvent event =
                    new CommittedEvent(
                            kind,
                            OPERATION_IDS.incrementAndGet(),
                            subject.get(),
                            before,
                            after,
                            new Audience(() -> List.copyOf(audience.get())),
                            actor.get());
            for (EventIntent intent : extensionRegistry.hookDispatcher().event(snapshot, event)) {
                Recipients.deliver(intent.recipients(), intent.message());
            }
        } catch (RuntimeException e) {
            // The transition has committed and the caller still owes its client the remaining
            // replies: a failure resolving the presented identity (hostname-display extension code)
            // must not cost it those. Only the type is logged; the message may carry extension or
            // client data.
            LOG.warn("Committed {} event not published: {}", kind, e.getClass().getSimpleName());
        }
    }

    /** Whether some enabled provider listens for {@code kind}. */
    public boolean listensTo(EventKind kind) {
        return listened(extensionRegistry.capabilitySnapshot(), kind);
    }

    private static boolean listened(RuntimeSnapshot snapshot, EventKind kind) {
        for (HookRegistration registration : snapshot.hooks(HookKind.EVENT)) {
            if (registration.eventKind() == kind) return true;
        }
        return false;
    }

    /** {@code session} set, changed or cleared its away reason; empty strings mean not away. */
    public void awayChanged(ClientSession session, String before, String after) {
        publish(
                EventKind.AWAY_CHANGED,
                () -> PresentedIdentity.presentedForm(session, extensionRegistry),
                before,
                after,
                () -> Recipients.sharedChannelNeighbors(session, false, member -> true));
    }

    /** {@code session} joined {@code channel}; {@code recipients} are its members afterwards. */
    public void joined(
            ClientSession session,
            String presentedForm,
            String channel,
            Collection<ClientSession> recipients) {
        publish(
                EventKind.JOINED,
                () -> presentedForm,
                "",
                channel,
                () -> recipients.stream().filter(member -> member != session).toList());
    }

    /** {@code session} left {@code channel}; {@code recipients} were captured before it left. */
    public void parted(
            ClientSession session,
            String presentedForm,
            String channel,
            String reason,
            Collection<ClientSession> recipients) {
        publish(
                EventKind.PARTED,
                () -> presentedForm,
                channel,
                reason,
                () -> recipients.stream().filter(member -> member != session).toList());
    }

    /**
     * {@code kicked} was removed from {@code channel} by the session presented as {@code
     * kickerPresentedForm}; {@code recipients} were captured before it left. The kicked member is
     * the subject and is not in the audience. {@code kickedPresentedForm} is the identity captured
     * when the kick was prepared; it is never resolved again here, because the session may have
     * changed nickname since, so a caller without one publishes nothing.
     */
    public void kicked(
            String kickerPresentedForm,
            ClientSession kicked,
            String kickedPresentedForm,
            String channel,
            String reason,
            Collection<ClientSession> recipients) {
        Objects.requireNonNull(kickedPresentedForm);
        publish(
                EventKind.KICKED,
                () -> kickedPresentedForm,
                () -> kickerPresentedForm,
                channel,
                reason,
                () -> recipients.stream().filter(member -> member != kicked).toList());
    }

    /** {@code session} disconnected; {@code neighbors} were captured as its memberships went. */
    public void quit(
            ClientSession session,
            String presentedForm,
            String reason,
            Collection<ClientSession> neighbors) {
        publish(
                EventKind.QUIT,
                () -> presentedForm,
                "",
                reason,
                () -> neighbors.stream().filter(member -> member != session).toList());
    }

    /**
     * {@code session} changed nickname. The subject is the identity peers knew it by before, and
     * peers are the shared-channel neighbors, which a nickname change does not alter.
     */
    public void nickChanged(
            ClientSession session,
            String oldPresentedForm,
            String oldNickname,
            String newNickname) {
        publish(
                EventKind.NICK_CHANGED,
                () -> oldPresentedForm,
                oldNickname,
                newNickname,
                () -> Recipients.sharedChannelNeighbors(session, false, member -> true));
    }
}
