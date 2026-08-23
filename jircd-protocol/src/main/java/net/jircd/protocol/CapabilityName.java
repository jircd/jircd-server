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
 * IRCv3 capability names this server implements — the one shared source of truth for a wire value
 * that both server-side code and a future client both need: each {@code jircd-capabilities/*}
 * module's own {@code Extension} class exposes its id via one of these constants (rather than its
 * own private literal), and {@code jircd-core} command handlers that must check a recipient's
 * negotiated set reference the same constant directly — {@code jircd-core} cannot depend on {@code
 * jircd-capabilities} (wrong dependency direction), but both already depend on this module, so
 * routing through here removes the duplication instead of requiring every caller to re-declare its
 * own copy of the string.
 */
public final class CapabilityName {

  private CapabilityName() {}

  public static final String MESSAGE_TAGS = "message-tags";
  public static final String SERVER_TIME = "server-time";
  public static final String ECHO_MESSAGE = "echo-message";
  public static final String AWAY_NOTIFY = "away-notify";
  public static final String BATCH = "batch";
}
