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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * This release's core, always-present channel-mode flags (contracts/irc-protocol-commands.md "Full
 * Channel Mode Catalog") — never gated by FR-011 toggling (FR-036). Command-level enforcement for
 * each rolls out story-by-story, but the catalog itself — what {@code RPL_ISUPPORT}'s {@code
 * CHANMODES}/{@code PREFIX} and {@code 004}'s mode-letter list advertise — is complete from
 * registration onward.
 */
public final class CoreChannelModes {

    public static final ChannelMode MEMBERS_ONLY =
            new ChannelMode(
                    "members-only",
                    'n',
                    ChannelMode.Kind.BOOLEAN,
                    Set.of(GateAction.SEND),
                    ChannelMode.CORE);
    public static final ChannelMode MODERATED =
            new ChannelMode(
                    "moderated",
                    'm',
                    ChannelMode.Kind.BOOLEAN,
                    Set.of(GateAction.SEND),
                    ChannelMode.CORE);
    public static final ChannelMode PRIVATE =
            new ChannelMode(
                    "private",
                    'p',
                    ChannelMode.Kind.BOOLEAN,
                    Set.of(GateAction.DISCOVER),
                    ChannelMode.CORE);
    public static final ChannelMode SECRET =
            new ChannelMode(
                    "secret",
                    's',
                    ChannelMode.Kind.BOOLEAN,
                    Set.of(GateAction.DISCOVER),
                    ChannelMode.CORE);
    public static final ChannelMode INVITE_ONLY =
            new ChannelMode(
                    "invite-only",
                    'i',
                    ChannelMode.Kind.BOOLEAN,
                    Set.of(GateAction.JOIN),
                    ChannelMode.CORE);
    public static final ChannelMode BAN_MASK =
            new ChannelMode(
                    "ban-mask",
                    'b',
                    ChannelMode.Kind.LIST,
                    Set.of(GateAction.SEND, GateAction.JOIN),
                    ChannelMode.CORE);
    public static final ChannelMode BAN_EXCEPTION =
            new ChannelMode(
                    "ban-exception", 'e', ChannelMode.Kind.LIST, Set.of(), ChannelMode.CORE);
    public static final ChannelMode INVITE_EXCEPTION =
            new ChannelMode(
                    "invite-exception", 'I', ChannelMode.Kind.LIST, Set.of(), ChannelMode.CORE);
    public static final ChannelMode OPERATOR =
            new ChannelMode("operator", 'o', ChannelMode.Kind.MEMBER, Set.of(), ChannelMode.CORE);
    public static final ChannelMode VOICE =
            new ChannelMode("voice", 'v', ChannelMode.Kind.MEMBER, Set.of(), ChannelMode.CORE);

    /**
     * 006-complete-core-protocol FR-001 — state lives in {@code Channel.memberLimit}, not {@code
     * activeModes} (data-model.md).
     */
    public static final ChannelMode USER_LIMIT =
            new ChannelMode(
                    "user-limit",
                    'l',
                    ChannelMode.Kind.VALUE_SET_ONLY,
                    Set.of(GateAction.JOIN),
                    ChannelMode.CORE);

    /**
     * 006-complete-core-protocol FR-004 — state lives in {@code Channel.key}, not {@code
     * activeModes} (data-model.md).
     */
    public static final ChannelMode CHANNEL_KEY =
            new ChannelMode(
                    "channel-key",
                    'k',
                    ChannelMode.Kind.VALUE_ALWAYS,
                    Set.of(GateAction.JOIN),
                    ChannelMode.CORE);

    /**
     * 006-complete-core-protocol FR-007 through FR-009 — {@code gates} is empty because
     * topic-setting isn't one of {@link GateAction}'s three modeled actions ({@code SEND}/{@code
     * JOIN}/{@code DISCOVER} — {@code DISCOVER} covers {@code TOPIC}-*viewing*, not *setting*);
     * consulted directly by {@code TopicCommandHandler}, the same way {@code operator}/{@code
     * voice} privilege checks already go straight to {@code Channel.operators()} rather than
     * through a gate abstraction (research.md "Story 2").
     */
    public static final ChannelMode TOPIC_LOCK =
            new ChannelMode(
                    "topic-lock", 't', ChannelMode.Kind.BOOLEAN, Set.of(), ChannelMode.CORE);

    /** Order matters for {@code CHANMODES}/{@code PREFIX} formatting (SupportedFeatures). */
    public static final Set<ChannelMode> ALL =
            Collections.unmodifiableSet(
                    new LinkedHashSet<>(
                            Set.of(
                                    BAN_MASK,
                                    BAN_EXCEPTION,
                                    INVITE_EXCEPTION,
                                    INVITE_ONLY,
                                    MODERATED,
                                    MEMBERS_ONLY,
                                    PRIVATE,
                                    SECRET,
                                    OPERATOR,
                                    VOICE,
                                    USER_LIMIT,
                                    CHANNEL_KEY,
                                    TOPIC_LOCK)));

    private CoreChannelModes() {}
}
