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
package net.jircd.core.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Structured, reviewable logging of security-relevant events — failed authentication attempts,
 * rejected moderation actions, etc. (FR-019).
 */
public final class SecurityEventLog {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityEventLog.class);

    private SecurityEventLog() {}

    /** Records a rejection on its worker, outside the listener's admission lock. */
    public static void connectionLimit(
            String remoteAddress, String limit, String bucket, int appliedLimit) {
        LOG.info(
                "security-event=connection-limit remoteAddress={} limit={} bucket={} limitApplied={}",
                remoteAddress,
                limit,
                bucket,
                appliedLimit);
    }

    /** Summarizes rejections dropped because every rejection worker is occupied. */
    public static void connectionLimitSuppressed(long count) {
        LOG.info("security-event=connection-limit-suppressed count={}", count);
    }

    public static void failedAuthentication(String connectionId, String username, String reason) {
        LOG.info(
                "security-event=failed-authentication connection={} username={} reason={}",
                connectionId,
                username,
                reason);
    }

    public static void rejectedModerationAction(
            String connectionId, String action, String target, String reason) {
        LOG.info(
                "security-event=rejected-moderation-action connection={} action={} target={} reason={}",
                connectionId,
                action,
                target,
                reason);
    }

    public static void connectionDisconnectedForAbuse(String connectionId, String reason) {
        LOG.info("security-event=abuse-disconnect connection={} reason={}", connectionId, reason);
    }
}
