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

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;
import net.jircd.core.config.ConfigurationException;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code REHASH} — the in-band equivalent of a {@code SIGHUP} (research.md "Configuration reload
 * mechanism"): re-reads and re-validates the Server Configuration file, reconciling it against live
 * state. A validation failure reports the specific error directly to the session and leaves the
 * previously-active configuration untouched, the same as the file-triggered path.
 */
public final class RehashCommandHandler implements CommandHandler {

    private final ConfigurationReloader configurationReloader;
    private final ExtensionRegistry extensionRegistry;
    private final Supplier<String> serverName;

    public RehashCommandHandler(
            ConfigurationReloader configurationReloader,
            ExtensionRegistry extensionRegistry,
            Supplier<String> serverName) {
        this.configurationReloader = configurationReloader;
        this.extensionRegistry = extensionRegistry;
        this.serverName = serverName;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        if (!AdminPrivilege.isAuthorized(session, extensionRegistry)) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NOPRIVILEGES,
                    List.of(),
                    "Permission Denied- You're not an IRC operator");
            return;
        }
        try {
            ConfigurationReloader.ReloadOutcome outcome = configurationReloader.reloadWithOutcome();
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.RPL_REHASHING,
                    List.of(configurationReloader.configPath().toString()),
                    "Rehashing");
            // A valid reload still applies; a provider that failed to change is reported, not
            // hidden.
            outcome.failures()
                    .forEach(
                            (id, reason) ->
                                    AdminNotices.send(
                                            session,
                                            serverName.get(),
                                            "Rehash could not apply " + id + ": " + reason));
        } catch (ConfigurationException e) {
            AdminNotices.send(session, serverName.get(), "Rehash failed: " + e.getMessage());
        } catch (IOException e) {
            AdminNotices.send(
                    session, serverName.get(), "Rehash failed: could not read configuration file");
        }
    }
}
