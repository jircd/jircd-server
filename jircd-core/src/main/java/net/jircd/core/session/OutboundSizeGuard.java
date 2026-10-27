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

import java.util.List;
import java.util.concurrent.TimeUnit;
import net.jircd.protocol.Message;
import net.jircd.protocol.MessageSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checks indivisible wire fields and diagnoses command-size failures after text fitting. */
public final class OutboundSizeGuard {
    private static final Logger LOG = LoggerFactory.getLogger(OutboundSizeGuard.class);
    private static final String FIXED_FIELDS_REASON = "fields exceed command budget";
    private static final WarningThrottle WARNINGS =
            new WarningThrottle(TimeUnit.MINUTES.toNanos(1), 256, System::nanoTime);

    private OutboundSizeGuard() {}

    /** Forgets warning keys so the next occurrence is logged again; for tests. */
    public static void resetWarnings() {
        WARNINGS.reset();
    }

    /**
     * Checks an indivisible reply, logging a throttled skip if its command section exceeds 512
     * bytes including CRLF, excluding tags.
     */
    public static boolean isRepresentable(Message message) {
        int bytes = MessageSerializer.commandBytes(message);
        if (bytes <= MessageSerializer.MAX_COMMAND_BYTES) {
            return true;
        }
        warn(message, FIXED_FIELDS_REASON, bytes, bytes);
        return false;
    }

    /**
     * Diagnoses a command-size failure after serialization, including text fitting, has failed.
     * Returns false when size cannot explain the failure, leaving tag diagnostics to the writer.
     * Warnings are shared across recipients, bounded to one per command per minute and 256 keys.
     */
    public static boolean warnIfOversized(Message message) {
        int bytes = MessageSerializer.commandBytes(message);
        if (bytes <= MessageSerializer.MAX_COMMAND_BYTES) {
            return false;
        }
        String command = command(message);
        if (("PRIVMSG".equalsIgnoreCase(command) || "NOTICE".equalsIgnoreCase(command))
                && message.params().size() == 2) {
            Message empty =
                    new Message(
                            message.tags(),
                            message.prefix(),
                            message.command(),
                            command,
                            List.of(message.params().getFirst(), ""));
            int fixedBytes = MessageSerializer.commandBytes(empty);
            if (fixedBytes > MessageSerializer.MAX_COMMAND_BYTES) {
                warn(message, FIXED_FIELDS_REASON, bytes, fixedBytes);
            } else {
                String body = message.params().getLast();
                int firstBytes =
                        MessageSerializer.utf8Bytes(Character.toString(body.codePointAt(0)));
                if (firstBytes <= MessageSerializer.MAX_COMMAND_BYTES - fixedBytes) {
                    return false;
                }
                warn(message, "text has no room for a complete code point", bytes, fixedBytes);
            }
        } else {
            warn(message, FIXED_FIELDS_REASON, bytes, bytes);
        }
        return true;
    }

    private static String command(Message message) {
        return message.rawCommand() != null ? message.rawCommand() : message.command().name();
    }

    private static void warn(Message message, String reason, int bytes, int fixedBytes) {
        if (!LOG.isWarnEnabled()) {
            return;
        }
        String command = command(message);
        long suppressed = WARNINGS.admit("reply|" + command);
        if (suppressed >= 0) {
            LOG.warn(
                    "Dropping oversized reply: reply={}, reason={}, commandBytes={}, fixedFieldsBytes={}, budget={}, suppressedSinceLast={}",
                    command,
                    reason,
                    bytes,
                    fixedBytes,
                    MessageSerializer.MAX_COMMAND_BYTES,
                    suppressed);
        }
    }
}
