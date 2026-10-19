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
package net.jircd.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.jircd.core.extension.ExtensionRegistry;
import net.jircd.core.extension.ServerContext;
import net.jircd.core.extension.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationReloaderConcurrencyTest {
  @TempDir Path directory;

  /** An extension whose start blocks, holding the first reload mid-way through reconciliation. */
  private static final class BlockingExtension implements ServerExtension {
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Override
    public String id() {
      return "blocker";
    }

    @Override
    public void start(ServerContext context) {
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void stop() {}
  }

  @Test
  @Timeout(30)
  void aSecondReloadWaitsForTheFirstToFinishPublishingSoLimitsNeverSplit() throws Exception {
    ConfigurationLoader loader = new ConfigurationLoader(Set.of(), Set.of("blocker"));
    ServerConfiguration initial =
        loader.load(
            new ByteArrayInputStream(
                "serverName: test.jircd.local\n".getBytes(StandardCharsets.UTF_8)));
    BlockingExtension blocker = new BlockingExtension();
    ExtensionRegistry extensions = new ExtensionRegistry();
    extensions.register(blocker);
    Path yaml = directory.resolve("reload.yaml");
    ConfigurationReloader reloader = new ConfigurationReloader(yaml, loader, extensions, initial);

    Files.writeString(
        yaml,
        "serverName: test.jircd.local\nnicknameMaxLength: 9\nserver-extensions: {blocker: enabled}\n");
    Thread first = new Thread(() -> reloadQuietly(reloader), "first-reload");
    first.start();
    assertThat(blocker.entered.await(10, TimeUnit.SECONDS)).isTrue();

    Files.writeString(yaml, "serverName: test.jircd.local\nnicknameMaxLength: 20\n");
    Thread second = new Thread(() -> reloadQuietly(reloader), "second-reload");
    second.start();
    while (second.getState() != Thread.State.BLOCKED
        && second.getState() != Thread.State.TERMINATED) {
      Thread.onSpinWait();
    }
    assertThat(second.getState())
        .as("the second reload must wait for the first to publish")
        .isEqualTo(Thread.State.BLOCKED);

    blocker.release.countDown();
    first.join();
    second.join();
    assertThat(reloader.current().nicknameMaxLength()).isEqualTo(20);
    assertThat(extensions.supportedFeatures().nicknameMaxLength()).isEqualTo(20);
  }

  private static void reloadQuietly(ConfigurationReloader reloader) {
    try {
      reloader.reload();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
