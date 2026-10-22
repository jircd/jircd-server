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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import net.jircd.core.session.ClientSession;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

final class StatsCommandHandlerTest {

  private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");

  private final ClientSession alice = new HandlerHarness().register("alice");

  private StatsCommandHandler at(Duration uptime) {
    return new StatsCommandHandler(
        () -> "server.example", STARTED, Clock.fixed(STARTED.plus(uptime), ZoneOffset.UTC));
  }

  @Test
  void uReportsUptimeThenEndOfStats() throws Exception {
    Duration uptime = Duration.ofDays(2).plusHours(3).plusMinutes(4).plusSeconds(5);

    List<Message> replies = HandlerHarness.run(at(uptime), alice, "STATS u");

    assertThat(HandlerHarness.codes(replies)).containsExactly("242", "219");
    assertThat(replies.get(0).params()).containsExactly("alice", "Server Up 2 days 3:04:05");
    assertThat(replies.get(1).params()).containsExactly("alice", "u", "End of /STATS report");
  }

  @Test
  void uptimeUnderADayOmitsTheDays() throws Exception {
    List<Message> replies = HandlerHarness.run(at(Duration.ofSeconds(59)), alice, "STATS u");

    assertThat(replies.get(0).params().getLast()).isEqualTo("Server Up 0 days 0:00:59");
  }

  @Test
  void otherLettersOnlyGetEndOfStats() throws Exception {
    List<Message> replies = HandlerHarness.run(at(Duration.ZERO), alice, "STATS m");

    assertThat(HandlerHarness.codes(replies)).containsExactly("219");
    assertThat(replies.getFirst().params()).containsExactly("alice", "m", "End of /STATS report");
  }

  @Test
  void onlyTheFirstLetterCounts() throws Exception {
    List<Message> replies = HandlerHarness.run(at(Duration.ZERO), alice, "STATS uptime");

    assertThat(HandlerHarness.codes(replies)).containsExactly("242", "219");
    assertThat(replies.get(1).params().get(1)).isEqualTo("u");
  }

  @Test
  void noLetterIsNeedMoreParams() throws Exception {
    List<Message> replies = HandlerHarness.run(at(Duration.ZERO), alice, "STATS");

    assertThat(replies.getFirst().rawCommand()).isEqualTo("461");
    assertThat(replies.getFirst().params()).contains("STATS");
  }

  @Test
  void anOverlongServerNameStillGetsAFittingNoSuchServer() throws Exception {
    List<Message> replies =
        HandlerHarness.run(at(Duration.ZERO), alice, "STATS u " + "s".repeat(2000));

    assertThat(HandlerHarness.codes(replies)).containsExactly("402");
  }

  @Test
  void anotherServerIsNoSuchServer() throws Exception {
    List<Message> replies = HandlerHarness.run(at(Duration.ZERO), alice, "STATS u other.example");

    assertThat(HandlerHarness.codes(replies)).containsExactly("402");
  }
}
