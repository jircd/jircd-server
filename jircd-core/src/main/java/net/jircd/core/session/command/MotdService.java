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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.WarningThrottle;
import net.jircd.protocol.NumericReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the message of the day: {@code 375}, one {@code 372} per file line, {@code 376}; or {@code
 * 422} when no file is configured or it cannot be used. The path comes from a supplier so a
 * configuration reload takes effect on the next request, and the file is re-read whenever its
 * modification time or size changes, so edits need no reload. Problems are logged as warnings
 * (rate-limited per path and reason) and never thrown.
 */
public final class MotdService {

    private static final Logger LOG = LoggerFactory.getLogger(MotdService.class);

    /** Largest MOTD file served; a bigger one is treated as unusable rather than read per login. */
    public static final int MAX_FILE_BYTES = 64 * 1024;

    private static final WarningThrottle WARNINGS =
            new WarningThrottle(
                    java.util.concurrent.TimeUnit.MINUTES.toNanos(1), 64, System::nanoTime);

    /** Most lines served, so a registration burst of 512-byte lines stays far under the SendQ. */
    public static final int MAX_LINES = 200;

    /** What a cached read was made from; a different path, mtime, size or file identity rereads. */
    private record Snapshot(
            String path, FileTime modified, long size, Object fileKey, List<String> lines) {
        Snapshot(String path, BasicFileAttributes attributes, List<String> lines) {
            this(
                    path,
                    attributes.lastModifiedTime(),
                    attributes.size(),
                    attributes.fileKey(),
                    lines);
        }

        boolean matches(String currentPath, BasicFileAttributes attributes) {
            return path.equals(currentPath)
                    && modified.equals(attributes.lastModifiedTime())
                    && size == attributes.size()
                    && java.util.Objects.equals(fileKey, attributes.fileKey());
        }
    }

    private final Supplier<String> serverName;
    private final Supplier<String> motdPath;
    private final AtomicReference<Snapshot> cached = new AtomicReference<>();

    public MotdService(Supplier<String> serverName, Supplier<String> motdPath) {
        this.serverName = serverName;
        this.motdPath = motdPath;
    }

    /** A service with no MOTD file, always answering {@code 422}. */
    public static MotdService none(Supplier<String> serverName) {
        return new MotdService(serverName, () -> null);
    }

    public void send(ClientSession session) {
        String server = serverName.get();
        List<String> lines = lines();
        if (lines.isEmpty()) {
            Replies.sendFitted(
                    session, server, NumericReply.ERR_NOMOTD, List.of(), "MOTD File is missing");
            return;
        }
        Replies.sendFitted(
                session,
                server,
                NumericReply.RPL_MOTDSTART,
                List.of(),
                "- " + server + " Message of the day - ");
        for (String line : lines) {
            Replies.sendFitted(session, server, NumericReply.RPL_MOTD, List.of(), "- " + line);
        }
        Replies.sendFitted(
                session, server, NumericReply.RPL_ENDOFMOTD, List.of(), "End of /MOTD command");
    }

    /** The current MOTD lines, empty when there is none. */
    private List<String> lines() {
        String configured = motdPath.get();
        if (configured == null) {
            return List.of();
        }
        try {
            Path path = Path.of(configured);
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return unusable(configured, "is not a regular file");
            }
            Snapshot snapshot = cached.get();
            if (snapshot != null && snapshot.matches(configured, attributes)) {
                return snapshot.lines();
            }
            // The size seen above can be stale by now, so never read more than the limit allows.
            byte[] content;
            try (InputStream in = Files.newInputStream(path)) {
                content = in.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (content.length > MAX_FILE_BYTES) {
                return unusable(configured, "is larger than " + MAX_FILE_BYTES + " bytes");
            }
            List<String> lines = split(content);
            if (lines.size() > MAX_LINES) {
                unusable(
                        configured,
                        "has more than " + MAX_LINES + " lines; only the first are served");
                lines = List.copyOf(lines.subList(0, MAX_LINES));
            }
            cached.set(new Snapshot(configured, attributes, lines));
            if (lines.isEmpty()) {
                return unusable(configured, "is empty");
            }
            return lines;
        } catch (IOException | RuntimeException failure) {
            return unusable(configured, "cannot be read: " + failure);
        }
    }

    /** Only the final line terminator(s) are dropped; whitespace on the last line is kept. */
    private static List<String> split(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.isBlank()) {
            return List.of();
        }
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end).lines().toList();
    }

    private static List<String> unusable(String path, String reason) {
        long suppressed = WARNINGS.admit(path + ' ' + reason);
        if (suppressed >= 0) {
            LOG.warn(
                    "MOTD file {} {}; clients get 422 (suppressed {} similar)",
                    path,
                    reason,
                    suppressed);
        }
        return List.of();
    }
}
