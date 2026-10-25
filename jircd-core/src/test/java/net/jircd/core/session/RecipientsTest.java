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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.jircd.protocol.CapabilityName;
import net.jircd.protocol.Command;
import net.jircd.protocol.Message;
import org.junit.jupiter.api.Test;

class RecipientsTest {
  @Test
  void sharedNeighborsIncludeSelfFirstAndEachPeerOnceInTraversalOrder() {
    ClientSession self = session("self");
    ClientSession alice = session("alice");
    ClientSession bob = session("bob");
    ClientSession carol = session("carol");
    ChannelRegistry registry = new ChannelRegistry();
    for (String name : List.of("#a", "#b", "#c")) {
      for (ClientSession member : List.of(alice, self, bob, carol)) {
        registry.join(name, member, ignored -> true);
      }
    }
    Set<ClientSession> expected = new LinkedHashSet<>();
    expected.add(self);
    for (Channel channel : self.channelMemberships()) {
      expected.addAll(channel.members());
    }
    Set<ClientSession> actual = Recipients.sharedChannelNeighbors(self, true, s -> true);
    assertThat(actual)
        .hasSize(4)
        .isInstanceOf(LinkedHashSet.class)
        .containsExactlyElementsOf(expected);
    assertThat(actual.iterator().next()).isSameAs(self);
    expected.remove(self);
    assertThat(Recipients.sharedChannelNeighbors(self, false, s -> true))
        .containsExactlyElementsOf(expected);
  }

  @Test
  void capabilityFilterExcludesUnsubscribedNeighborsAndSelf() {
    ClientSession self = session("self");
    ClientSession subscribed = session("subscribed");
    ClientSession other = session("other");
    TestNegotiation.add(subscribed, CapabilityName.AWAY_NOTIFY);
    TestNegotiation.add(self, CapabilityName.AWAY_NOTIFY);
    ChannelRegistry registry = new ChannelRegistry();
    for (ClientSession member : List.of(self, subscribed, other)) {
      registry.join("#a", member, ignored -> true);
    }
    assertThat(
            Recipients.sharedChannelNeighbors(
                self, false, s -> s.negotiatedCapabilities().contains(CapabilityName.AWAY_NOTIFY)))
        .containsExactly(subscribed);
  }

  @Test
  void sessionWithoutChannelsReturnsOnlySelfOrNothing() {
    ClientSession self = session("self");
    assertThat(Recipients.sharedChannelNeighbors(self, true, s -> true)).containsExactly(self);
    assertThat(Recipients.sharedChannelNeighbors(self, false, s -> true)).isEmpty();
    assertThat(Recipients.sharedChannelNeighbors(self, true, s -> false)).isEmpty();
  }

  @Test
  void deliverySkipsMissingWriterAndContinuesWithRemainingRecipients() {
    ClientSession first = session("first");
    ClientSession missing = session("missing");
    ClientSession last = session("last");
    ByteArrayOutputStream firstOut = new ByteArrayOutputStream();
    ByteArrayOutputStream lastOut = new ByteArrayOutputStream();
    try (SessionWriter firstWriter = new SessionWriter(first, firstOut, TagRenderer.NONE);
        SessionWriter lastWriter = new SessionWriter(last, lastOut, TagRenderer.NONE)) {
      first.attachWriter(firstWriter);
      last.attachWriter(lastWriter);
      Recipients.deliver(List.of(first, missing, last), Message.of(Command.PING, "hello"));
    }
    assertThat(firstOut.toString(StandardCharsets.UTF_8)).isEqualTo("PING hello\r\n");
    assertThat(lastOut.toString(StandardCharsets.UTF_8)).isEqualTo("PING hello\r\n");
  }

  private static ClientSession session(String nick) {
    ClientSession session = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    session.setNickname(nick);
    return session;
  }
}
