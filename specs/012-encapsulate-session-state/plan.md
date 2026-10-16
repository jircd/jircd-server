# Implementation Plan: Encapsulate Channel and Session State Transitions

**Branch**: `012-encapsulate-session-state` | **Date**: 2026-10-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/012-encapsulate-session-state/spec.md`

## Summary

Move channel/session state rules out of command handlers and into the objects that own the
state, without changing anything a client can see. Three owners, one lock order:

1. **`ChannelRegistry`** (the channel directory) becomes the only entry point for
   *membership transitions*: `join`, `part`, `kick` and `leaveAll` (disconnect). Each runs under
   the registry monitor, then the channel monitor, does both sides of the membership change,
   drops the channel if it emptied, and returns an immutable result with the recipients to
   notify. Delivery happens after the locks are released.
2. **`Channel`** owns intra-channel consistency. It gains named mutators for privileges, modes,
   invitations and the member listing. `addMember`/`removeMember` become package-private.
   Every collection accessor returns an unmodifiable live view.
3. **`ClientSession`** exposes `channelMemberships()` and `userModes()` as unmodifiable views.
   Membership is changed only by package-private methods that the registry calls while it holds
   the channel monitor. User modes change through `setUserMode`/`clearUserMode`.

Two shared helpers remove the duplicated fan-out code: `Recipients` (shared-channel neighbours,
de-duplicated, optionally filtered; plus "deliver to a set, skipping sessions without a writer")
and `Replies.sendListed` (byte-aware splitting of a space-separated list across numeric reply
lines, extracted from `JoinCommandHandler.sendNamesLine`).

Delivered in four mergeable steps (helpers, transitions, mutators, docs/cleanup), each green on
`./gradlew build`. Decisions and rejected alternatives are in [research.md](research.md); the
operation signatures are in [contracts/state-operations.md](contracts/state-operations.md).

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain), multi-module build

**Primary Dependencies**: none new. JDK concurrency primitives only (`synchronized` monitors,
`ConcurrentHashMap` key sets, `CopyOnWriteArrayList`, `Collections.unmodifiable*`)

**Storage**: N/A (in-memory server state)

**Testing**: JUnit 5 + AssertJ unit tests in `jircd-core` (virtual-thread and `CountDownLatch`
choreography for races, as in `ChannelRegistryTest`). Socket-level tests in
`jircd-integration-tests`. Load tests tagged `load` (`./gradlew loadTest`)

**Target Platform**: JVM server (macOS/Linux)

**Project Type**: network server (IRCv3), multi-module library + app

**Performance Goals**: all load tests pass their asserted bounds (including <1s per message
delivery); Gradle durations are recorded for information only. The existing load tests are
`Story1DeliveryLatencyLoadTest`, `ConcurrentConnectionScaleLoadTest`, `KeepAliveLoadTest`,
`RateLimitLoadTest`. Message fan-out (PRIVMSG/NOTICE/TAGMSG) must not gain a per-message copy
of the member set (FR-010)

**Constraints**: client-visible behavior identical for any sequential command history (spec
US2). Existing integration tests unchanged; test-support additions are allowed. No new dependencies. Spotless/SpotBugs/PMD clean

**Scale/Scope**: about 20 production files across `jircd-core` and
`jircd-server-extensions/admin`. The capability modules do not touch these accessors (verified
by search)

### Call-site inventory (grounding, at `6088e51`)

Mutations through live accessors that this feature moves behind named operations:

| State | Mutating call sites today |
|---|---|
| `Channel.members/operators/voiced` (membership) | `ChannelRegistry.join` → `addMember`. `removeMember` from `PartCommandHandler`, `KickCommandHandler`, `DisconnectCleanup` |
| `Channel.operators/voiced` (privilege) | `ModeCommandHandler.applyMember`, `SamodeCommandHandler` (+o/-o on self) |
| `Channel.activeModes` | `ModeCommandHandler.applyBoolean` (with the +s/+p exclusion) |
| `Channel.invited` | `InviteCommandHandler` (add), `JoinCommandHandler.admissionFailure` (consume) |
| `Channel.bans` | already behind `addBan`/`removeBan`; accessor still returns the live list |
| `ClientSession.channelMemberships` | `ChannelRegistry.join` (add). `PartCommandHandler`, `KickCommandHandler`, `DisconnectCleanup` (remove) |
| `ClientSession.userModes` | `UserModeCommandHandler` (add/remove). `grant/revokeAdministratorPrivilege` already encapsulated |

Read-only users that keep working against views: `ChannelVisibility`, `CoreChannelModes`,
`MessageCommandHandler`/`TagmsgCommandHandler.resolveRecipients`, `WhoCommandHandler`,
`ListCommandHandler`, `TopicCommandHandler`, `InviteCommandHandler`, `AwayCommandHandler`,
`LusersCommandHandler`, `UserhostCommandHandler`, `WhoisCommandHandler`,
`WallopsCommandHandler`.

Hand-written recipient dedup today: `NickCommandHandler.broadcastNickChange`,
`AwayCommandHandler.broadcastAwayChange`, `DisconnectCleanup.cleanup`. Hand-written "for each
member, if writer != null, enqueueRaw" loops: JOIN, SAJOIN, PART, KICK, MODE, SAMODE, TOPIC,
NICK, QUIT. Byte-aware list splitting: only `JoinCommandHandler.sendNamesLine` (used by JOIN,
SAJOIN, NAMES and bare NAMES).

Test code that drives the internal mutation API and must migrate (FR-018):
`ChannelRegistryTest` (direct `addMember`/`removeMember`/`channelMemberships().add/remove`),
`ChannelTest` (`addMember`), `OutboundMessageBudgetTest` (`addMember`, `voiced().add`).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle / Gate | Status | How this plan satisfies it |
|---|---|---|
| I. Code Quality: single responsibility | PASS | Each owner gets one job: the registry handles channel existence and membership transitions, the channel handles its own consistency, the session handles its own modes and view. Handlers keep parsing, permission replies and delivery |
| I. Static analysis clean | PASS | `./gradlew build` (Spotless, SpotBugs, PMD) is the exit gate of every step. Existing `@SuppressWarnings("PMD.CloseResource")` on borrowed writers moves into `Recipients.deliver` with its reason |
| I. Dead code removed | PASS | The hand-written fan-out loops, the inline splitting loop in `sendNamesLine` and the live mutable accessors are deleted, not deprecated (in-repo callers only, per spec Assumptions) |
| I. Public interfaces documented at definition | PASS | FR-017. Invariants and lock order live in class Javadoc of `ChannelRegistry`, `Channel`, `ClientSession` |
| II. Tests first, regression tests for bugs | PASS | Each step starts with failing tests for its new invariant (FR-019). The FR-020 KICK/PART race and the disconnect-vs-JOIN leak get regression tests that fail on main |
| II. Deterministic tests | PASS | Races are forced with latches inside admission/test hooks (existing `ChannelRegistryTest` pattern), never sleeps. The SC-002 stress test asserts invariants, not timings |
| II. Coverage must not decrease | PASS | New operations are covered directly. Deleted code is covered by tests that move to the replacements |
| III. UX consistency | PASS | No user-facing change for sequential use. Integration suite unchanged (Quality Gate 4 = existing irctest/integration suite plus quickstart walkthrough) |
| IV. Performance budgets | PASS | No new user-facing operation, so no new budget. The hot path touches registry/channel state. Gate 5: all load tests pass their asserted bounds; Gradle durations are recorded for information only (SC-005, research R7) |
| Workflow: complexity justified | PASS | Two small helpers and three result records. Each replaces existing duplication or a current race. No new module or dependency |

No violations, so Complexity Tracking is empty.

**Post-design re-check**: PASS. The design in data-model.md and contracts keeps every item
above. One behavior change, limited to concurrent races, was confirmed by the user on 2026-10-16
(spec Clarifications, FR-020) and is not a constitution conflict.

## Project Structure

### Documentation (this feature)

```text
specs/012-encapsulate-session-state/
├── plan.md                      # This file
├── research.md                  # Phase 0: decisions R1–R8
├── data-model.md                # Phase 1: owners, invariants, transitions, lock order
├── quickstart.md                # Phase 1: validation guide
├── contracts/
│   └── state-operations.md      # Phase 1: internal operation contracts
├── checklists/requirements.md
└── tasks.md                     # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
jircd-core/src/main/java/net/jircd/core/session/
├── ChannelRegistry.java        # join/part/kick/leaveAll transitions, lock-order Javadoc
├── Channel.java                # views, privilege/mode/invite mutators, memberListing()
├── ClientSession.java          # membership/user-mode views + package-private mutators
├── Recipients.java             # NEW: shared-channel neighbours + deliver()
├── JoinResult.java             # NEW: record (channel, recipients, grantedOperator)
├── Departure.java              # NEW: record (channel, recipients, departed session)
├── DisconnectCleanup.java      # uses ChannelRegistry.leaveAll + Recipients.deliver
└── command/
    ├── Replies.java            # + sendListed(...) byte-aware list replies
    ├── JoinCommandHandler.java # uses JoinResult; sendNamesReply/sendNamesLine delegate to
    │                           #   Channel.memberListing() + Replies.sendListed
    ├── NamesCommandHandler.java
    ├── PartCommandHandler.java # ChannelRegistry.part
    ├── KickCommandHandler.java # ChannelRegistry.kick
    ├── NickCommandHandler.java # Recipients.sharedChannelNeighbors(includeSelf)
    ├── AwayCommandHandler.java # Recipients (filter: away-notify)
    ├── ModeCommandHandler.java # Channel.grant/revoke*, setMode/clearMode
    ├── TopicCommandHandler.java, InviteCommandHandler.java, UserModeCommandHandler.java

jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/
├── SajoinCommandHandler.java   # ChannelRegistry.join(..., ADMIT_ALL) + JoinResult
└── SamodeCommandHandler.java   # Channel.grantOperator/revokeOperator

jircd-core/src/test/java/net/jircd/core/session/
├── ChannelRegistryTest.java    # migrated choreography + new transition/race tests
├── ChannelTest.java            # mutator invariants
├── RecipientsTest.java         # NEW
├── MembershipStressTest.java   # NEW (SC-002, deterministic invariant checks)
└── command/RepliesListedTest.java  # NEW (or alongside OutboundMessageBudgetTest)
```

**Structure Decision**: Everything stays in the existing modules and packages. The new types
sit in `net.jircd.core.session` so the mutators that must not escape can be package-private.
The compiler then enforces FR-009 for handlers in `.command` and for the admin extension.

## Delivery Steps (FR-018)

Each step is one PR-sized change. It is mergeable on its own and must pass `./gradlew build`.

1. **Shared fan-out and listed replies** (US3): add `Recipients` and `Replies.sendListed`, and
   move NICK/AWAY dedup, QUIT delivery, every channel fan-out loop and the NAMES splitting onto them. No
   state ownership changes yet.
2. **Membership transitions** (US1): `ChannelRegistry.join/part/kick/leaveAll` with result
   records. Also the closing-session admission guard, package-private
   `addMember`/`removeMember`/membership mutators, and an unmodifiable `members()` and
   `channelMemberships()`. Migrate PART/KICK/JOIN/SAJOIN/DisconnectCleanup and
   `ChannelRegistryTest`.
3. **Named mutators** (US4, FR-006): privileges, modes, invitations, ban view and user modes.
   Migrate MODE/SAMODE/INVITE/UMODE and `OutboundMessageBudgetTest` setup.
4. **Invariants and cleanup** (US4, FR-017): lock-order and invariant Javadoc, plus FR-number
   comment cleanup in touched files. Also the SC-002 stress test and the load-test comparison.

## Resolved Questions

- **FR-020 (confirmed 2026-10-16)**: when two operations race on the same membership, only the
  one that removed the member broadcasts, and the loser gets
  `ERR_USERNOTINCHANNEL`/`ERR_NOTONCHANNEL`. See spec Clarifications.

## Complexity Tracking

No constitution violations to justify.
