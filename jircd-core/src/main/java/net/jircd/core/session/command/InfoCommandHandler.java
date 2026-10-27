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
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code INFO} — a short, fixed server-information burst (005-fix-batch-conformance FR-019),
 * completing a command previously recognized but unhandled. Reuses the same {@code serverVersion}
 * source {@code VERSION}'s {@code 351} reply already uses, not a second, independent one.
 */
public final class InfoCommandHandler implements CommandHandler {

    private final Supplier<String> serverName;
    private final String serverVersion;

    public InfoCommandHandler(Supplier<String> serverName, String serverVersion) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        String server = serverName.get();
        Replies.sendFitted(
                session,
                server,
                NumericReply.RPL_INFO,
                List.of(),
                server + " (jircd IRC server) " + serverVersion);
        Replies.sendFitted(
                session, server, NumericReply.RPL_ENDOFINFO, List.of(), "End of /INFO list");
    }
}
