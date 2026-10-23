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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.jircd.protocol.NickMask;

/**
 * A named, joinable group through which members exchange messages. {@code name} is its identity;
 * membership and moderation state are guarded by this aggregate. The mutators of the member,
 * operator, voice, mode, ban and invitation collections are {@code synchronized} on this monitor;
 * {@code topic} is an atomic snapshot; {@code memberLimit} and {@code key} are single-field
 * volatile values with unsynchronized setters. Lock order: an operation that needs both monitors
 * takes the {@link ChannelRegistry} monitor first, one that needs only this monitor never takes the
 * registry monitor while holding it, and no operation holds two channel monitors.
 *
 * <p>C-1: {@code operators} and {@code voiced} are subsets of {@code members}; granting either to a
 * non-member is refused and changes nothing. C-2: {@code secret} and {@code private} are never both
 * active; setting one clears the other in the same step. C-3: at most {@link #MAX_BANS} entries per
 * mask list, with unique masks under RFC1459 case folding. C-4: the first member added to an empty
 * channel becomes an operator. C-5: {@link #memberListing} and {@link #memberFacts()} are weakly
 * consistent traversals taken without the monitor. They never list a session twice but are not a
 * snapshot of one instant. Each member's statuses, however, come from one consistent view of the
 * operator and voice sets (see {@link #heldStatuses}), so they never show a status combination the
 * member did not hold.
 *
 * <p>The collection accessors return unmodifiable views. Membership changes arrive only through
 * {@link ChannelRegistry} transitions.
 */
public final class Channel {

  public static final int MAX_BANS = 100;

  private static final List<String> OPERATOR = List.of("@");
  private static final List<String> VOICE = List.of("+");
  private static final List<String> OPERATOR_AND_VOICE = List.of("@", "+");

  private final String name;
  private final String foldedName;
  private final Set<ClientSession> members = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> membersView = Collections.unmodifiableSet(members);
  private final Set<ClientSession> operators = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> operatorsView = Collections.unmodifiableSet(operators);
  private final Set<ClientSession> voiced = ConcurrentHashMap.newKeySet();
  private final Set<ClientSession> voicedView = Collections.unmodifiableSet(voiced);

  /**
   * Odd while a privilege update is in progress; changes with every update. Written under the
   * monitor.
   */
  private final AtomicLong privilegeVersion = new AtomicLong();

  private final Set<ChannelMode> activeModes = ConcurrentHashMap.newKeySet();
  private final Set<ChannelMode> activeModesView = Collections.unmodifiableSet(activeModes);
  private final List<BanEntry> bans = new CopyOnWriteArrayList<>();
  private final List<BanEntry> bansView = Collections.unmodifiableList(bans);
  private final List<BanEntry> banExceptions = new CopyOnWriteArrayList<>();
  private final List<BanEntry> inviteExceptions = new CopyOnWriteArrayList<>();
  private final Set<String> invited = ConcurrentHashMap.newKeySet();

  /** An immutable snapshot of a topic and the identity and Unix time captured when it was set. */
  public record Topic(String text, String setter, long setAt) {}

  private final AtomicReference<Topic> topic = new AtomicReference<>();
  private volatile int memberLimit;
  private volatile String key;
  private final Instant createdAt = Instant.now();

  public Channel(String name) {
    this.name = name;
    this.foldedName = CaseMapping.fold(name);
  }

  public String name() {
    return name;
  }

  /**
   * When this channel instance was created. A field initializer, not a constructor parameter, so a
   * zero-member channel recreated as a fresh {@code Channel} starts with a new value, the same way
   * {@code topic}, {@code memberLimit} and {@code key} reset.
   */
  public Instant createdAt() {
    return createdAt;
  }

  public Set<ClientSession> members() {
    return membersView;
  }

  /**
   * Returns one status-prefixed nickname per member, in member iteration order, each with its
   * highest status only — the form an ordinary requester gets. A capability that renders
   * differently starts from {@link #memberFacts()} instead. Reads the concurrent sets without this
   * monitor, so a NAMES reply on a large channel never delays a transition waiting for it while
   * holding the registry monitor. The traversal never visits a session twice, but it is not a
   * snapshot of one instant: concurrent joins and departures may or may not appear. A member's
   * statuses are read consistently, as in {@link #heldStatuses}.
   */
  public List<String> memberListing() {
    List<String> listing = new ArrayList<>();
    for (ClientSession member : members) {
      List<String> held = heldStatuses(member);
      listing.add((held.isEmpty() ? "" : held.getFirst()) + member.nickname());
    }
    return listing;
  }

