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
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.NumericReply;

/**
 * The shared authorization check every admin command but {@code OPER} itself uses: the sender must
 * hold administrator privilege, AND the {@code admin} extension supplying these commands must still
 * be currently enabled — the latter is what makes {@code EXTENSION DISABLE admin}'s self-lockout
 * work (contracts/irc-protocol-commands.md "Self-lockout"): the command handlers stay registered
 * (there's no unregister mechanism), but this check fails once {@code admin} is disabled, rejecting
 * even an already-`OPER`'d session exactly like a non-privileged one.
 */
final class AdminPrivilege {

    private AdminPrivilege() {}

    static boolean isAuthorized(ClientSession session, ExtensionRegistry extensionRegistry) {
        return session.isAdministrator()
                && extensionRegistry.stateOf(AdminExtension.ID) == Extension.State.ENABLED;
    }

    /**
     * Rejects with {@code 481 ERR_NOPRIVILEGES} and returns {@code true} unless {@code session} is
     * authorized; returns {@code false} without side effects otherwise.
     */
    static boolean rejectUnlessAuthorized(
            ClientSession session, ExtensionRegistry extensionRegistry, String serverName) {
        if (isAuthorized(session, extensionRegistry)) {
            return false;
        }
        Replies.sendFitted(
                session,
                serverName,
                NumericReply.ERR_NOPRIVILEGES,
                List.of(),
                "Permission Denied- You're not an IRC operator");
        return true;
    }

    /**
     * Rejects with {@code 461 ERR_NEEDMOREPARAMS} and returns {@code true} if {@code params} has
     * fewer than {@code minParams} elements; returns {@code false} without side effects otherwise.
     */
    static boolean rejectIfTooFewParams(
            ClientSession session,
            String serverName,
            String commandName,
            List<String> params,
            int minParams) {
        if (params.size() >= minParams) {
            return false;
        }
        Replies.sendFitted(
                session,
                serverName,
                NumericReply.ERR_NEEDMOREPARAMS,
                List.of(commandName),
                "Not enough parameters");
        return true;
    }
}
