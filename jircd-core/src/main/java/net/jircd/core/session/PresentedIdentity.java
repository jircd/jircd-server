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

import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerExtension;
import net.jircd.protocol.Hostmask;

/**
 * Resolves a session's display hostmask by live-checking current {@code cloak} {@link
 * ServerExtension} state, never cached, with no cloak extension enabled, the display value is
 * simply the real hostname. Each call is a live sample. A caller may reuse one sample briefly:
 * JOIN's ban check uses the identity sampled just before it takes the membership monitors, so a
 * JOIN racing an administrator's cloak toggle is checked against the earlier identity.
 */
public final class PresentedIdentity {

  private PresentedIdentity() {}

  public static String displayHostname(ClientSession session, ExtensionRegistry extensionRegistry) {
    for (Extension extension : extensionRegistry.enabled()) {
      if (extension instanceof ServerExtension serverExtension
          && "hostname-display".equals(serverExtension.extensionPoint())
          && extension instanceof HostnameDisplayExtension cloak) {
        return cloak.display(session.realHostname());
      }
    }
    return session.realHostname();
  }

  public static String presentedForm(ClientSession session, ExtensionRegistry extensionRegistry) {
    return Hostmask.format(
        session.nickname(), session.ident(), displayHostname(session, extensionRegistry));
  }
}
