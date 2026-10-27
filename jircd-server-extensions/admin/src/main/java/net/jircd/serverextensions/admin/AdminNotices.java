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
package net.jircd.serverextensions.admin;

import java.util.List;
import java.util.Map;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;

/**
 * Server-originated {@code NOTICE} replies (confirmation/result text with no dedicated numeric)
 * several admin commands use — {@code EXTENSION}'s success confirmation, {@code WHOHOST}'s result.
 */
final class AdminNotices {

    private AdminNotices() {}

    static void send(ClientSession session, String serverName, String text) {
        if (session.writer() != null) {
            session.writer()
                    .enqueueRaw(
                            new Message(
                                    Map.of(),
                                    serverName,
                                    Command.NOTICE,
                                    "NOTICE",
                                    List.of(Replies.target(session), text)));
        }
    }
}
