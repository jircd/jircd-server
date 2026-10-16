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

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChannelTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = ' ',
      value = {
        "a[b a{b",
        "a{b a[b",
        "a]b a}b",
        "a}b a]b",
        "a\\b a|b",
        "a|b a\\b",
        "a^b a~b",
        "a~b a^b",
        "Alice aLICE"
      })
  void findMemberUsesTheRfc1459CaseMapping(String joinedAs, String targeted) {
    Channel channel = new Channel("#lobby");
    ClientSession member = new ClientSession("id", "host.example", RateLimitBucket.withDefaults());
    member.setNickname(joinedAs);
    channel.addMember(member);

    assertThat(channel.findMember(targeted)).containsSame(member);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = ' ',
      value = {"a[b a]b", "a^b a_b"})
  void findMemberDoesNotMatchDistinctNicknames(String joinedAs, String targeted) {
    Channel channel = new Channel("#lobby");
    ClientSession member = new ClientSession("id", "host.example", RateLimitBucket.withDefaults());
    member.setNickname(joinedAs);
    channel.addMember(member);

    assertThat(channel.findMember(targeted)).isEmpty();
  }

  @Test
  void memberListingUsesOperatorBeforeVoiceAndListsEachMemberOnceInIterationOrder() {
    Channel channel = new Channel("#lobby");
    List<ClientSession> members =
        List.of(member("operator"), member("voiced"), member("both"), member("plain"));
    // Package-private setup stays in the owner package; the first joiner receives operator status.
    members.forEach(channel::addMember);
    channel.voiced().add(members.get(1));
    channel.operators().add(members.get(2));
    channel.voiced().add(members.get(2));
    assertThat(channel.memberListing())
        .containsExactlyInAnyOrder("@operator", "+voiced", "@both", "plain")
        .doesNotHaveDuplicates();
    assertThat(channel.memberListing())
        .containsExactlyElementsOf(
            channel.members().stream()
                .map(
                    m ->
                        (channel.operators().contains(m)
                                ? "@"
                                : channel.voiced().contains(m) ? "+" : "")
                            + m.nickname())
                .toList());
  }

  @Test
  void emptyChannelHasEmptyMemberListing() {
    assertThat(new Channel("#empty").memberListing()).isEmpty();
  }

  private static ClientSession member(String nick) {
    ClientSession member = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    member.setNickname(nick);
    return member;
  }
}
