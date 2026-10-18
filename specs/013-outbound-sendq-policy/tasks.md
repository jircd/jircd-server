---

description: "Task list for the outbound queue overflow policy (013, issue #50)"
---

# Tasks: Outbound Queue Overflow Policy

**Input**: Design documents from `/specs/013-outbound-sendq-policy/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Required. The constitution (Testing Standards) and AGENTS.md require tests first; each story lists its tests before its implementation.

**Organization**: grouped by user story. The open decisions are resolved in spec.md Clarifications (storm 2,000; server-wide limit; no fan-out change; backlog discard on overflow; ceiling 2 MiB).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1, US2, US3 (spec.md user stories)

## Path Conventions

Gradle multi-module. Core code: `jircd-core/src/main/java/net/jircd/core/...`; unit tests: `jircd-core/src/test/java/net/jircd/core/...`; end-to-end: `jircd-integration-tests/src/test/java/net/jircd/integration/`.

## Phase 1: Setup

**Purpose**: bring in the load test and measure the baseline before changing anything

- [ ] T001 Cherry-pick commit `a847df5` (`JoinStormLoadTest`, from `feature/issue-48-join-storm-load-test`) onto this branch so `jircd-integration-tests/src/test/java/net/jircd/integration/JoinStormLoadTest.java` exists; #48's PR is open as #59; if it has merged by then, rebase instead and skip the cherry-pick
- [ ] T002 Record the pre-change baseline: run `./gradlew :jircd-integration-tests:loadTest --tests '*JoinStormLoadTest*'` at least 7 times (waves of 100) and write medians (storm time, `#quiet` p99/max) plus the observed overflow failures at one simultaneous wave of 250 and 1,000 into `specs/013-outbound-sendq-policy/baseline.md`
- [ ] T003 [P] Add a package-private/test-only way to read a `SessionWriter`'s peak queued bytes (or log it at DEBUG) and use it in a scratch run of the 2,000-client single-wave storm to record bytes queued per member; write the figure into `specs/013-outbound-sendq-policy/baseline.md` and confirm or adjust the 512 KiB default in `research.md` D3 (default MUST be at least 3x the measured peak and at most the 2 MiB ceiling; if 3x the peak exceeds 2 MiB, stop and report to the user)

---

## Phase 2: Foundational (blocking)

**Purpose**: the configuration setting every story depends on

**CRITICAL**: no story work starts before this phase is done

- [x] T004 Write failing tests in `jircd-core/src/test/java/net/jircd/core/config/ConfigurationLoaderTest.java` for `sendqLimitBytes`: absent yields 524288; 16384 and 2097152 accepted; 16383, 2097153, 0, -1, `"big"`, `1.5` rejected with a `ConfigurationException` message naming `sendqLimitBytes` (data-model.md: "integer, 16384 <= value <= 2097152")
- [x] T005 Add the `int sendqLimitBytes` component to `ServerConfiguration` in `jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java` with constants `DEFAULT_SENDQ_LIMIT_BYTES = 524288`, `MIN_SENDQ_LIMIT_BYTES = 16384`, `SENDQ_LIMIT_CEILING_BYTES = 2097152`, with a test or static check that `DEFAULT_SENDQ_LIMIT_BYTES <= SENDQ_LIMIT_CEILING_BYTES`; update every direct constructor call (main and test sources, find with `rg 'new ServerConfiguration\('`)
- [x] T006 Parse and validate `sendqLimitBytes` in `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java` (extend the `positiveIntWithinCeiling` pattern with a floor) so T004 passes; keep the error text style of the other settings
- [x] T007 [P] Add a reload test to the existing reload test class (`rg -l ConfigurationReloader jircd-core/src/test jircd-integration-tests/src/test`): an invalid `sendqLimitBytes` on reload throws and `current()` keeps the previous value
- [x] T008 Wire the setting through `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java` and `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java` as a supplier `() -> reloader.current().sendqLimitBytes()` (same pattern as `keepAliveFrequencySeconds`), read once when a connection opens

**Checkpoint**: configuration parses, validates, reloads; nothing uses it yet beyond being passed to the handler

---

## Phase 3: User Story 1 - JOIN storm no longer disconnects healthy members (Priority: P1) MVP

**Goal**: queue limit is in bytes, default sized so a 2,000-client simultaneous storm completes with 0 overflow disconnects.

**Independent Test**: `JoinStormLoadTest` with one simultaneous wave of 2,000 passes; `SessionWriter` unit tests show the byte accounting.

