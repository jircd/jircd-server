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
package net.jircd.core.extension;

import net.jircd.core.session.command.CommandHandler;
import net.jircd.protocol.Command;

/**
 * How a {@link ServerExtension} that introduces new commands (e.g. {@code admin}'s {@code
 * OPER}/{@code EXTENSION}/{@code REHASH}/{@code WHOHOST}/{@code SAJOIN}/{@code SAMODE}) adds them
 * to {@code jircd-core}'s command dispatch — {@code jircd-server}, the composition root, only
 * depends on extension modules at runtime ({@code ServiceLoader}), so it can't register their
 * handlers itself; the extension does it, given this at {@link Extension#start}.
 */
public interface CommandRegistrar {

  void register(Command command, CommandHandler handler);

  /** Whether {@code command} already has a handler (core's, or another extension's). */
  boolean isRegistered(Command command);

  /**
   * Removes {@code installed} for {@code command}, but only if it is still the handler in place: a
   * handler another extension has since installed over it stays. The command is answered as unknown
   * once nothing handles it. There is deliberately no default, so a registrar cannot claim to
   * support installing handlers it can never take back.
   */
  void unregister(Command command, CommandHandler installed);
}
