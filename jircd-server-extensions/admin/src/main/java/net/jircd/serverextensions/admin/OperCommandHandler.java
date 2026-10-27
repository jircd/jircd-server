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

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.jircd.core.config.ConfigurationReloader;
import net.jircd.core.config.ConnectionLimitPolicy;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.DisconnectCleanup;
import net.jircd.core.session.SecurityEventLog;
import net.jircd.core.session.command.CommandHandler;
import net.jircd.core.session.command.Replies;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import net.jircd.protocol.NumericReply;

/**
 * {@code OPER} — verifies administrator credentials (FR-034), granting privilege on success and
 * tracking failures across connections toward a source-address lockout (research.md "OPER
 * failed-attempt lockout"). Deliberately does not itself check {@link AdminPrivilege} — this is the
 * credential-verification entry point that grants privilege, not a privilege-gated action.
 */
public final class OperCommandHandler implements CommandHandler {

    private final Supplier<String> serverName;
    private final ConfigurationReloader configurationReloader;
    private final DisconnectCleanup disconnectCleanup;
    private final OperAttemptTracker attempts;

    public OperCommandHandler(
            Supplier<String> serverName,
            ConfigurationReloader configurationReloader,
            DisconnectCleanup disconnectCleanup) {
        this(
                serverName,
                configurationReloader,
                disconnectCleanup,
                new OperAttemptTracker(Clock.systemUTC(), 10_000));
    }

    OperCommandHandler(
            Supplier<String> serverName,
            ConfigurationReloader configurationReloader,
            DisconnectCleanup disconnectCleanup,
            OperAttemptTracker attempts) {
        this.attempts = attempts;
        this.serverName = serverName;
        this.configurationReloader = configurationReloader;
        this.disconnectCleanup = disconnectCleanup;
    }

    @Override
    public void handle(ClientSession session, Message message) {
        if (message.params().size() < 2) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NEEDMOREPARAMS,
                    List.of("OPER"),
                    "Not enough parameters");
            return;
        }
        String username = message.params().getFirst();
        String password = message.params().get(1);
        var config = configurationReloader.current();

        String source =
                session.sourceAddress() == null
                        ? "connection:" + session.connectionId()
                        : ConnectionLimitPolicy.resolve(
                                        session.sourceAddress(), config.connectionLimits())
                                .bucket();
        var result =
                attempts.authenticate(
                        source,
                        config.operFailureThreshold(),
                        config.operLockoutSeconds(),
                        !config.operRequireTls() || session.isTls(),
                        () ->
                                AdminCredentialVerifier.verify(
                                        config.administratorCredentials(), username, password));
        if (result == OperAttemptTracker.Result.CAPACITY_FULL) {
            result = authenticateSession(session, config, username, password);
        }
        if (result == OperAttemptTracker.Result.TLS_REQUIRED) {
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.ERR_NOOPERHOST,
                    List.of(),
                    "OPER requires TLS; reconnect using TLS");
            return;
        }
        if (result == OperAttemptTracker.Result.SUCCESS) {
            session.resetFallbackOperFailures();
            session.grantAdministratorPrivilege();
            Replies.sendFitted(
                    session,
                    serverName.get(),
                    NumericReply.RPL_YOUREOPER,
                    List.of(),
                    "You are now an IRC operator");
            // 005-fix-batch-conformance FR-023 — the same unsolicited self-directed MODE echo shape
            // UserModeCommandHandler already uses for a self mode change, so the client's own state
            // tracking reflects the new operator status immediately, without a separate query.
            if (session.writer() != null) {
                session.writer()
                        .enqueueRaw(
                                new Message(
                                        Map.of(),
                                        serverName.get(),
                                        Command.MODE,
                                        "MODE",
                                        List.of(session.nickname(), "+o")));
            }
            return;
        }

        SecurityEventLog.failedAuthentication(
                session.connectionId(),
                username,
                result == OperAttemptTracker.Result.LOCKED
                        ? "OPER source locked out"
                        : "invalid OPER credentials");
        Replies.sendFitted(
                session,
                serverName.get(),
                NumericReply.ERR_PASSWDMISMATCH,
                List.of(),
                "Password incorrect");
        if (result == OperAttemptTracker.Result.LOCKED
                || result == OperAttemptTracker.Result.FAILURE_LIMIT_REACHED) {
            disconnectCleanup.cleanup(session, "Too many failed OPER attempts");
        }
    }

    private static OperAttemptTracker.Result authenticateSession(
            ClientSession session, ServerConfiguration config, String username, String password) {
        if (config.operRequireTls() && !session.isTls()) {
            return OperAttemptTracker.Result.TLS_REQUIRED;
        }
        if (AdminCredentialVerifier.verify(config.administratorCredentials(), username, password)) {
            return OperAttemptTracker.Result.SUCCESS;
        }
        return session.incrementFallbackOperFailures() >= config.operFailureThreshold()
                ? OperAttemptTracker.Result.FAILURE_LIMIT_REACHED
                : OperAttemptTracker.Result.FAILURE;
    }
}