  /**
   * One {@link MemberFacts} per member in member iteration order, with the same weak consistency as
   * {@link #memberListing()}: unformatted facts that carry no knowledge of any capability.
   */
  public List<MemberFacts> memberFacts() {
    List<MemberFacts> facts = new ArrayList<>();
    for (ClientSession member : members) {
      facts.add(new MemberFacts(member, heldStatuses(member)));
    }
    return facts;
  }

  /** The facts of one member; no statuses for a session that is not a member. */
  public MemberFacts memberFacts(ClientSession member) {
    return new MemberFacts(member, heldStatuses(member));
  }

  /**
   * The status symbols a member holds, highest first and in {@code PREFIX} order: {@code @} for
   * operator, {@code +} for voice. Both sets are read as one view without taking the monitor, so a
   * listing never waits for a transition: every privilege update (made under the monitor) brackets
   * itself with {@code privilegeVersion} changes, and the read retries when an update was in
   * progress or finished meanwhile. A concurrent "-o then +v" can therefore never show {@code @+}
   * for a member who never held both. An update only adds to or removes from the sets, so the retry
   * wait is short and never calls out to a session.
   */
  public List<String> heldStatuses(ClientSession member) {
    boolean operator;
    boolean voice;
    while (true) {
      long before = privilegeVersion.get();
      operator = operators.contains(member);
      voice = voiced.contains(member);
      if ((before & 1) == 0 && privilegeVersion.get() == before) {
        break;
      }
      Thread.onSpinWait();
    }
    if (operator) {
      return voice ? OPERATOR_AND_VOICE : OPERATOR;
    }
    return voice ? VOICE : List.of();
  }

  public Set<ClientSession> operators() {
    return operatorsView;
  }

  public Set<ClientSession> voiced() {
    return voicedView;
  }

  public Set<ChannelMode> activeModes() {
    return activeModesView;
  }

  public List<BanEntry> bans() {
    return bansView;
  }

  /** Adds an invitation using IRC case folding; repeated invitations remain a single entry. */
  public synchronized void invite(String nickname) {
    invited.add(CaseMapping.fold(nickname));
  }

  /** Reports whether this nickname has a pending invitation, using IRC case folding. */
  public synchronized boolean isInvited(String nickname) {
    return invited.contains(CaseMapping.fold(nickname));
  }

  /** Consumes a pending invitation once, returning whether it existed. */
  public synchronized boolean consumeInvitation(String nickname) {
    return invited.remove(CaseMapping.fold(nickname));
  }

  /**
   * Runs {@code action} holding this channel's monitor and returns its result; the monitor is
   * released when the action returns or throws. The action reads and changes state guarded by this
   * channel and, when the {@link ChannelRegistry} monitor is already held by the caller, the
   * registry-owned transition state (session membership, registry map). It never acquires the
   * registry monitor for the first time, so a channel-only action must not call registry
   * operations, and it never takes another channel's monitor. It does not deliver messages, call
   * extension code or block.
   */
  synchronized <T> T underMonitor(Supplier<T> action) {
    return action.get();
  }

  /** As {@link #underMonitor(Supplier)}, for an action with no result. */
  synchronized void runUnderMonitor(Runnable action) {
    action.run();
  }

  /** The outcome of {@link #applyAsOperator}. */
  public enum ModeChangeResult {
    /** The change was applied. */
    APPLIED,
    /** The requester is not an operator of the channel, so nothing changed. */
    NOT_OPERATOR,
    /** A privilege change named a target that is not a member, so nothing changed. */
    NOT_A_MEMBER,
    /** A ban was refused because the ban list is full, so nothing changed. */
    BAN_LIST_FULL
  }

