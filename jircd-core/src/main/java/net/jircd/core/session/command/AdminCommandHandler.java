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
import net.jircd.core.config.ServerConfiguration.AdminContact;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code ADMIN} — the administrator contact (RFC 2812 §3.4.9): {@code 256} through {@code 259} from
 * the configured name, location and email, with {@code -} standing in for an unset line. With none
 * configured the server answers {@code 423} rather than invent contact details.
 */
public final class AdminCommandHandler implements CommandHandler {

    private static final String UNSET = "-";

    private final Supplier<String> serverName;
    private final Supplier<AdminContact> contact;

    public AdminCommandHandler(Supplier<String> serverName, Supplier<AdminContact> contact) {
        this.serverName = serverName;
        this.contact = contact;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        if (!message.params().isEmpty()
                && !LocalServerTarget.accepts(session, serverName, message.params().getFirst())) {
            return;
        }
        String server = serverName.get();
        AdminContact admin = contact.get();
        if (admin.isEmpty()) {
            Replies.sendFitted(
                    session,
                    server,
                    NumericReply.ERR_NOADMININFO,
                    List.of(server),
                    "No administrative info available");
            return;
        }
        Replies.sendFitted(
                session,
                server,
                NumericReply.RPL_ADMINME,
                List.of(server),
                "Administrative info about " + server);
        line(session, server, NumericReply.RPL_ADMINLOC1, admin.name());
        line(session, server, NumericReply.RPL_ADMINLOC2, admin.location());
        line(session, server, NumericReply.RPL_ADMINEMAIL, admin.email());
    }

    private static void line(
            ClientSession session, String server, NumericReply numeric, String text) {
        Replies.sendFitted(session, server, numeric, List.of(), text == null ? UNSET : text);
    }
}
