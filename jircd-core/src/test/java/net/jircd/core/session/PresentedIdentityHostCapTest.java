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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import net.jircd.core.config.ServerConfiguration;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import org.junit.jupiter.api.Test;

class PresentedIdentityHostCapTest {

  private static final int CAP = ServerConfiguration.PRESENTED_HOST_MAX_BYTES;

  private static final class Cloak implements ServerExtension, HostnameDisplayExtension {
    private final String shown;

    Cloak(String shown) {
      this.shown = shown;
    }

    @Override
    public String id() {
      return "cap-cloak";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public void start(ServerContext context) {
      // Stateless test extension.
    }

    @Override
    public void stop() {
      // Stateless test extension.
    }

    @Override
    public String display(String hostname) {
      return shown;
    }
  }

  private static String displayed(String shown) {
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new Cloak(shown));
    extensions.enable("cap-cloak");
    ClientSession session = new ClientSession("c1", "203.0.113.9", RateLimitBucket.withDefaults());
    return PresentedIdentity.displayHostname(session, extensions);
  }

  @Test
  void aHostAtTheCapPassesThroughUnchanged() {
    String host = "h".repeat(CAP);
    assertThat(displayed(host)).isEqualTo(host);
  }

  @Test
  void anOverlongHostIsTruncatedNotReplacedByTheRealAddress() {
    String host = "h".repeat(300);
    assertThat(displayed(host)).isEqualTo("h".repeat(CAP)).doesNotContain("203.0.113.9");
  }

  @Test
  void truncationKeepsWholeCodePoints() {
    String shown = displayed("界".repeat(40));
    assertThat(shown).isEqualTo("界".repeat(CAP / 3));
    assertThat(shown.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(CAP);
  }

  @Test
  void repeatedOverlongHostsNeverThrow() {
    for (int i = 0; i < 5; i++) {
      assertThat(displayed("x".repeat(500))).hasSize(CAP);
    }
  }
}
