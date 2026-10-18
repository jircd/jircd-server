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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    channel.grantVoice(members.get(1));
    channel.grantOperator(members.get(2));
    channel.grantVoice(members.get(2));
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

  @Test
  void collectionsRejectExternalAdditions() {
    Channel channel = new Channel("#c");
    ClientSession outsider = member("outsider");
    Set<ClientSession> members = channel.members();
    Set<ClientSession> operators = channel.operators();
    Set<ClientSession> voiced = channel.voiced();
    Set<ChannelMode> activeModes = channel.activeModes();
    List<BanEntry> bans = channel.bans();
    BanEntry ban = new BanEntry("*!*@host", "setter", Instant.EPOCH);
    assertThatThrownBy(() -> members.add(outsider))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> operators.add(outsider))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> voiced.add(outsider))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> activeModes.add(CoreChannelModes.SECRET))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> bans.add(ban)).isInstanceOf(UnsupportedOperationException.class);
  }

  /** Pins the serial order: resolve the member, PART, then attempt the operator grant. */
  @Test
  void grantRacingPartCannotLeaveAGhostOperator() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession target = member("target");
    Channel channel = registry.join("#c", target, ignored -> true).orElseThrow().channel();
    channel.revokeOperator(target);
    CountDownLatch resolved = new CountDownLatch(1);
    CountDownLatch removed = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var grant =
          executor.submit(
              () -> {
                ClientSession resolvedTarget = channel.findMember("target").orElseThrow();
                resolved.countDown();
                await(removed);
                return channel.grantOperator(resolvedTarget);
              });
      var part =
          executor.submit(
              () -> {
                await(resolved);
                try {
                  return registry.part(channel, target);
                } finally {
                  removed.countDown();
                }
              });
      assertThat(part.get(5, TimeUnit.SECONDS)).isPresent();
      assertThat(grant.get(5, TimeUnit.SECONDS)).isFalse();
    }
    assertThat(channel.members()).doesNotContain(target);
    assertThat(channel.operators()).doesNotContain(target);
  }

  @Test
  void removingAnAbsentBanDoesNotChangeOtherBans() {
    Channel channel = new Channel("#c");
    BanEntry ban = new BanEntry("*!*@banned.example", "setter", Instant.EPOCH);
    channel.addBan(ban);
    channel.removeBan("*!*@absent.example");
    assertThat(channel.bans()).containsExactly(ban);
    channel.removeBan(ban.mask());
    assertThat(channel.bans()).isEmpty();
    channel.removeBan(ban.mask());
    assertThat(channel.bans()).isEmpty();
  }

  @Test
  void grantsRefuseNonMembersAndRevokesRemovePrivileges() {
    Channel channel = new Channel("#c");
    ClientSession outsider = member("outsider");
    assertThat(channel.grantOperator(outsider)).isFalse();
    assertThat(channel.grantVoice(outsider)).isFalse();
    assertThat(channel.operators()).isEmpty();
    assertThat(channel.voiced()).isEmpty();
    assertThat(channel.revokeOperator(outsider)).isFalse();
    assertThat(channel.revokeVoice(outsider)).isFalse();
    channel.addMember(member("first"));
    ClientSession target = member("target");
    channel.addMember(target);
    assertThat(channel.grantOperator(target)).isTrue();
    assertThat(channel.grantVoice(target)).isTrue();
    assertThat(channel.grantOperator(target)).isTrue();
    assertThat(channel.grantVoice(target)).isTrue();
    assertThat(channel.operators()).contains(target);
    assertThat(channel.voiced()).containsExactly(target);
    assertThat(channel.revokeOperator(target)).isTrue();
    assertThat(channel.revokeVoice(target)).isTrue();
    assertThat(channel.revokeOperator(target)).isFalse();
    assertThat(channel.revokeVoice(target)).isFalse();
    assertThat(channel.operators()).doesNotContain(target);
    assertThat(channel.voiced()).isEmpty();
  }

  @Test
  void secretAndPrivateModesExcludeEachOtherWithoutClearingOtherModes() {
    Channel channel = new Channel("#c");
    channel.setMode(CoreChannelModes.MODERATED);
    channel.setMode(CoreChannelModes.PRIVATE);
    channel.setMode(CoreChannelModes.SECRET);
    assertThat(channel.activeModes())
        .containsExactlyInAnyOrder(CoreChannelModes.MODERATED, CoreChannelModes.SECRET);
    channel.setMode(CoreChannelModes.PRIVATE);
    assertThat(channel.activeModes())
        .containsExactlyInAnyOrder(CoreChannelModes.MODERATED, CoreChannelModes.PRIVATE);
    channel.clearMode(CoreChannelModes.PRIVATE);
    channel.clearMode(CoreChannelModes.SECRET);
    assertThat(channel.activeModes()).containsExactly(CoreChannelModes.MODERATED);
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = ' ',
      value = {"Alice aLICE", "a[b A{B", "a]b A}B", "a\\b A|B", "a^b A~B"})
  void invitationsFoldNicknamesAndAreConsumedOnce(String invited, String matching) {
    Channel channel = new Channel("#c");
    assertThat(channel.isInvited(matching)).isFalse();
    channel.invite(invited);
    channel.invite(matching);
    assertThat(channel.isInvited(matching)).isTrue();
    assertThat(channel.isInvited(invited)).isTrue();
    assertThat(channel.consumeInvitation(matching)).isTrue();
    assertThat(channel.isInvited(invited)).isFalse();
    assertThat(channel.consumeInvitation(invited)).isFalse();
  }

  @Test
  void modeExclusionUsesRecordValuesRatherThanObjectIdentity() {
    Channel channel = new Channel("#c");
    ChannelMode secret = CoreChannelModes.SECRET;
    ChannelMode privateMode = CoreChannelModes.PRIVATE;
    ChannelMode secretCopy =
        new ChannelMode(
            secret.id(), secret.flag(), secret.kind(), secret.gates(), secret.definedBy());
    ChannelMode privateCopy =
        new ChannelMode(
            privateMode.id(),
            privateMode.flag(),
            privateMode.kind(),
            privateMode.gates(),
            privateMode.definedBy());
    assertThat(secretCopy).isEqualTo(secret).isNotSameAs(secret);
    assertThat(privateCopy).isEqualTo(privateMode).isNotSameAs(privateMode);
    channel.setMode(secret);
    channel.setMode(privateCopy);
    assertThat(channel.activeModes()).containsExactly(privateMode);
    channel.setMode(secretCopy);
    assertThat(channel.activeModes()).containsExactly(secret);
  }

  @Test
  void namedMutatorsUpdateUnmodifiableLiveViews() {
    Channel channel = new Channel("#c");
    ClientSession target = member("target");
    var operators = channel.operators();
    var voiced = channel.voiced();
    var modes = channel.activeModes();
    var bans = channel.bans();
    channel.addMember(target);
    channel.grantVoice(target);
    channel.setMode(CoreChannelModes.MODERATED);
    BanEntry ban = new BanEntry("*!*@host", "setter", Instant.EPOCH);
    channel.addBan(ban);
    assertThat(operators).containsExactly(target);
    assertThat(voiced).containsExactly(target);
    assertThat(modes).containsExactly(CoreChannelModes.MODERATED);
    assertThat(bans).containsExactly(ban);
    channel.revokeOperator(target);
    channel.revokeVoice(target);
    channel.clearMode(CoreChannelModes.MODERATED);
    channel.removeBan(ban.mask());
    assertThat(operators).isEmpty();
    assertThat(voiced).isEmpty();
    assertThat(modes).isEmpty();
    assertThat(bans).isEmpty();
  }

  /** Pins the opposite serial order: grant operator status, then PART clears it. */
  @Test
  void operatorGrantBeforePartIsClearedByDeparture() throws Exception {
    ChannelRegistry registry = new ChannelRegistry();
    ClientSession target = member("target");
    Channel channel = registry.join("#c", target, ignored -> true).orElseThrow().channel();
    channel.revokeOperator(target);
    CountDownLatch granted = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var grant =
          executor.submit(
              () -> {
                try {
                  return channel.grantOperator(target);
                } finally {
                  granted.countDown();
                }
              });
      var part =
          executor.submit(
              () -> {
                await(granted);
                return registry.part(channel, target);
              });
      assertThat(grant.get(5, TimeUnit.SECONDS)).isTrue();
      assertThat(part.get(5, TimeUnit.SECONDS)).isPresent();
    }
    assertThat(channel.members()).doesNotContain(target);
    assertThat(channel.operators()).doesNotContain(target);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void underMonitorRunsHoldingTheChannelMonitorAndReturnsTheResult() {
    Channel channel = new Channel("#c");

    boolean held = channel.underMonitor(() -> Thread.holdsLock(channel));

    assertThat(held).isTrue();
    assertThat(Thread.holdsLock(channel)).isFalse();
  }

  @Test
  void underMonitorReleasesTheMonitorWhenTheActionThrows() throws Exception {
    Channel channel = new Channel("#c");

    assertThatThrownBy(
            () ->
                channel.underMonitor(
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    assertThat(Thread.holdsLock(channel)).isFalse();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertThat(
              executor.submit(() -> channel.underMonitor(() -> "entered")).get(5, TimeUnit.SECONDS))
          .isEqualTo("entered");
    }
  }

  @Test
  void runUnderMonitorHoldsTheChannelMonitor() {
    Channel channel = new Channel("#c");
    boolean[] held = new boolean[1];

    channel.runUnderMonitor(() -> held[0] = Thread.holdsLock(channel));

    assertThat(held[0]).isTrue();
    assertThat(Thread.holdsLock(channel)).isFalse();
  }

  @Test
  void setMemberPrivilegeGrantsAndRevokesForAMember() {
    Channel channel = new Channel("#c");
    ClientSession target = member("target");
    channel.addMember(target);
    channel.revokeOperator(target);

    assertThat(channel.setMemberPrivilege(target, Channel.MemberPrivilege.OPERATOR, true)).isTrue();
    assertThat(channel.operators()).containsExactly(target);
    assertThat(channel.setMemberPrivilege(target, Channel.MemberPrivilege.VOICE, true)).isTrue();
    assertThat(channel.voiced()).containsExactly(target);
    assertThat(channel.setMemberPrivilege(target, Channel.MemberPrivilege.OPERATOR, false))
        .isTrue();
    assertThat(channel.setMemberPrivilege(target, Channel.MemberPrivilege.VOICE, false)).isTrue();
    assertThat(channel.operators()).isEmpty();
    assertThat(channel.voiced()).isEmpty();
  }

  @Test
  void setMemberPrivilegeRevokeOfAnUnsetPrivilegeIsStillValidForAMember() {
    Channel channel = new Channel("#c");
    ClientSession target = member("target");
    channel.addMember(target);
    channel.revokeOperator(target);

    assertThat(channel.setMemberPrivilege(target, Channel.MemberPrivilege.OPERATOR, false))
        .isTrue();
    assertThat(channel.setMemberPrivilege(target, Channel.MemberPrivilege.VOICE, false)).isTrue();
  }

  @Test
  void setMemberPrivilegeRefusesANonMemberAndChangesNothing() {
    Channel channel = new Channel("#c");
    ClientSession outsider = member("outsider");

    for (Channel.MemberPrivilege privilege : Channel.MemberPrivilege.values()) {
      assertThat(channel.setMemberPrivilege(outsider, privilege, true)).isFalse();
      assertThat(channel.setMemberPrivilege(outsider, privilege, false)).isFalse();
    }
    assertThat(channel.operators()).isEmpty();
    assertThat(channel.voiced()).isEmpty();
  }

  private static ClientSession member(String nick) {
    ClientSession member = new ClientSession(nick, "host.example", RateLimitBucket.withDefaults());
    member.setNickname(nick);
    return member;
  }
}
