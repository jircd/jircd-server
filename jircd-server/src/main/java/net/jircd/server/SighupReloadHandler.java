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
package net.jircd.server;

import net.jircd.core.config.ConfigurationReloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

/**
 * The manual, file-only configuration reload trigger (research.md "Configuration reload mechanism")
 * — keeps Story 4 usable without depending on Story 6's optional {@code admin} extension. Editing
 * the configuration file alone has no effect until {@code SIGHUP} (or the in-band {@code REHASH}
 * command, Story 6) fires.
 */
public final class SighupReloadHandler {

  private static final Logger LOG = LoggerFactory.getLogger(SighupReloadHandler.class);

  private SighupReloadHandler() {}

  public static void install(JircdServerApplication application) {
    Signal.handle(new Signal("HUP"), signal -> reload(application.reloader()));
  }

  /**
   * One SIGHUP reload, reported as {@code REHASH} reports it: a valid reload still applies, and
   * each extension that could not reach its desired state is logged with why instead of the reload
   * being reported as clean.
   */
  public static void reload(ConfigurationReloader reloader) {
    try {
      ConfigurationReloader.ReloadOutcome outcome = reloader.reloadWithOutcome();
      if (outcome.failures().isEmpty()) {
        LOG.info("Configuration reloaded via SIGHUP");
      }
      outcome
          .failures()
          .forEach(
              (id, reason) ->
                  LOG.warn(
                      "Configuration reloaded via SIGHUP, but could not apply {}: {}", id, reason));
    } catch (Exception e) {
      LOG.warn("SIGHUP reload failed, running configuration left untouched", e);
    }
  }
}
