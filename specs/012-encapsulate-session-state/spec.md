# Feature Specification: Encapsulate Channel and Session State Transitions

**Feature Branch**: `012-encapsulate-session-state`

**Created**: 2026-10-16

**Status**: Draft

**Input**: User description: "GitHub issue #19 'Encapsulate channel and session state
transitions': Channel and ClientSession expose live mutable state collections while handlers
independently coordinate admission, membership, privileges and lifecycle. Introduce cohesive
operations for JOIN/PART/KICK/disconnect/NICK transitions, read-only snapshots/views, explicit
lock ownership and ordering, centralized recipient dedup and byte-aware reply construction, and
replace historical FR-commentary with current invariants. Incremental, coordinated with
correctness fixes, not a broad rewrite; observable behavior must stay identical (existing unit +
integration tests are the regression net)."

## Context

Every membership change today is assembled by its command handler out of separate steps on two
objects: the channel's member/operator/voice sets and the session's own list of channels. JOIN
goes through one locked registry operation, but PART, KICK, disconnect cleanup and SAJOIN/SAMODE
each perform their own sequence of "notify, remove from channel, remove from session, drop the
channel if empty", and channel/user mode handlers edit the underlying sets directly. Each step
is individually thread-safe; the sequence is not. The correctness bugs fixed under issues #10–#18
(nickname ownership, JOIN/empty-channel race, atomic member limits, case-mapped member lookup,
outbound byte budgets and NAMES splitting, idempotent repeated JOIN, NICK dedup, self-message
delivery) were each patched where they surfaced; this feature moves the rules those fixes
established into the channel and session themselves so that no caller can bypass them.

The audience for this change is twofold: IRC users, who must see no difference in behavior, and
maintainers, who need the state rules to live in one place.

## Clarifications

### Session 2026-10-16

- Q: When two concurrent operations target the same membership (two KICKs of one target, PART
  racing KICK), is a serial outcome acceptable, so that the losing operation now gets "not on
  that channel" where today a duplicate KICK/PART broadcast could occur? → A: Yes. Adopt the
  serial-outcome rule: only the operation that actually removed the member notifies, and the
  other receives the reply it would have received had it run second (FR-020).
- Q: After Step 2b moved the JOIN snapshot outside the guards, a joiner KICKed or disconnected
  during the snapshot could miss its own JOIN echo. What should a joiner receive? → A: Always
  echo the joiner's own JOIN. The joiner is unconditionally a JOIN recipient, added without
  taking any guard. NAMES shows the listing as read and is never patched to include the joiner
  (FR-001).
- Q: JOIN samples the joiner's presented (possibly cloaked) identity before taking any guard,
  and an administrator can toggle cloaking meanwhile. Which identity applies? → A: The JOIN line
  re-samples the presented identity after the join, outside all guards. The ban check keeps the
  identity sampled just before locking. This is an accepted relaxation: only JOINs racing an
  administrator's cloak toggle are affected (FR-022).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Membership stays consistent from both sides under concurrent traffic (Priority: P1)

A user is in several busy channels. While they part one channel, get kicked from another and
finally disconnect, other users are joining, parting and changing nicknames in the same channels
at the same moment. Afterwards, every channel's member list and the user's own view of which
channels they are in agree exactly; no channel lists a user who has left, no user believes they
are in a channel that no longer lists them, and no empty channel lingers.

**Why this priority**: This is the defect class the issue was raised for. A mismatch between
the two sides produces ghost members (messages delivered to, or NAMES listing, someone who has
left), lost notifications, and channels that never get cleaned up. Every other story builds on
membership changes being single, indivisible operations.

**Independent Test**: Drive JOIN, PART, KICK and disconnect for many sessions concurrently
against the same channels, then check that for every channel and every session the two views of
membership are mirror images, that the channel directory holds no empty channels, and that each
departure produced exactly one notification per remaining recipient.

**Acceptance Scenarios**:

1. **Given** a user who is a member of a channel, **When** they PART, **Then** every member
   present at that moment (including the user) receives the PART notification exactly once, and
   afterwards neither the channel nor the user records the membership.
