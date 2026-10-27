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

import java.util.function.Supplier;
import net.jircd.core.session.CaseMapping;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.NumericReply;

/**
 * The optional server-name parameter of {@code ADMIN} and {@code STATS}. This server has no peers,
 * so only its own name is a valid target.
 */
final class LocalServerTarget {

    private LocalServerTarget() {}

    /** Whether {@code target} names this server; otherwise sends {@code 402} and returns false. */
    static boolean accepts(ClientSession session, Supplier<String> serverName, String target) {
        String server = serverName.get();
        if (CaseMapping.fold(server).equals(CaseMapping.fold(target))) {
            return true;
        }
        Replies.sendEchoing(
                session, server, NumericReply.ERR_NOSUCHSERVER, target, "No such server");
        return false;
    }
}