### Tests for User Story 1 (write first, confirm they fail)

- [ ] T009 [P] [US1] Add `jircd-core/src/test/java/net/jircd/core/session/SessionWriterByteLimitTest.java`: with limit 16384 and a blocked output stream, enqueueing raw messages totalling under the limit never triggers overflow, however many entries; the first enqueue that would push estimated queued bytes past the limit triggers overflow exactly once; bytes are released as the writer drains; a fan-out entry is counted with the 128-byte tag allowance; a maximum-size legal line fits an empty queue at the 16384 floor
- [ ] T010 [P] [US1] Update `jircd-core/src/test/java/net/jircd/core/session/SessionWriterCloseTest.java` (it fills "256 lines") and any other test that assumes the 256-entry cap (`rg -l 'QUEUE_CAPACITY|256' jircd-core/src/test`) to size its fill from a byte limit instead
- [ ] T011 [US1] Change `jircd-integration-tests/src/test/java/net/jircd/integration/JoinStormLoadTest.java` to JOIN all 2,000 clients in one simultaneous wave (remove `WAVE_SIZE` waves, keep `CONNECT_CONCURRENCY`), assert 0 unexpected disconnects, and update the class comment's measured numbers; confirm it fails on the current code

### Implementation for User Story 1

- [ ] T012 [US1] In `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java` replace `ArrayBlockingQueue(256)`/`QUEUE_CAPACITY` with an unbounded `LinkedBlockingQueue` plus an atomic `queuedBytes` against a `limitBytes` constructor argument (keep the 3-argument constructor using `DEFAULT_SENDQ_LIMIT_BYTES`); entries carry their estimated size and release it when the writer takes them
- [ ] T013 [US1] Add the size estimate in `SessionWriter.java`: UTF-8 byte lengths of prefix, command, params and tag keys/values plus separators and CRLF, without serializing; fan-out entries add the fixed 128-byte allowance (research.md D2); enqueue reserves bytes atomically and, when `queuedBytes + size > limitBytes`, undoes the reservation and calls the existing overflow path
- [ ] T014 [US1] Pass `sendqLimitBytes` from the T008 supplier into `new SessionWriter(...)` in `ConnectionHandler.java`
- [ ] T015 [US1] Run T009-T011 to green, then 7 alternating before/after runs of `JoinStormLoadTest` (and `Story1DeliveryLatencyLoadTest`, `ConcurrentConnectionScaleLoadTest`) comparing medians against `baseline.md`; record results in `baseline.md` (constitution IV: regressions over 10% need written justification)

**Checkpoint**: the storm passes at default settings; overflow still closes with the old reason and no ERROR line

---

## Phase 4: User Story 2 - A client that stops reading is disconnected and told why (Priority: P1)

**Goal**: overflow is exactly-once, never blocks the sender, sends a best-effort `ERROR`, and broadcasts reason `SendQ exceeded`.

**Independent Test**: `SendqOverflowTest` with a non-reading client.

### Tests for User Story 2 (write first)

- [ ] T016 [P] [US2] Extend `SessionWriterByteLimitTest.java` (or add `SessionWriterOverflowTest.java`): on overflow the backlog is discarded, the line `ERROR :Closing Link: <host> (SendQ exceeded)` and the poison pill are written ahead of close when the stream accepts writes, further enqueues are ignored, the disconnect callback runs once under 8 racing enqueuers, and the enqueuing thread returns without waiting on a blocked output stream (assert with a timeout well under the 2 s close bound)
- [ ] T017 [P] [US2] Add `jircd-integration-tests/src/test/java/net/jircd/integration/SendqOverflowTest.java`: start `TestServer` with `sendqLimitBytes: 16384`; a client joins a channel then never reads; a second client floods the channel; assert the stuck connection is closed within 5 s, a third member sees `QUIT :SendQ exceeded`, a reading client that stays under the limit is unaffected, and a reader that was slow but alive receives the `ERROR` line
- [ ] T018 [P] [US2] Add a case to `SendqOverflowTest.java` where overflow races a client `QUIT`: exactly one QUIT broadcast and one `disconnected` log entry (use `LogCapture`)

### Implementation for User Story 2

