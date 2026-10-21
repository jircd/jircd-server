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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.Supplier;
import net.jircd.core.session.ClientSession;
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
      new WarningThrottle(java.util.concurrent.TimeUnit.MINUTES.toNanos(1), 64, System::nanoTime);

  private record Snapshot(String path, long modifiedMillis, long size, List<String> lines) {}

  private final Supplier<String> serverName;
  private final Supplier<String> motdPath;
  private volatile Snapshot cached;

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
      Replies.send(session, server, NumericReply.ERR_NOMOTD, "MOTD File is missing");
      return;
    }
    Replies.send(
        session, server, NumericReply.RPL_MOTDSTART, "- " + server + " Message of the day - ");
    for (String line : lines) {
      Replies.sendFitted(session, server, NumericReply.RPL_MOTD, List.of(), "- " + line);
    }
    Replies.send(session, server, NumericReply.RPL_ENDOFMOTD, "End of /MOTD command");
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
      if (attributes.size() > MAX_FILE_BYTES) {
        return unusable(configured, "is larger than " + MAX_FILE_BYTES + " bytes");
      }
      long modified = attributes.lastModifiedTime().toMillis();
      Snapshot snapshot = cached;
      if (snapshot != null
          && snapshot.path().equals(configured)
          && snapshot.modifiedMillis() == modified
          && snapshot.size() == attributes.size()) {
        return snapshot.lines();
      }
      List<String> lines = split(Files.readAllBytes(path));
      cached = new Snapshot(configured, modified, attributes.size(), lines);
      if (lines.isEmpty()) {
        return unusable(configured, "is empty");
      }
      return lines;
    } catch (IOException | RuntimeException failure) {
      return unusable(configured, "cannot be read: " + failure);
    }
  }

  private static List<String> split(byte[] content) {
    String text = new String(content, StandardCharsets.UTF_8);
    if (text.isBlank()) {
      return List.of();
    }
    return text.stripTrailing().lines().toList();
  }

  private static List<String> unusable(String path, String reason) {
    long suppressed = WARNINGS.admit(path + ' ' + reason);
    if (suppressed >= 0) {
      LOG.warn(
          "MOTD file {} {}; clients get 422 (suppressed {} similar)", path, reason, suppressed);
    }
    return List.of();
  }
}