  /**
   * Applies {@code change} on behalf of {@code requester} in one step under this channel's monitor:
   * the requester's operator status is checked and the change made with no gap between them, so a
   * de-op or departure of the requester cannot fall in between. A requester who is not an operator
   * at that moment, including one who has left, changes nothing.
   */
  public synchronized ModeChangeResult applyAsOperator(
      ClientSession requester, ChannelModeChange change) {
    Objects.requireNonNull(change);
    if (!operators.contains(requester)) {
      return ModeChangeResult.NOT_OPERATOR;
    }
    return switch (change) {
      case ChannelModeChange.Flag(ChannelMode mode, boolean set) -> {
        setFlag(mode, set);
        yield ModeChangeResult.APPLIED;
      }
      case ChannelModeChange.Limit(int limit) -> {
        setMemberLimit(limit);
        yield ModeChangeResult.APPLIED;
      }
      case ChannelModeChange.Key(String newKey) -> {
        setKey(newKey);
        yield ModeChangeResult.APPLIED;
      }
      case ChannelModeChange.ListAdd(ChannelMode mode, BanEntry entry) ->
          addMask(mode, entry) ? ModeChangeResult.APPLIED : ModeChangeResult.BAN_LIST_FULL;
      case ChannelModeChange.ListRemove(ChannelMode mode, String mask) -> {
        removeMask(mode, mask);
        yield ModeChangeResult.APPLIED;
      }
      case ChannelModeChange.Privilege(
              ClientSession target,
              MemberPrivilege privilege,
              boolean granted) ->
          setMemberPrivilege(target, privilege, granted)
              ? ModeChangeResult.APPLIED
              : ModeChangeResult.NOT_A_MEMBER;
    };
  }

  private void setFlag(ChannelMode mode, boolean set) {
    if (set) {
      setMode(mode);
    } else {
      clearMode(mode);
    }
  }

  /** A per-member channel privilege. */
  public enum MemberPrivilege {
    OPERATOR,
    VOICE
  }

  /**
   * Grants or revokes a privilege for a current member in one step under this channel's monitor.
   * Returns {@code false} and changes nothing for a non-member; for a member it returns {@code
   * true}, including a revoke of a privilege the member does not hold.
   */
  public synchronized boolean setMemberPrivilege(
      ClientSession member, MemberPrivilege privilege, boolean granted) {
    Objects.requireNonNull(privilege);
    if (!members.contains(member)) {
      return false;
    }
    Set<ClientSession> holders =
        switch (privilege) {
          case OPERATOR -> operators;
          case VOICE -> voiced;
        };
    updateHolders(holders, member, granted);
    return true;
  }

  /** Grants operator status to a current member; returns false only for a non-member. */
  public synchronized boolean grantOperator(ClientSession member) {
    if (!members.contains(member)) {
      return false;
    }
    updateHolders(operators, member, true);
    return true;
  }

  /** Removes operator status, returning whether it was present. */
  public synchronized boolean revokeOperator(ClientSession member) {
    return updateHolders(operators, member, false);
  }

  /** Grants voice to a current member; returns false only for a non-member. */
  public synchronized boolean grantVoice(ClientSession member) {
    if (!members.contains(member)) {
      return false;
    }
    updateHolders(voiced, member, true);
    return true;
  }

  /** Removes voice, returning whether it was present. */
  public synchronized boolean revokeVoice(ClientSession member) {
    return updateHolders(voiced, member, false);
  }

  /** Activates a mode, clearing PRIVATE for SECRET and SECRET for PRIVATE in the same step. */
  public synchronized void setMode(ChannelMode mode) {
    if (CoreChannelModes.SECRET.equals(mode)) {
      activeModes.remove(CoreChannelModes.PRIVATE);
    } else if (CoreChannelModes.PRIVATE.equals(mode)) {
      activeModes.remove(CoreChannelModes.SECRET);
    }
    activeModes.add(mode);
  }

  /** Clears a mode; clearing an inactive mode is a no-op. */
  public synchronized void clearMode(ChannelMode mode) {
    activeModes.remove(mode);
  }

  public String topic() {
    Topic current = topic.get();
    return current == null ? null : current.text();
  }

  public Topic topicState() {
    return topic.get();
  }

  /** An empty or absent topic clears its setter and timestamp as well. */
  public void setTopic(String text, String setter, long setAt) {
    topic.set(
        text == null || text.isEmpty()
            ? null
            : new Topic(text, Objects.requireNonNull(setter, "setter"), setAt));
  }

