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

import java.util.List;
import java.util.Map;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;

/**
 * {@code QUIT} — usable at any time, including before registration completes (FR-060). Sends {@code
 * ERROR} (RFC 2812 §3.1.7) before invoking {@link DisconnectCleanup} with the client-supplied
 * reason, or a fixed default if none was given — never a blank reason — the same
 * enqueue-then-cleanup order {@code KILL} and the keep-alive timeout already use.
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
        // Borrowed reference to this session's own still-active writer — closed below via
        // disconnectCleanup.cleanup(), not directly by this handler.
        @SuppressWarnings("PMD.CloseResource")
        var writer = session.writer();
        if (writer != null) {
            writer.enqueueRaw(
                    new Message(Map.of(), null, Command.ERROR, "ERROR", List.of(effectiveReason)));
        }
        disconnectCleanup.cleanup(session, effectiveReason);
    }
}