- [ ] T019 [US2] In `SessionWriter.java` add the overflow state machine (data-model.md): a compare-and-set `overflowed` flag; on first overflow discard the queued backlog, enqueue the `ERROR` line and the poison pill in a reserved allowance outside the limit, and ignore later enqueues; the `ERROR` host comes from the session's remote address (`contracts/client-visible.md`)
- [ ] T020 [US2] Replace the single `onOverflow` callback with two: `onSendqOverflow` and `onWriteFailure` (the latter keeps today's reason and behavior); in `ConnectionHandler.java` run the sendq disconnect on a new virtual thread via `disconnectCleanup.cleanup(session, "SendQ exceeded")` so the sender never waits on `SessionWriter.close()` (research.md D5)
- [ ] T021 [US2] Run T016-T018 to green; re-run the existing `KillCommandTest` and `QuitNeighborsTest` to confirm the close/drain guarantee (a line queued just before close is still written) is unchanged

**Checkpoint**: US1 and US2 both hold; overflow is visible to the client and members

---

## Phase 5: User Story 3 - Operators can size and observe the limit (Priority: P2)

**Goal**: distinct, countable log entry; documented policy.

**Independent Test**: overflow one connection and read the log; read the README section.

### Tests for User Story 3 (write first)

- [ ] T022 [P] [US3] Extend `jircd-core/src/test/java/net/jircd/core/session/ConnectionMonitorLogTest.java` (check the existing location with `rg -l ConnectionMonitorLog jircd-core/src/test jircd-integration-tests/src/test`) for `sendqOverflow(connection, limitBytes, queuedBytes)`: line format matches `contracts/monitoring-log.md` and `overflowTotal` increases by one per call
- [ ] T023 [US3] Add a case to `SendqOverflowTest.java` using `LogCapture`: one overflow produces exactly one `connection-event=sendq-overflow` entry with the configured `limitBytes` and one `disconnected ... reason=SendQ exceeded` entry; two overflows give `overflowTotal` differing by 1 (compare relative values, the counter is process-wide)

### Implementation for User Story 3

- [ ] T024 [US3] Add `sendqOverflow(String connectionId, int limitBytes, long queuedBytes)` and a process-wide `AtomicLong` total to `jircd-core/src/main/java/net/jircd/core/session/ConnectionMonitorLog.java`; call it from the sendq overflow path in `ConnectionHandler.java` before `cleanup`
- [ ] T025 [P] [US3] Document the policy in `README.md` (FR-012): default, unit, key, minimum, ceiling, the exact `ERROR` text, QUIT/log reason, reload applies to new connections only, worst-case memory (`connections x limit`: 10,000 x 512 KiB = 5 GiB at the default, 10,000 x 2 MiB = 20 GiB at the ceiling), and guidance for raising it; add `sendqLimitBytes` to the example configuration if the README has one (the gitignored local `jircd-server/jircd.yaml` MUST NOT be touched)
- [ ] T026 [US3] Run T022-T023 to green

**Checkpoint**: all three stories complete

---

## Phase 6: Polish and cross-cutting

- [ ] T027 [P] Check that no other code path assumes the removed 256-entry capacity or the old shared overflow callback: `rg 'setOnOverflow|QUEUE_CAPACITY|Excess Flood' --glob '*.java'`
- [ ] T028 `./gradlew spotlessApply` then `./gradlew build` (tests, Spotless, SpotBugs, PMD, coverage 85% line / 75% branch); fix findings without suppressions
- [ ] T029 Run `./gradlew loadTest` once end to end and the quickstart.md steps; paste the before/after medians into the PR description
- [ ] T030 Mark completed tasks here and in `baseline.md` with the results; leave the docs commit separate from code per the repo's convention (do not commit without the user's review)

---

## Dependencies and execution order

- Phase 1 first (T001 gates T011; T002/T003 inform the default and the budgets).
- Phase 2 gates everything: T004 -> T005 -> T006 -> T008; T007 parallel after T006.
- US1 (T009-T015) is the MVP and gates US2 because both change `SessionWriter.java`; US2 (T016-T021) follows. US3's test and log work (T022-T024) touches `ConnectionMonitorLog.java` and could start after Phase 2, but its integration test T023 needs US2's overflow path.
- T025 (README) is independent of code after the default is fixed by T003.
- Polish last.

## Parallel examples

- After T006: T007 with T009, T010 (different files).
- US2 tests T016, T017, T018 (different files / independent cases).
- T022 and T025 alongside US2 implementation.

## Implementation strategy

MVP is Phase 1 + 2 + US1: the byte limit and a default that passes the storm. Then US2 for the ERROR and reason, then US3 for the log and docs. Stop at each checkpoint for the user's review; do not commit without it.