  /**
   * The {@code user-limit} channel mode's current value. {@code 0} means unset (no limit),
   * mirroring {@code topic}'s {@code null}-means-unset shape but using {@code 0} since a real limit
   * is always positive.
   */
  public int memberLimit() {
    return memberLimit;
  }

  public void setMemberLimit(int memberLimit) {
    this.memberLimit = memberLimit;
  }

  /** The {@code channel-key} channel mode's current value. {@code null} means unset. */
  public String key() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public boolean isEmpty() {
    return members.isEmpty();
  }

  /**
   * Adds a member, granting operator status if this is the channel's very first member (classic
   * first-join-gets-operator default). Returns {@code true} if operator status was granted by this
   * call.
   */
  synchronized boolean addMember(ClientSession session) {
    boolean wasEmpty = members.isEmpty();
    members.add(session);
    if (wasEmpty) {
      updateHolders(operators, session, true);
    }
    return wasEmpty;
  }

  /** Removes a departing session from membership, operator, and voice state together. */
  synchronized void removeMember(ClientSession session) {
    members.remove(session);
    privilegeVersion.incrementAndGet();
    try {
      operators.remove(session);
      voiced.remove(session);
    } finally {
      privilegeVersion.incrementAndGet();
    }
  }

  /** Adds to or removes from a privilege set, bracketed for {@link #heldStatuses}' retry. */
  private boolean updateHolders(Set<ClientSession> holders, ClientSession member, boolean add) {
    privilegeVersion.incrementAndGet();
    try {
      return add ? holders.add(member) : holders.remove(member);
    } finally {
      privilegeVersion.incrementAndGet();
    }
  }

  /**
   * Lookup of a current member by nickname, folded with the same rfc1459 casemapping as {@link
   * NicknameRegistry}.
   */
  public Optional<ClientSession> findMember(String nickname) {
    String folded = CaseMapping.fold(nickname);
    return members.stream().filter(m -> CaseMapping.fold(m.nickname()).equals(folded)).findFirst();
  }

  /**
   * Adds a ban mask, enforcing the 100-entry cap atomically — {@code true} if the mask is now
   * present (including already-present under RFC1459 case folding, a no-op success), {@code false}
   * if adding it would exceed the cap.
   */
  public synchronized boolean addBan(BanEntry entry) {
    return addMask(CoreChannelModes.BAN_MASK, entry);
  }

  /** Adds a mask with the same per-list cap and duplicate semantics as bans. */
  private synchronized boolean addMask(ChannelMode mode, BanEntry entry) {
    List<BanEntry> masks = mutableMasks(mode);
    String folded = CaseMapping.fold(entry.mask());
    if (masks.stream().anyMatch(b -> CaseMapping.fold(b.mask()).equals(folded))) {
      return true;
    }
    if (masks.size() >= MAX_BANS) {
      return false;
    }
    masks.add(entry);
    return true;
  }

  /** Removes a ban mask using RFC1459 case folding — a silent no-op if not present. */
  public synchronized void removeBan(String mask) {
    removeMask(CoreChannelModes.BAN_MASK, mask);
  }

  private synchronized void removeMask(ChannelMode mode, String mask) {
    String folded = CaseMapping.fold(mask);
    mutableMasks(mode).removeIf(b -> CaseMapping.fold(b.mask()).equals(folded));
  }

  /** Returns an unmodifiable, safely traversable view of a core mask list. */
  public List<BanEntry> masks(ChannelMode mode) {
    return Collections.unmodifiableList(mutableMasks(mode));
  }

  private List<BanEntry> mutableMasks(ChannelMode mode) {
    return switch (mode.flag()) {
      case 'b' -> bans;
      case 'e' -> banExceptions;
      case 'I' -> inviteExceptions;
      default -> throw new IllegalArgumentException("not a core mask list: " + mode.flag());
    };
  }

  /** Matches either the presented or real identity against the given mask list. */
  public boolean matchesMask(ChannelMode mode, String presented, String real) {
    return mutableMasks(mode).stream()
        .anyMatch(
            entry ->
                NickMask.matches(presented, entry.mask()) || NickMask.matches(real, entry.mask()));
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof Channel other && foldedName.equals(other.foldedName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(foldedName);
  }
}
