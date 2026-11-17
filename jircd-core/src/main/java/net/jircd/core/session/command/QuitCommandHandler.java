/*
 * Copyright 2026 JIRCD Maintainers
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

import net.jircd.core.session.ClientSession;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.protocol.Message;

/**
 * {@code QUIT} — usable at any time, including before registration completes (FR-060). Sends {@code
 * ERROR} (RFC 2812 §3.1.7) with the client-supplied reason, or a fixed default if none was given —
 * never a blank reason. {@link DisconnectCleanup} claims CLOSING first and the {@code ERROR} is
 * queued as part of that claim, so a client that has read it is no longer counted (#151); {@code
 * KILL} and the keep-alive timeout use the same claim-then-ERROR order. A session another trigger
 * already claimed gets no second {@code ERROR}.
 */
public final class QuitCommandHandler implements CommandHandler {

    private static final String DEFAULT_REASON = "Client Quit";

    private final DisconnectCleanup disconnectCleanup;

    public QuitCommandHandler(DisconnectCleanup disconnectCleanup) {
        this.disconnectCleanup = disconnectCleanup;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        String reason = message.params().isEmpty() ? null : message.params().getFirst();
        String effectiveReason = reason != null ? reason : DEFAULT_REASON;
        disconnectCleanup.disconnectWithError(session, effectiveReason, effectiveReason);
    }
}
