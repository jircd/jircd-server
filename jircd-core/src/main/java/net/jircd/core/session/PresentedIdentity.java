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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerExtension;
import net.jircd.protocol.Hostmask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a session's display hostmask by live-checking current {@code cloak} {@link
 * ServerExtension} state, never cached, with no cloak extension enabled, the display value is
 * simply the real hostname. Each call is a live sample. A caller may reuse one sample briefly:
 * JOIN's ban check uses the identity sampled just before it takes the membership monitors, so a
 * JOIN racing an administrator's cloak toggle is checked against the earlier identity.
 *
 * <p>A hostname an extension returns is cut to {@link ServerConfiguration#PRESENTED_HOST_MAX_BYTES}
 * bytes, the host term the configuration's reply budget assumes. The cut value, not the real
 * hostname, is the fallback: the real value is the address a cloak exists to hide.
 */
public final class PresentedIdentity {

  private static final Logger LOG = LoggerFactory.getLogger(PresentedIdentity.class);

  private static final long WARN_INTERVAL_NANOS = 60_000_000_000L;

  /** Nanotime of the last over-long warning; zero until the first one. */
  private static final AtomicLong LAST_WARNED = new AtomicLong();

  private static final AtomicReference<LongSupplier> NANO_CLOCK =
      new AtomicReference<>(System::nanoTime);

  private PresentedIdentity() {}

  public static String displayHostname(ClientSession session, ExtensionRegistry extensionRegistry) {
    for (Extension extension : extensionRegistry.enabled()) {
      if (extension instanceof ServerExtension serverExtension
          && "hostname-display".equals(serverExtension.extensionPoint())
          && extension instanceof HostnameDisplayExtension cloak) {
        return bounded(cloak.display(session.realHostname()), extension.id());
      }
    }
    return session.realHostname();
  }

  public static String presentedForm(ClientSession session, ExtensionRegistry extensionRegistry) {
    return Hostmask.format(
        session.nickname(), session.ident(), displayHostname(session, extensionRegistry));
  }

  /**
   * Cuts {@code host} to the byte cap on a code-point boundary, warning at most once a minute. One
   * pass counts each code point's UTF-8 bytes without encoding, and stops at the first one past the
   * cap, so a huge value costs work proportional to the cap.
   */
  private static String bounded(String host, String extensionId) {
    if (host == null) {
      return null;
    }
    int cap = ServerConfiguration.PRESENTED_HOST_MAX_BYTES;
    int used = 0;
    int end = 0;
    while (end < host.length()) {
      int codePoint = host.codePointAt(end);
      int bytes = utf8Bytes(codePoint);
      if (used + bytes > cap) {
        warnOverlong(extensionId);
        return host.substring(0, end);
      }
      used += bytes;
      end += Character.charCount(codePoint);
    }
    return host;
  }

  private static int utf8Bytes(int codePoint) {
    if (codePoint < 0x80) {
      return 1;
    }
    if (codePoint < 0x800) {
      return 2;
    }
    return codePoint < 0x10000 ? 3 : 4;
  }

  private static void warnOverlong(String extensionId) {
    long now = NANO_CLOCK.get().getAsLong();
    long last = LAST_WARNED.get();
    if ((last == 0 || now - last >= WARN_INTERVAL_NANOS) && LAST_WARNED.compareAndSet(last, now)) {
      LOG.warn(
          "Extension {} presented a hostname over {} bytes; truncating it",
          extensionId,
          ServerConfiguration.PRESENTED_HOST_MAX_BYTES);
    }
  }

  /** Swaps the throttle's clock and restarts its window; tests only. */
  static void useClockForTest(LongSupplier clock) {
    NANO_CLOCK.set(clock);
    LAST_WARNED.set(0);
  }
}
