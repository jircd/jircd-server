# Research: Encapsulate Channel and Session State Transitions

The Technical Context had no unresolved unknowns, since stack, test tooling and budgets all come
from the existing project. This file records the design decisions behind the plan and the
alternatives that were rejected.

## R1. Which lock serializes membership transitions?

**Decision**: The `ChannelRegistry` monitor serializes every membership transition (join, part,
kick, disconnect leave). Inside it, the target `Channel` monitor is taken for the membership
change itself. Global order: **registry → channel**. Never two channel monitors at once. Never
the registry monitor while holding a channel monitor.

**Rationale**:
- `join` already holds the registry monitor across get-or-create, admission and insertion
  (issue #12 fix). Leave operations already take it for `removeIfEmpty`. Taking it once for the
  whole leave removes the window between "member removed" and "empty channel dropped" with no
  new lock.
- Disconnect-vs-JOIN (FR-003/FR-004) needs a point both sides synchronize on. Cleanup sets
  `CLOSING` first, then takes the registry monitor to snapshot and leave its channels. A JOIN
  that holds the monitor first finishes and is seen by the snapshot. A JOIN that runs later sees
  `CLOSING` and is refused.
- Two KICKs of one target, or PART racing KICK (FR-020), become serial for free: the second
  operation finds the member gone.
- Contention: `lookup` is already `synchronized` on the registry and runs on every channel
  PRIVMSG, so the registry monitor is already on the hot path. Transitions are rare compared
  with messages, and the added work under the lock is O(members) set updates and one snapshot
  copy.

**Alternatives considered**:
- *Per-channel lock only, registry lock just for existence*: fixes the add/remove asymmetry
  but leaves the disconnect-vs-JOIN leak (cleanup's membership snapshot can miss a JOIN that
  checked `CLOSING` before it was set) and still needs the registry lock for empty-channel
  removal. Two acquisitions per leave and a harder proof.
- *Read/write lock or `StampedLock` on the registry*: allows parallel lookups but adds
  complexity with no measured need (Constitution: no speculative generality). It can come back
  if a load test shows registry contention.
- *Lock-free (CAS on immutable membership snapshots)*: much larger rewrite, which the issue
  rules out.

## R2. Read-only views or snapshots?

**Decision**: Accessors return **unmodifiable live views** (`Collections.unmodifiableSet/List`
over the existing concurrent collections). Transitions return **snapshots** (immutable copies)
of the recipients they captured under the lock.

**Rationale**: FR-010 forbids a per-message copy on fan-out. A view is zero-copy and keeps the
weakly-consistent iteration that callers already rely on. Snapshots are needed only where a
recipient set must agree with a state change, and that happens once per transition.

**Alternatives**: `Set.copyOf` on every accessor call, rejected because it allocates on every
PRIVMSG to a channel. Exposing iteration callbacks (`forEachMember`), rejected because it
changes every read call site for no safety gain over a view.

## R3. Where do the transitions live?

**Decision**: On `ChannelRegistry` (`join`, `part`, `kick`, `leaveAll`). `Channel.addMember`,
`Channel.removeMember`, `ClientSession.addMembership` and `ClientSession.removeMembership`
become package-private in `net.jircd.core.session`.

**Rationale**: The registry already owns the lock that must be held, and it is already in
`ServerContext` for extensions (SAJOIN). Package-private mutators let the compiler enforce
FR-009 against `.command` handlers and the admin module. No new service class is needed.

**Alternatives**: A new `MembershipService`, rejected as an extra type and wiring for no
benefit over the registry, which already has the lock and the callers. Methods on `Channel`
that call back into the registry, rejected because they would invert the lock order.

## R4. Behavior preservation for notification recipients

**Decision**: Recipients are captured inside the transition so that they match current
sequential behavior exactly:
- JOIN: members **after** insertion (joiner included). Same as today's post-join iteration.
- PART: members **before** removal (parter included).
- KICK: members **before** removal (target included).
- QUIT: union of members of every channel left, minus the quitter, captured per channel
  **after** removal (today's `neighbors.addAll(channel.members())` after `removeMember`).
- NICK: the changer plus `Recipients.sharedChannelNeighbors`, computed after `setNickname`.
  No registry lock is needed. `nickname` is volatile and is written before recipients are
  read, so a concurrent joiner either is in the snapshot and gets the NICK, or joined after it
  and reads the new nickname in NAMES (spec edge case).
- AWAY: neighbours that negotiated `away-notify`, excluding self (unchanged filter).

Delivery order to any one client is unchanged. Each handler still enqueues the notification
before its own numeric replies. JOIN's recipients and NAMES listing are read right after the
transition, outside the monitors (amended by R10; that is also how JOIN worked before this
feature).

## R5. Byte-aware listed replies

**Decision**: `Replies.sendListed(session, serverName, numeric, List<String> fixedParams,
List<String> items)` packs space-separated items into the final parameter. The budget is
computed exactly as `sendNamesLine` computes it today (`MAX_COMMAND_BYTES - commandBytes(empty
reply)`, trailing colon reserved). Items are never split, and an empty item list still emits one
line with an empty trailing parameter (current NAMES-of-empty behavior). `Channel.memberListing()`
returns `@nick`/`+nick`/`nick` tokens in member iteration order under the channel monitor.

**Rationale**: This is the only list reply today (issue #14). Putting it in `Replies` makes it
available to future list replies (RPL_WHOISCHANNELS, which is unused today) without another
copy. No new protocol-module API is needed: `MessageSerializer.commandBytes` and `utf8Bytes`
are already public.

## R6. Recipient de-duplication helper

**Decision**: `Recipients.sharedChannelNeighbors(ClientSession, boolean includeSelf,
Predicate<ClientSession> filter)` returns a `LinkedHashSet` (self first when included, then
neighbours in membership/member iteration order, matching today's NICK code).
`Recipients.deliver(Collection<ClientSession>, Message)` enqueues raw to each session whose
writer is non-null and carries the single documented `PMD.CloseResource` suppression for
borrowed writers.

**Alternatives**: Putting dedup on `ClientSession`, rejected because it would make the session
walk channel member sets, which is cross-aggregate logic, and that belongs outside both
aggregates.

## R7. Performance verification

**Decision**: Run `./gradlew loadTest` on `main` and on the step-2 and step-4 heads, and record
the results in the PRs (Quality Gate 5). All load tests must pass their asserted bounds
(including <1s per message delivery); Gradle durations are recorded for information only
(SC-005). These tests emit no measured latency or throughput figures.

**Rationale**: Step 2 lengthens the registry critical section for PART/KICK/QUIT and adds one
recipient copy per transition. The messaging hot path only reads views. Measuring is required
by Constitution IV.

## R8. Coordination with correctness issues and comment cleanup

**Decision**: Issues #10–#18 are closed on main, and their regression tests (`RepeatedJoinTest`,
`NickBroadcastTest`, `OutboundByteBudgetTest`, `ChannelRegistryTest` races,
`SelfDirectMessageTest`, …) form the safety net. They are not reworked except where unit-test
choreography uses removed internal methods. Historical `NNN-feature FR-xxx` citations are
rewritten as present-tense invariants **only in files this feature touches**. Spec Assumptions
put a repository-wide sweep out of scope (185 references across about 50 files).

## R10. JOIN must not do O(members) work under the registry monitor (FR-021, SC-007)

**Evidence**: the #48 `JoinStormLoadTest` (2,000 clients JOIN `#storm` in waves of 100 while
A→B PRIVMSG on `#quiet` is timed):

| Build | Storm time | `#quiet` p99/max |
|---|---|---|
| main | 2.17–2.26 s | 77–157 ms |
| step 2 (`7a80ef6`) | 3.06–3.36 s (+~45%) | 171–277 ms |

**Acceptance criterion (SC-007, rewritten 2026-10-16)**: measured with the #48
`JoinStormLoadTest` over at least 7 alternating runs per variant on an otherwise idle machine,
the feature branch's **median** storm completion time and **median** `#quiet` p50 latency are each
within 10% of main's. `#quiet` p99 and max only need to stay within the test's own bounds
(500 ms / 1 s). Each run measures only about 18 `#quiet` messages, so a run's p99 is effectively
its slowest sample and not a stable statistic. The step 2 p99/max figures above are motivation,
not a compliance measure.

**First-version evidence (Step 2b v1, before the joiner-echo and identity re-sample changes)**:
7 runs each, partly concurrent with other builds. Superseded by the final measurement below.

| Variant | Median storm | Median `#quiet` p50 | Median `#quiet` p99 |
|---|---|---|---|
| main | 2278 ms | 17.8 ms | 81.1 ms |
| Step 2b v1 | 2206 ms (−3%) | 17.7 ms | 100.7 ms |

**Final measurement (SC-007 met, 2026-10-17)**: 7 alternating runs per variant on the final
Step 2b code, with no concurrent builds (load average 2.5–4.7). "main" is the #48 test branch at
`64f1373`; the feature variant is the final Step 2b code with the same test. Per-run values are
in `baseline.md`.

| Variant | Median storm | Median `#quiet` p50 | Median `#quiet` p99 | Worst `#quiet` p99/max |
|---|---|---|---|---|
| main | 2293 ms | 19.6 ms | 134.3 ms | 181.0 ms |
| Step 2b final | 2335 ms (+1.8%) | 19.4 ms (−1.0%) | 100.1 ms | 157.2 ms |

Both medians are well within 10% of main, and every run's p99/max stays within the 500 ms / 1 s
bounds.

**Cause**: step 2's `join` built `memberListing()` and copied the recipient set, both
O(members), while holding the registry monitor. Every channel PRIVMSG's `lookup()` takes that
same monitor. Admission also ran `PresentedIdentity.presentedForm` (cloak extension code)
under both monitors whenever the channel had bans. On main this work ran in each joiner's own
thread with no lock held, so joiners did it in parallel.

**Why "snapshot under the channel monitor only" is not enough**: Java monitors nest, so the
registry can't be released while the channel stays held. Even with two phases (insert under
registry → channel, then re-take only the channel monitor for the snapshot), the next JOIN to
`#storm` takes the registry monitor and then *waits on the `#storm` monitor while still holding
the registry*. The registry is then blocked for the duration of the previous joiner's
O(members) snapshot, so `#quiet` lookups stall exactly as before. The synchronized
`memberListing()` has the same effect for every JOIN/NAMES reply on the storm channel. With
registry → channel ordering, any long hold of a channel monitor turns into a hold of the
registry. A hand-over-hand `ReentrantLock` for the registry fails the same way.

**Decision** (the narrowest change that removes the stall):
1. `join` runs CLOSING check, already-member check, admission, `addMember` and `addMembership`
   under registry → channel, as before (R-1…R-4 unchanged). It releases both monitors, then
   builds `JoinResult` from the live, weakly consistent views: a recipient copy of `members()`
   and `memberListing()`.
2. `Channel.memberListing()` no longer synchronizes. It iterates the concurrent sets without a
   monitor, as `sendNamesLine` did on main. It never visits the same session twice. It does
   **not** give a membership snapshot of one instant, and it does not choose each member's
   prefix atomically (see the ordering consequences and C-5).
3. `JoinCommandHandler` samples `presentedForm` before calling `join` and passes it to the ban
   gate, so no extension code runs under a monitor. After `join` returns it samples
   `presentedForm` again, outside every monitor, for the JOIN line (FR-022).
   - The identity can change between the two samples. A same-connection NICK can't cause this,
     because the dispatcher serializes the joiner's own commands. An administrator enabling or
     disabling the global hostname-display (cloak) extension can.
   - The JOIN line therefore always shows the identity current after the join.
   - The ban check uses the earlier sample. With a cloak enabled meanwhile, a ban matching only
     the new cloaked mask can be missed for that one JOIN. With a cloak disabled meanwhile, the
     check uses the old cloaked mask; the real mask is always checked too.
   - This is an accepted relaxation of `PresentedIdentity`'s live-check rule, limited to JOINs
     racing a cloak toggle (spec Clarifications 2026-10-16). Each successful JOIN costs one
     extra hostname-display call, outside all locks.
4. PART/KICK/`leaveAll` are unchanged. FR-020 still needs the membership check and removal to
   be atomic, and their pre-/post-removal recipient copies are O(members) under the registry
   monitor. This is the same class of cost for PART/QUIT storms, but #48 doesn't measure it.
   Recorded as a follow-up candidate, not changed here.

**Ordering consequences**: after both monitors are released, `join` runs three separate steps,
each with its own interleavings. `ChannelRegistry.join` first builds the listing with
`channel.memberListing()`, then copies the live `members()` view into a detached recipient set
and adds J to it; the `JoinResult` constructor only copies that already-detached set:

1. **L**: traverse the members for the NAMES listing.
2. **R**: traverse the members for the recipient copy.
3. **D**: deliver the JOIN, from the handler.

This is not the same order as main. Main delivered the JOIN to live members first and built
NAMES afterwards. Here L happens before R and D. J is the joiner and C the channel.

- *X joins C concurrently*: depending on when X's insertion lands relative to L and R, X can
  appear in J's NAMES, in J's recipients, both, or neither. X's own NAMES may already list J.
  Clients treat a JOIN for an already-listed member as idempotent.
- *Member Y leaves C concurrently (PART/KICK/QUIT)*:
  - Y's departure recipients are captured under the monitors after J's insertion, so J receives
    Y's departure.
  - If Y is removed after L, J's NAMES can still list Y.
  - If Y is removed after R visited Y, Y stays in J's recipients and can receive J's JOIN after
    Y has left. A weakly consistent traversal is not a "member at delivery time" filter.
- *J itself is KICKed, or its connection closes, concurrently*:
  - **J always receives its own JOIN.** `join` adds J to the recipient copy unconditionally,
    with no lock taken (spec Clarifications 2026-10-16). A KICKed J can therefore see the KICK
    before its own JOIN echo, since the two are enqueued by different threads. A closing J's
    writer may drop the write.
  - **Removed before L**: J is missing from NAMES, which is shown as read and never patched.
    NAMES may even be empty if the channel emptied.
  - **Removed between L and R**: J appears in its own NAMES.
  - **Peers, whenever J is removed**: each peer gets J's JOIN if and only if R captured that
    peer. Whether R also saw J doesn't matter, because J is added to the copy anyway.
    The operator who KICKed J before L is still a member, is captured by R and receives J's
    JOIN. So a peer can see J's JOIN after the KICK whether J was removed before L, between L
    and R, or after R.
  - The KICK itself goes to the members before removal, J included.
  - If the channel emptied, the result can reference a channel instance that has since been
    removed and replaced by a fresh instance of the same name. That doesn't corrupt the registry
    or re-add membership, but the result then describes a channel that no longer exists.
  - Step 2's capture inside the monitors always included J in its own recipients. Step 2b keeps
    that guarantee by adding J explicitly. `MembershipStressTest` still asserts it, and
    `ChannelRegistryTest.joinerKickedBeforeTheSnapshotIsStillARecipient` and
    `JoinSnapshotHandlerTest.joinerKickedDuringTheSnapshotStillReceivesItsOwnJoin` force the
    interleaving.
  - Capturing inside the transition does not order delivery across threads either. Enqueue
    order, not capture time, decides what a client sees, so JOIN-after-KICK for *peers* was also
    possible in step 2.
  - Rejected mitigation: re-check membership before delivery. It is check-then-act and only
    narrows the window.
- *A mode or privilege change concurrently*:
  - **New: torn prefixes.** `memberListing()` reads `operators.contains` and `voiced.contains` as
    two separate reads without the channel monitor. For example, Y starts voiced and not an
    operator. The listing reads operator = false; another thread grants operator and then
    revokes voice; the listing reads voice = false. The plain `Y` token matches no status Y held
    during that interval. Steps 1–3's synchronized listing excluded this. Main's unsynchronized
    listing allowed it too.
  - **Existing in every design: snapshot-to-enqueue inversion.** The listing can read `@Y`, then
    a `-o Y` echo is enqueued to J, then NAMES containing `@Y` is enqueued later, so J ends up
    with a stale prefix. Receiving the MODE echo does not by itself make J consistent.
  - Neither design is a guarantee about the order things appear on the wire.
- *Nicknames*: two members' concurrent NICK changes can render duplicate nick tokens even though
  session identities are unique. NICK takes no channel monitor, so this predates Step 2b.
- *Invariants*: R-1…R-3 are unaffected, because every mutation, the CLOSING check and
  admission still run under registry → channel, and `leaveAll`/`part`/`kick` are untouched.
  - R-4's protocol is unchanged. A join that observes CLOSING is refused. A join that passed the
    check can finish its mutation before cleanup gets the registry monitor, and cleanup then
    removes it. With the snapshot later, the join's *result* can complete after cleanup, but
    membership is never re-added.
  - FR-020 is unaffected. It does not order JOIN/NAMES/MODE delivery after the locks are
    released.
  - FR-001's "capture recipients in the same step" is relaxed to "read right after"; the spec was
    amended (FR-001, FR-008, FR-021).

**Test seam**: `ChannelRegistry` gets a package-private constructor that takes a hook called
after both monitors are released, at the start of the JOIN snapshot. Tests block in the hook and
show that operations on another channel, and a second JOIN on the same channel, complete in the
meantime. Placed where step 2 built the snapshot, under both monitors, those tests fail. This is
a simple, deterministic way to force that boundary, and it is test-only: the public constructor
installs a no-op. It is not a production extension point. A hook that throws after the mutation
leaves the membership in place.

**Alternatives** (engineering tradeoffs; none of them was measured):
- *Weak snapshots after release (chosen)*: the smallest change. It accepts the outcomes listed
  above (with the joiner always echoed and the JOIN line re-sampled, per the spec
  Clarifications of 2026-10-16). Acceptance is conditional on the SC-007 measurement.
- *Lock order channel → registry*: the registry is held only for O(1) map operations and
  snapshots stay atomic. But every JOIN's O(members) work is serialized under the channel
  monitor, which may keep the storm time near step 2's (unmeasured). R-4 needs a Dekker-style
  double check of CLOSING, and every transition has to change consistently.
- *Non-blocking channel acquisition*: try-lock the channel while holding the registry, then
  release the registry and retry after re-checking identity and lifecycle. This needs a
  consistent protocol across all transitions, may livelock under continuous mutation, and still
  doesn't order delivery.
- *Versioned or persistent (copy-on-write) member snapshots*: capture is atomic and O(1), but
  every add or remove becomes O(members) or needs a HAMT, which changes allocation costs.
- *Separate quiet lookups from mutation serialization* (e.g., a concurrent read map for
  `lookup`): this addresses the PRIVMSG stall directly, but changes R-1's enforcement and the
  creation-order guarantee of `all()`.

## R9. Concurrent KICK/PART outcome (FR-020)

**Decision**: Serial outcome. Only the operation that actually removed the member broadcasts,
and the other gets `ERR_USERNOTINCHANNEL`/`ERR_NOTONCHANNEL`. Confirmed by the user on
2026-10-16 (spec Clarifications).

**Alternatives**: Report "already gone" and broadcast anyway. Rejected because it keeps the
duplicate broadcast.