2. **Given** a channel operator and a target member, **When** the operator KICKs the target,
   **Then** every member present at that moment receives the KICK notification exactly once, and
   the target's membership, operator status and voice status in that channel are all removed in
   the same step.
3. **Given** a user in several channels, **When** they disconnect (QUIT, connection loss, KILL
   or liveness timeout), **Then** they are removed from every channel, each channel left empty is
   removed from the channel directory, and each user who shared at least one channel with them
   receives one QUIT notification.
4. **Given** a user who is disconnecting, **When** a JOIN on behalf of that same user is being
   processed at the same time, **Then** once disconnect cleanup has finished the user is a member
   of no channel.
5. **Given** the last member of a channel is leaving, **When** another user joins the same
   channel name at the same time, **Then** exactly one channel of that name exists afterwards and
   the joining user is a member of it (preserving the fix for issue #12).
6. **Given** a channel at its member limit, **When** several users try to join at once,
   **Then** no more users are admitted than the limit allows (preserving the fix for issue #13).

---

### User Story 2 - Every visible behavior is unchanged (Priority: P1)

A user connects with their usual client and does what they always do: joins and parts channels,
sets modes, gets voiced and opped, kicks, invites, changes nickname, goes away, sends messages
and quits. Replies, notifications, their order, their recipients and their wire format are
exactly what they were before this change.

**Why this priority**: The issue explicitly asks for an incremental change that does not alter
behavior. A refactor that changes replies breaks clients and conformance results, which costs
more than the defect it fixes.

**Independent Test**: The full existing unit and integration suite, including the regression
tests for issues #10–#18 and the irctest conformance fixes, passes. No assertion about
client-visible behavior is edited. Unit tests whose setup drives the internal mutation methods
this feature removes are moved to the new operations and keep asserting the same invariant.

**Acceptance Scenarios**:

1. **Given** the existing automated test suite, **When** it runs after each incremental step of
   this feature, **Then** every test passes, every integration test unmodified, and every
   migrated unit test still asserting the invariant it asserted before.
2. **Given** a sequential (non-overlapping) sequence of commands, **When** it is replayed before
   and after this change, **Then** each client receives the same lines in the same order.

---

### User Story 3 - Channel-wide notices reach each user once and always fit on the wire (Priority: P2)

When a user changes their nickname, goes away, quits, or is the subject of any channel-wide
notice, each other user receives that notice once, however many channels they share. When the
server lists a channel's members, the list is split across as many lines as needed so that no
line exceeds the protocol's size limit, for any mix of nickname lengths and character sets.

**Why this priority**: Duplicate notices (issue #17) and oversized replies (issue #14) were both
fixed, but each fix lives in one handler and is re-implemented, or missing, elsewhere. Putting
these rules in one place stops the same bugs from coming back the next time a notice or list
reply is added.

**Independent Test**: For NICK, AWAY (to away-notify subscribers) and QUIT, have one user share
several channels with another and confirm one notice per recipient. For NAMES, fill a channel
with long multi-byte nicknames and confirm every reply line fits within the limit and the union
of the lines lists every member once.

**Acceptance Scenarios**:

1. **Given** two users who share three channels, **When** one changes nickname, **Then** the
   other receives one NICK notice and the changing user receives exactly one as well.
2. **Given** two users who share three channels and one has negotiated away-notify, **When**
   the other goes away, **Then** the subscriber receives one AWAY notice and the user going away
   receives none.
3. **Given** a channel whose member list cannot fit in one reply line, **When** any user
   requests or receives its member list (on JOIN, SAJOIN, NAMES or bare NAMES), **Then** the list
   is split into as many lines as needed, none over the limit, and the list is followed by
   exactly one end-of-list reply.

---

### User Story 4 - Maintainers can find and trust the state rules (Priority: P3)

A contributor fixing a bug in KICK, or adding a new command that changes membership or modes,
can find the rules for that state (who may change it, which lock protects it, the order locks
are taken in, what must hold after every change) documented in one place next to the state
itself, written as current invariants rather than as references to old feature numbers.

**Why this priority**: This does not change runtime behavior, but it is what keeps the first
three stories true as the code evolves. Without it, the next handler written will go back to
editing state directly.

**Independent Test**: Inspection: the channel, the session, and the channel directory each
document the invariants and lock ordering they own; outside them, no code changes membership,
privilege, mode, ban, invitation or user-mode state directly (verifiable by search and by the
compiler, since the collections are no longer exposed for mutation).

**Acceptance Scenarios**:

1. **Given** the source after this change, **When** a reviewer searches for code that changes
   channel or session state collections, **Then** every such change goes through a named
   operation of the channel, the session, or the channel directory.
2. **Given** the classes and handlers touched by this feature, **When** a reviewer reads their
   documentation, **Then** it states current invariants and does not justify current behavior
   by citing historical feature or requirement numbers.

### Edge Cases

- **Disconnect racing JOIN** (same session): cleanup must not leave the session as a member of a
  channel it joined while closing.
- **Two operators KICK the same target at once**: the target is removed once. Only the KICK
  that actually removed the target is broadcast, and the other operator receives the "not on that
  channel" reply (see Clarifications). Today both may be broadcast.
- **PART racing KICK of the same user**: exactly one departure is recorded and notified.
- **Duplicate disconnect triggers** (QUIT racing socket close): cleanup runs once (the existing
  idempotency guard stays).
- **NICK change while a third user is joining a shared channel**: the joiner ends up knowing the
  new nickname, either because the member list shows it or because they receive the NICK notice
  (possibly both). They never end up with only the old nickname.
- **Case-only nickname change** (`Bob` → `BOB`): nickname ownership is kept (issue #10).
- **First joiner of a freshly re-created channel** gets operator status; a channel that emptied
  and was re-created starts with fresh topic, key, limit, modes, bans and creation time.
- **Repeated JOIN of a channel the user is already in**: no change and no notification (issue #16).
- **Members whose connection has no writer yet or is already closing**: they are skipped for
  delivery, as today, without aborting delivery to anyone else.
- **Mode `+s` and `+p` together**: setting one clears the other, as today, as one change.
- **A user who is both operator and voiced** is listed with the operator prefix only.
- **A single member token that cannot fit even on an empty reply line**: behavior stays what it
  is today (the case is unreachable within configured nickname/channel length limits).

## Requirements *(mandatory)*

### Functional Requirements

**Cohesive transitions**

- **FR-001**: Joining a channel MUST be a single operation that, as one indivisible step,
  resolves or creates the channel, evaluates admission (ban, invite-only, member limit, key,
  invitation exemption and its one-time consumption), adds the membership on both the channel
  and the session sides, and grants operator status to the first member. The members to notify
  and the NAMES listing are read right after that step, with no channel or directory guard held,
  so that a large channel's O(members) work never blocks other channels (FR-021). Like the
  pre-feature code, both are weakly consistent reads after the join. Unlike it, the listing is
  read before the JOIN fan-out. The joiner is always a recipient of its own JOIN; the NAMES
  listing is shown as read and never patched to include the joiner (Clarifications). Research
  R10 lists the resulting outcomes.
- **FR-002**: Leaving a channel (PART) and being removed from it (KICK) MUST each be a single
  operation that, as one indivisible step, verifies the member is present, captures the members
  to notify, removes membership, operator status and voice status from the channel, removes the
  channel from the session's memberships, and removes the channel from the directory if it is
  now empty.
- **FR-003**: Disconnect cleanup MUST remove the session from all its channels using the same
  leave operation as FR-002, MUST compute the de-duplicated set of users who shared any channel
  with it, and MUST guarantee that once cleanup completes the session belongs to no channel,
  even if a JOIN for that session was in progress at the same time.
- **FR-004**: A session whose disconnect has begun MUST NOT be admitted to any channel.
- **FR-005**: A nickname change MUST capture the recipients of the NICK notice (the changing
  user plus every user sharing any channel with them, each once) consistently with the change
  itself, so that each recipient sees either the old or the new nickname as the current one, as
  described in Edge Cases.
- **FR-006**: Operator/voice grants and revocations, channel mode changes (including the `+s`/
  `+p` mutual exclusion), ban additions/removals (including the 100-entry cap), invitation
  recording and consumption, and user-mode changes MUST each be a named operation on the state
  owner, not a direct edit of an exposed collection by the caller.
- **FR-007**: Administrative transitions (SAJOIN, SAMODE) MUST use the same operations as their
  user-facing counterparts, differing only in skipping the checks they are allowed to bypass.
- **FR-008**: Every transition operation MUST return enough information for the caller to send
  notifications and replies without re-reading live state (for example the captured recipient
  set and whether operator status was granted). For JOIN, the returned recipients and listing
  are read after the guards are released (FR-001).
- **FR-021**: The channel-directory guard, which every channel lookup also takes, MUST NOT be
  held for work that grows with a channel's member count, or while extension code runs, during
  JOIN. The JOIN admission check gets the joiner's presented identity computed before any guard
  is taken.
- **FR-022**: The JOIN line MUST use the joiner's presented identity sampled after the join,
  outside all guards. The ban check MAY use the identity sampled just before the guards were
  taken. A JOIN that races an administrator enabling or disabling cloaking is therefore checked
  against the earlier identity. This is an accepted relaxation of the live-check rule for that
  race only (Clarifications).

**Read-only access**

- **FR-009**: Code outside the channel, the session and the channel directory MUST NOT be able
  to change channel membership, operator, voice, mode, ban, or invitation state, or a session's
  channel memberships or user modes, through any accessor. Accessors MUST return read-only
  views or snapshots.
- **FR-010**: Reads on hot paths (message fan-out, membership checks) MUST NOT copy the
  member collection per message; transitions that need a stable recipient set MUST capture
  one snapshot per transition.

**Lock ownership and ordering**

- **FR-011**: Each piece of mutable channel/session state MUST have exactly one documented
  owner and guard: the channel directory guards channel existence (creation and removal by
  name); each channel guards its own membership, privileges, modes, bans, invitations and
  limits; a session's channel-membership set is changed only by the transition that holds the
  corresponding channel's guard.
- **FR-012**: Guards MUST be acquired in one documented global order — channel directory before
  channel — and no operation may hold the guards of two channels at once or acquire the
  directory guard while holding a channel guard. The order MUST be documented where the
  guards are declared.
- **FR-013**: Guards MUST NOT be held while delivering to a client's outbound queue or while
  calling extension code other than the non-blocking admission check; delivery happens after
  the transition using the captured recipient set.

**Recipient de-duplication and reply construction**

- **FR-014**: Computing "everyone who shares a channel with this user", optionally including
  the user and optionally filtered (for example by negotiated capability), MUST be provided once
  and used by NICK, AWAY notification and disconnect QUIT. Each recipient appears at most once;
  delivery order among recipients is not guaranteed, as today.
- **FR-015**: Fanning a notice out to a captured recipient set, skipping recipients with no
  active outbound queue, MUST be provided once and used by JOIN, SAJOIN, PART, KICK, NICK, QUIT
  and the channel-wide MODE/SAMODE/TOPIC notices.
- **FR-016**: Building a reply that lists items (such as member names with status prefixes)
  across as many lines as needed under the protocol's per-line byte limit MUST be provided once,
  measure size in encoded bytes, never split an item, and be used by every member-list reply
  (JOIN, SAJOIN, NAMES and bare NAMES).

**Documentation and incremental delivery**

- **FR-017**: The channel, the session and the channel directory MUST each document the
  invariants they maintain and the guard that protects them. In the code touched by this
  feature, comments that justify behavior by citing historical feature or requirement numbers
  MUST be replaced by a statement of the current invariant or rule.
- **FR-018**: The change MUST be delivered as a sequence of independently mergeable steps, each
  of which keeps the full existing test suite green. Integration tests MUST NOT be modified. A
  unit test MAY be changed only to move its setup or choreography off a removed internal method,
  and MUST keep asserting the same invariant.
- **FR-019**: Each new invariant (FR-001–FR-005, FR-012) MUST be covered by a deterministic
  automated test written before the change that establishes it, including concurrent-interleaving
  tests for the races listed in Edge Cases.
- **FR-020**: When two concurrent operations target the same membership (two KICKs of one
  target, PART racing KICK), the outcome MUST be as if they ran one after the other: only the
  operation that actually removed the member notifies, and the other receives the reply it would
  have received had it run second. This is the only intended client-visible difference, it
  arises only under these races, and it removes a duplicate KICK/PART broadcast (confirmed in
  Clarifications, 2026-10-16).

### Key Entities

- **Channel**: a named group of members. Owns membership, operator and voice sets, active
  modes, ban list, pending invitations, topic, key, member limit and creation time. Invariants:
  every operator or voiced user is a member; a member listed by the channel lists the channel
  among its own memberships; the ban list never exceeds its cap; `+s` and `+p` are never both
  set.
- **Client Session**: one connected client. Owns its nickname, user modes, channel-membership
  set, capabilities and lifecycle state. Invariant: once its disconnect has completed it belongs
  to no channel and holds no nickname.
- **Channel Directory** (channel registry): the server-wide mapping from case-folded channel
  name to channel. Invariant: it never holds an empty channel after a transition completes, and
  never holds two channels whose names fold to the same value.
- **Transition Result**: what a join/leave/kick/nick transition reports back — the captured
  recipient set, whether it took effect, and for joins whether operator status was granted.
- **Recipient Set**: an ordered, duplicate-free set of sessions to deliver one notice to.
- **Listed Reply**: a reply whose final parameter is a space-separated list split across lines
  under the per-line byte limit.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the existing unit and integration tests pass after every step. Zero
  integration tests are modified, and every migrated unit test asserts the invariant it asserted
  before.
- **SC-002**: In a stress run of at least 1,000 concurrent join/part/kick/disconnect operations
  across shared channels, 0 membership mismatches between channels and sessions, 0 empty
  channels left in the directory, and 0 duplicate or missing departure notices are observed.
- **SC-003**: Outside the channel, session and channel directory, 0 places in the production
  source change membership, privilege, mode, ban, invitation or user-mode state directly.
- **SC-004**: Recipient de-duplication and listed-reply construction each exist in exactly one
  place, and every user listed in FR-014–FR-016 calls it.
- **SC-005**: All load tests pass their asserted bounds (including <1s per message delivery);
  Gradle durations are recorded for information only.
- **SC-006**: In the files touched by this feature, 0 comments justify behavior solely by citing
  a historical feature or requirement number.
- **SC-007**: In the JOIN-storm load test from #48 (2,000 clients join one channel in waves of
  100 while messages between two users in a quiet channel are timed), measured over at least 7
  alternating runs per variant (main and feature branch) on an otherwise idle machine, the
  feature branch's **median** storm completion time and **median** quiet-channel p50 latency are
  each within 10% of main's. The quiet-channel p99 and max only need to stay within the test's
  own bounds (500 ms / 1 s). Each run measures only about 18 quiet-channel messages, so a run's
  p99 is effectively its slowest sample and not a stable statistic.

## Assumptions

- Channel, session and channel-directory types are used only inside this repository (core,
  capability and server-extension modules); there is no third-party extension API to keep
  source-compatible, so in-repo callers are updated in the same step that narrows an accessor.
- The correctness issues this work must be coordinated with (#10–#18) are already fixed on main;
  their regression tests are part of the safety net and must keep passing. No other open
  correctness issue touches this state.
- Outbound queue delivery is non-blocking (a full queue triggers the existing overflow
  handling), so delivering after a transition does not change the order in which a single
  client sees lines.
- Comment cleanup (FR-017) is limited to files this feature already touches; a repository-wide
  sweep of historical references is out of scope.
- Server-to-server federation, new commands, and new replies are out of scope.
- No new user-facing operation is added, so no new latency budget is defined; the existing load
  tests' budgets apply (SC-005). The #48 JOIN-storm test showed a measurable JOIN regression at
  step 2 (storm +~45%, quiet-channel p99/max 171–277 ms vs 77–157 ms on main), so SC-007 restores
  a measured, main-relative criterion for JOIN.
