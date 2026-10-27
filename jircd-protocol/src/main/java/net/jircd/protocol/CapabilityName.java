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
package net.jircd.protocol;

/**
 * IRCv3 capability names this server implements — the shared vocabulary of the wire value, so each
 * {@code jircd-capabilities/*} module's own {@code Extension} class exposes its id through one of
 * these constants rather than its own private literal, and clients and tests refer to the same
 * spelling. Names only: {@code jircd-core} never branches on a concrete capability name; each
 * module owns its behavior through the typed registrations of the capability SPI.
 */
public final class CapabilityName {

    private CapabilityName() {}

    public static final String MESSAGE_TAGS = "message-tags";
    public static final String SERVER_TIME = "server-time";
    public static final String ECHO_MESSAGE = "echo-message";
    public static final String AWAY_NOTIFY = "away-notify";
    public static final String BATCH = "batch";
    public static final String MULTI_PREFIX = "multi-prefix";

    /**
     * Core infrastructure, not a provider: always offered in {@code CAP LS}, never a toggleable
     * extension. It opts a pre-302 client in to {@code CAP NEW}/{@code DEL} notifications.
     */
    public static final String CAP_NOTIFY = "cap-notify";
}
