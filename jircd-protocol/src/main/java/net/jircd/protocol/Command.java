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
 * Every command RFC 1459/2812 defines, plus the IRCv3 framework commands and project-specific
 * administration commands this project uses (contracts/ irc-protocol-commands.md "Full Command
 * Catalog"). Recognition here is independent of whether {@code jircd-core} has a handler for a
 * given command this release.
 */
public enum Command {
    PASS,
    NICK,
    USER,
    OPER,
    MODE,
    SERVICE,
    QUIT,
    SQUIT,
    JOIN,
    PART,
    TOPIC,
    NAMES,
    LIST,
    INVITE,
    KICK,
    PRIVMSG,
    NOTICE,
    MOTD,
    LUSERS,
    VERSION,
    STATS,
    LINKS,
    TIME,
    CONNECT,
    TRACE,
    ADMIN,
    INFO,
    SERVLIST,
    SQUERY,
    WHO,
    WHOIS,
    WHOWAS,
    KILL,
    PING,
    PONG,
    ERROR,
    AWAY,
    REHASH,
    DIE,
    RESTART,
    SUMMON,
    USERS,
    WALLOPS,
    USERHOST,
    ISON,
    CAP,
    AUTHENTICATE,
    TAGMSG,
    EXTENSION,
    WHOHOST,
    SAJOIN,
    SAMODE,
    HELP,
    HELPOP;

    /**
     * Resolves a wire command token to its {@link Command}, matched case-insensitively (FR-015 —
     * {@code join}/{@code Join}/{@code JOIN} all resolve to the same entry). Returns {@code null}
     * if the token does not name any recognized command.
     */
    public static Command fromToken(String token) {
        for (Command command : values()) {
            if (command.name().equalsIgnoreCase(token)) {
                return command;
            }
        }
        return null;
    }
}
