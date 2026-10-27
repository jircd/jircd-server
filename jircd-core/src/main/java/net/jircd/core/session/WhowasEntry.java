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

import java.time.Instant;

/**
 * Immutable snapshot of one disconnected session's last-known identity (002-extended-irc-commands
 * data-model.md "WhowasEntry") — created exactly once, at the moment a session becomes {@code
 * CLOSING}, never retroactively edited.
 *
 * @param realHostname the connection's real hostname/IP, regardless of cloaking — shown only to an
 *     administrator querying {@code WHOWAS} (mirrors {@code WHOHOST}/{@code WHOIS}'s FR-038
 *     resolution), never to anyone else
 * @param presentedHostname the value {@code cloak} (if enabled) was displaying for this session at
 *     the moment it disconnected — a snapshot, not a live recomputation against whatever cloak
 *     state happens to be active when {@code WHOWAS} is later queried; shown to every
 *     non-administrator requester
 */
public record WhowasEntry(
        String nickname,
        String ident,
        String realHostname,
        String presentedHostname,
        String realname,
        Instant disconnectedAt) {}
