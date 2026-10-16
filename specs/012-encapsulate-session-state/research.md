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
before its own numeric replies. JOIN's NAMES listing is captured inside the transition
(`JoinResult.memberListing`) so it matches the recipient snapshot.

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

## R9. Concurrent KICK/PART outcome (FR-020)

**Decision**: Serial outcome. Only the operation that actually removed the member broadcasts,
and the other gets `ERR_USERNOTINCHANNEL`/`ERR_NOTONCHANNEL`. Confirmed by the user on
2026-10-16 (spec Clarifications).

**Alternatives**: Report "already gone" and broadcast anyway. Rejected because it keeps the
duplicate broadcast.
