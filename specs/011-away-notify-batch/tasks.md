---

description: "Task list template for feature implementation"
---

# Tasks: Away-Notify and Batch Capabilities

**Input**: Design documents from `/specs/011-away-notify-batch/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/away-notify-and-batch.md, quickstart.md

**Tests**: Included — this project's constitution (Testing Standards) requires every new
feature to ship with automated tests covering its primary behavior and documented edge cases.
`away-notify` gets end-to-end coverage in `jircd-integration-tests`, the established pattern
for capability-visible wire behavior (`AwayStatusTest.java`, etc.). `batch` gets a direct
`jircd-core` unit test on `SessionWriter`, mirroring the existing `LivenessMonitorTest.java`
pattern — research.md (Decision 4) explains why a black-box integration test isn't the right
tool for a mechanism this release ships with no production consumer.

**Organization**: Tasks are grouped by user story (spec.md P1/P2) to enable independent
implementation and testing of each story. The two capabilities share no code — each story
creates its own new Gradle module from scratch, so there is no cross-story Foundational phase.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2)
- Include exact file paths in descriptions

## Path Conventions

Existing multi-module Gradle project (plan.md "Project Structure") — two new leaf modules:

- `jircd-capabilities/away-notify/` — new capability module (US1)
- `jircd-capabilities/batch/` — new capability module (US2)
- `jircd-core/src/main/java/net/jircd/core/session/` — `SessionWriter`, `command/AwayCommandHandler`
- `jircd-integration-tests/src/test/java/net/jircd/integration/` — end-to-end tests (US1)
- `jircd-core/src/test/java/net/jircd/core/session/` — unit tests (US2)

---

## Phase 1: Setup

**Purpose**: Confirm a clean baseline before touching any source.

- [X] T001 Run `./gradlew build` from the repo root and confirm it succeeds with no source
      changes yet, establishing the pre-feature baseline.

---

## Phase 2: Foundational

**Purpose**: N/A for this feature — `away-notify` and `batch` share no code, no data, and no
common new abstraction (plan.md "Structure Decision"). Each user story below creates its own
Gradle module and touches its own files; neither blocks the other. Both stories may proceed in
either order, or in parallel by different contributors, immediately after Phase 1.

---

## Phase 3: User Story 1 - See when a channel-mate goes away or returns (Priority: P1) 🎯 MVP

**Goal**: A client that negotiates `away-notify` receives an `AWAY` notice whenever a user
sharing a channel with it changes its away status — set, changed, or cleared — with at most
one notice per recipient per change, and zero notices for anyone who hasn't opted in or shares
no channel with the changed user.

**Independent Test**: Negotiate `away-notify` on one connection, share a channel with a second
connection, have the second connection go away (with a reason) and then return; confirm the
first receives exactly one notice per transition. A third, non-opted-in connection in the same
channel receives nothing — per quickstart.md Scenarios 1-5.

### Tests for User Story 1

- [X] T002 [P] [US1] Write integration tests in
      `jircd-integration-tests/src/test/java/net/jircd/integration/AwayNotifyTest.java`
      covering: (a) an opted-in, channel-sharing recipient receives `:nick!user@host AWAY
      :<reason>` when the sender goes away, and `:nick!user@host AWAY` (no trailing param) when
      the sender returns (quickstart.md Scenario 1); (b) a recipient who never negotiated
      `away-notify` receives nothing (Scenario 2); (c) a recipient sharing two channels with the
      sender receives exactly one notice per change, not two (Scenario 3); (d) a recipient
      sharing zero channels with the sender receives nothing even if opted in (Scenario 4); (e)
      two successive reason changes while already away (never clearing in between) each produce
      their own notice (Scenario 5). These tests are expected to fail until T003-T005 are
      complete.

### Implementation for User Story 1

- [X] T003 [P] [US1] Create the `jircd-capabilities/away-notify` module:
      `build.gradle.kts` (mirror `jircd-capabilities/echo-message/build.gradle.kts` exactly —
      `implementation(project(":jircd-protocol"))` and `implementation(project(":jircd-core"))`),
      `src/main/java/net/jircd/capabilities/awaynotify/AwayNotifyExtension.java` (extends
      `AbstractCapabilityExtension`, `public static final String ID = "away-notify"`, no hook
      overrides — the fan-out logic lives in `AwayCommandHandler`, not here, per research.md
      Decision 1), and
      `src/main/resources/META-INF/services/net.jircd.core.extension.CapabilityExtension`
      (containing the one line `net.jircd.capabilities.awaynotify.AwayNotifyExtension`).
- [X] T004 [US1] Register the new module: add `"jircd-capabilities:away-notify"` to
      `settings.gradle.kts`'s `include(...)` block, and add
      `runtimeOnly(project(":jircd-capabilities:away-notify"))` to
      `jircd-server/build.gradle.kts` alongside the three existing capability `runtimeOnly`
      lines. (depends on T003)
- [X] T005 [US1] Modify
      `jircd-core/src/main/java/net/jircd/core/session/command/AwayCommandHandler.java`: after
      the existing set-reason/clear-reason logic (both branches), build a
      `Set<ClientSession>` by iterating `session.channelMemberships()` and each channel's
      `members()` (deduplicating by session identity, unlike `NickCommandHandler`'s undeduplicated
      double-loop — research.md Decision 1, FR-004), excluding the sender itself, filter to
      sessions whose `negotiatedCapabilities()` contains `CapabilityName.AWAY_NOTIFY`
      (`net.jircd.protocol.CapabilityName`, the shared constant both `jircd-core` and every
      `jircd-capabilities/*` module reference — no more per-file hardcoded string literals), and
      send each a `Command.AWAY` raw `Message` via `writer().enqueueRaw(...)` — prefix
      `PresentedIdentity.presentedForm(session, extensionRegistry)` (cloak-aware; `AwayCommandHandler`
      now takes `ExtensionRegistry` as a constructor dependency — a raw
      `Hostmask.format(..., session.realHostname())` was caught in review as leaking the real
      hostname past an enabled `cloak` extension, since `AWAY` requires no privilege at all),
      params `List.of(reason)` when going away/changing reason or `List.of()` when clearing
      (contracts/away-notify-and-batch.md "away-notify capability"; data-model.md "Away-Status
      Change Notice").

**Checkpoint**: User Story 1 is fully functional and independently testable — run T002's tests
against T003-T005; all should pass. Deliverable on its own without User Story 2 existing.

---

## Phase 4: User Story 2 - Client can tell which server messages belong together (Priority: P2)

**Goal**: A general-purpose message-grouping mechanism (`BATCH +ref`/`BATCH -ref` framing with
a `batch=ref` tag on each member) is available for any future caller to use, correctly scoped
to recipients who negotiated both `batch` and `message-tags`, with zero behavior change for
anyone who hasn't. This release ships no caller of it — the mechanism itself is what's
delivered and tested.

**Independent Test**: Construct a `SessionWriter` directly (mirroring
`LivenessMonitorTest.java`'s pattern) against a session with `batch`/`message-tags` negotiated,
issue two concurrent `enqueueBatch` calls, and confirm each group's start/member/end lines are
unambiguously distinguishable by reference tag; confirm a session lacking either capability
gets the member messages completely unwrapped — per quickstart.md Scenarios 6-7. No dependency
on User Story 1.

### Tests for User Story 2

- [X] T006 [P] [US2] Write a unit test — e.g.
      `jircd-core/src/test/java/net/jircd/core/session/SessionWriterBatchTest.java` — following
      `LivenessMonitorTest.java`'s pattern of constructing `SessionWriter` directly against a
      captured `OutputStream` and a `ClientSession` with capabilities added directly to
      `negotiatedCapabilities()`. Cover: (a) two concurrent `enqueueBatch` calls to a
      `batch`+`message-tags`-negotiated session produce two distinct reference tags, each
      group's `BATCH +ref`/member/`BATCH -ref` lines correctly matched and never
      cross-attributed (quickstart.md Scenario 6, FR-008/FR-009); (b) a session with neither
      capability negotiated (and, separately, one with `batch` alone but not `message-tags`)
      receives the same member messages completely unwrapped — byte-for-byte what individual
      `enqueueRaw` calls would have produced (Scenario 7, FR-010, research.md Decision 2).
      These tests are expected to fail until T007-T009 are complete.

### Implementation for User Story 2

- [X] T007 [P] [US2] Create the `jircd-capabilities/batch` module:
      `build.gradle.kts` (mirror `jircd-capabilities/echo-message/build.gradle.kts`),
      `src/main/java/net/jircd/capabilities/batch/BatchExtension.java` (extends
      `AbstractCapabilityExtension`, `public static final String ID = "batch"`, no hook
      overrides — `SessionWriter.enqueueBatch` does its own capability check directly, per
      research.md Decision 2), and
      `src/main/resources/META-INF/services/net.jircd.core.extension.CapabilityExtension`
      (containing the one line `net.jircd.capabilities.batch.BatchExtension`).
- [X] T008 [US2] Register the new module: add `"jircd-capabilities:batch"` to
      `settings.gradle.kts`'s `include(...)` block, and add
      `runtimeOnly(project(":jircd-capabilities:batch"))` to `jircd-server/build.gradle.kts`.
      (depends on T007)
- [X] T009 [US2] Add `enqueueBatch(String type, List<String> typeParams, List<Message> members)`
      to `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java`: check
      `session.negotiatedCapabilities().contains("batch") &&
      session.negotiatedCapabilities().contains("message-tags")` (both string literals hardcoded
      locally, same precedent as T005); if both present, generate
      `String ref = UUID.randomUUID().toString()` (research.md Decision 3), `enqueueRaw` a
      `BATCH` message with params `["+"+ref, type, ...typeParams]`, then `enqueueRaw` each
      member message with `batch=ref` merged into its existing tags map, then `enqueueRaw` a
      `BATCH` message with params `["-"+ref]`; if either capability is absent, `enqueueRaw` each
      member message completely unmodified (data-model.md "Message Group (Batch)"; contracts/
      away-notify-and-batch.md "batch capability").

**Checkpoint**: User Story 2 is fully functional and independently testable — run T006's test
against T007-T009; all should pass. Deliverable on its own without User Story 1 existing.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Final checks spanning both stories.

- [X] T010 [P] Re-read `specs/011-away-notify-batch/contracts/away-notify-and-batch.md` against
      the finished `AwayCommandHandler`/`SessionWriter`/extension changes and correct any
      wording that has drifted from the actual implementation (no source-code change expected —
      this is a documentation-accuracy pass).
- [X] T011 Walk through every scenario in `specs/011-away-notify-batch/quickstart.md`
      end-to-end (manually or by confirming the corresponding T002/T006 test methods cover it)
      and check off any scenario not already exercised by an automated test.
- [X] T012 [P] Update `specs/001-ircv3-server/contracts/server-configuration.md`'s example
      `extensions:` block to include `away-notify: enabled` / `batch: enabled` as documented
      examples, alongside the three pre-existing capabilities (documentation-accuracy pass,
      same convention `010-wallops-notices` used for its own doc updates — no schema change,
      since any `CapabilityExtension` id is already generically accepted there).
- [X] T013 [P] Run `./gradlew check` to execute the full test suite plus
      Spotless/SpotBugs/PMD across `jircd-core`, the two new capability modules, and
      `jircd-integration-tests`, satisfying the Constitution's Quality Gates before this
      feature is considered mergeable.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Empty — nothing blocks either story beyond Setup.
- **User Story 1 (Phase 3)**: Depends on Setup only.
- **User Story 2 (Phase 4)**: Depends on Setup only — fully independent of User Story 1 (no
  shared file, no shared new abstraction).
- **Polish (Phase 5)**: Depends on both user stories being complete.

### Within Each Story

- T002 (US1 tests) before T003-T005 (US1 implementation) — tests first, per this project's
  Testing Standards; T004 depends on T003 (registration needs the module to exist); T005 has no
  hard file dependency on T003/T004 to compile, but the feature isn't observable end-to-end
  until all three land.
- T006 (US2 tests) before T007-T009 (US2 implementation); T008 depends on T007; T009 has no
  hard file dependency on T007/T008 to compile (it's a `jircd-core` change, not part of the new
  module), but the capability isn't negotiable end-to-end until all three land.

### Parallel Opportunities

- T002 and T006 can be written in parallel — different files, different stories.
- T003 and T007 can be done in parallel — different new modules, no shared file.
- User Story 1 (T002-T005) and User Story 2 (T006-T009) can proceed fully in parallel by two
  different contributors once T001 (Setup) is done — they touch zero common files.
- T010, T012, and T013 in Polish are independent of each other and can run in parallel.

---

## Parallel Example: After Setup (T001)

```bash
# Two fully independent tracks can start immediately once T001 is done:
Track A (User Story 1): T002 -> T003 -> T004 -> T005
Track B (User Story 2): T006 -> T007 -> T008 -> T009
# Neither track depends on the other at any point.
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001).
2. Complete Phase 3: User Story 1 (T002-T005) — Phase 2 is empty, skip straight through.
3. **STOP and VALIDATE**: run quickstart.md Scenarios 1-5 against a running server.
4. This alone delivers real, user-visible value: opted-in clients see away-status changes in
   real time. User Story 2 (batch) ships pure infrastructure with no consumer yet in this
   release, so it adds zero user-visible value on its own — reasonable to defer entirely if
   only one story is wanted right now.

### Incremental Delivery

1. Setup → foundation ready (T001).
2. Add User Story 1 → validate independently → this is the MVP (T002-T005).
3. Add User Story 2 → validate independently (T006-T009) — delivers the batch mechanism ready
   for a future capability (e.g. the separately-tracked chathistory roadmap item) to use.
4. Polish (T010-T013) once both are in place.
