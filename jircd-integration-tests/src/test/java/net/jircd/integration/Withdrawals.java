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
package net.jircd.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.jircd.core.extension.Extension;
import net.jircd.core.extension.ExtensionRegistry;

/** Waits, without polling, for a hook fault's FAILED withdrawal of a provider to finish. */
final class Withdrawals {

  private Withdrawals() {}

  /**
   * Returns once {@code id}'s withdrawal is complete. {@code stopped} is counted down by the
   * provider's {@code stop}, which the withdrawal calls while holding the registry's lifecycle
   * lock; the withdrawal records the provider FAILED before it releases that lock. A disable takes
   * the same lock and, for a FAILED provider with no cleanup owed, changes nothing, so once it
   * returns the outcome is final.
   */
  static void awaitFailed(ExtensionRegistry registry, String id, CountDownLatch stopped)
      throws InterruptedException {
    assertThat(stopped.await(5, TimeUnit.SECONDS)).as("%s stopped", id).isTrue();
    registry.disable(id);
    assertThat(registry.stateOf(id)).isEqualTo(Extension.State.FAILED);
  }

  /**
   * Every line {@code client} reads up to and including the first one containing {@code marker}. A
   * fault's withdrawal runs on its own thread, so its {@code DEL} may reach a client before the
   * output whose preparation faulted; callers keep both lines instead of reading past the {@code
   * DEL}.
   */
  static List<String> linesThrough(RawIrcClient client, String marker) throws IOException {
    List<String> lines = new ArrayList<>();
    String line;
    do {
      line = client.readUntil(" ", Duration.ofSeconds(5)); // every IRC line has a space
      lines.add(line);
    } while (!line.contains(marker));
    return lines;
  }
}
