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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.HostnameDisplayExtension;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import net.jircd.core.session.BanEntry;
import net.jircd.core.session.Channel;
import net.jircd.core.session.ChannelRegistry;
import net.jircd.core.session.ClientSession;
import net.jircd.core.session.RateLimitBucket;
import net.jircd.protocol.MessageParser;
import org.junit.jupiter.api.Test;

/** The JOIN ban gate's hostname-display extension call runs before any registry monitor. */
class JoinBanGateLockTest {

  @Test
  void hostnameDisplayDuringBanGateDoesNotBlockOtherChannelLookups() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    Channel banned =
        registry.join("#banned", session("owner"), ignored -> true).orElseThrow().channel();
    banned.addBan(new BanEntry("*!*@nobody.example", "owner", Instant.EPOCH));
    Channel quiet =
        registry.join("#quiet", session("quiet"), ignored -> true).orElseThrow().channel();
    CountDownLatch displaying = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(new PausingHostname(displaying, release));
    extensions.enable("pausing-hostname");
    JoinCommandHandler handler =
        new JoinCommandHandler(registry, extensions, () -> "server", () -> 50);
    ClientSession joiner = session("joiner");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var join =
          executor.submit(
              () -> {
                handler.handle(joiner, MessageParser.parse("JOIN #banned"));
                return null;
              });
      try {
        assertThat(displaying.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(executor.submit(() -> registry.lookup("#quiet")))
            .succeedsWithin(Duration.ofSeconds(2))
            .satisfies(found -> assertThat(found).containsSame(quiet));
      } finally {
        release.countDown();
      }
      join.get(5, TimeUnit.SECONDS);
    }
    assertThat(banned.members()).contains(joiner);
  }

  private static ClientSession session(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    session.setIdent(nick);
    return session;
  }

  private static final class PausingHostname implements ServerExtension, HostnameDisplayExtension {
    private final CountDownLatch entered;
    private final CountDownLatch resume;
    private final AtomicBoolean first = new AtomicBoolean(true);

    private PausingHostname(CountDownLatch entered, CountDownLatch resume) {
      this.entered = entered;
      this.resume = resume;
    }

    @Override
    public String id() {
      return "pausing-hostname";
    }

    @Override
    public String extensionPoint() {
      return "hostname-display";
    }

    @Override
    public void start(ServerContext context) {
      // Stateless test extension: nothing to set up.
    }

    @Override
    public void stop() {
      // Stateless test extension: nothing to release.
    }

    @Override
    public String display(String hostname) {
      if (first.compareAndSet(true, false)) {
        entered.countDown();
        try {
          assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      return hostname;
    }
  }
}
