---

description: "Task list for the outbound queue overflow policy (013, issue #50)"
---

# Tasks: Outbound Queue Overflow Policy

**Input**: Design documents from `/specs/013-outbound-sendq-policy/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Required. The constitution (Testing Standards) and AGENTS.md require tests first; each story lists its tests before its implementation.

**Organization**: grouped by user story. The open decisions are resolved in spec.md Clarifications (storm 2,000; server-wide limit; no fan-out change; backlog discard on overflow; ceiling 2 MiB; pure per-message capability accounting; separate survival and latency targets).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1, US2, US3 (spec.md user stories)

## Path Conventions

Gradle multi-module. Core code: `jircd-core/src/main/java/net/jircd/core/...`; unit tests: `jircd-core/src/test/java/net/jircd/core/...`; end-to-end: `jircd-integration-tests/src/test/java/net/jircd/integration/`.

## Phase 1: Setup

**Purpose**: bring in the load test and measure the baseline before changing anything

- [x] T001 Bring in #48's hardened `JoinStormLoadTest` and `JoinStormSupportTest`: satisfied by the coordinator rebase onto main containing merged PR #59 (`f9dfac1`); no cherry-pick is needed.
- [x] T002 Record the pre-change baseline: run `./gradlew --no-daemon :jircd-integration-tests:loadTest --tests 'net.jircd.integration.JoinStormLoadTest' --rerun` at least 7 times (waves of 100) and write medians (storm time, `#quiet` p99/max) plus the observed overflow failures at one simultaneous wave of 250 and 1,000 into `specs/013-outbound-sendq-policy/baseline.md`
- [x] T003 Record the accepted scratch peak in `baseline.md` and research D3: 2,000 clients in one wave, peak 31,286 B, 3x = 93,858 B; retain default 524288 B below the 2097152 B ceiling. The temporary package-private probe and enlarged entry cap are restored afterward. Its old allowance is historical methodology; the user accepts this measurement and the 512 KiB default. The 2 GiB probe reaches all clients; its failed latency assertion informs the separate survival target.

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

**Independent Test**: `SingleWaveJoinStormLoadTest` proves 2,000-client survival without latency assertions; existing `JoinStormLoadTest` keeps waves of 100 and all bounds; unit tests prove the conservative wire-byte bound and pure memoized contributions.

### Tests for User Story 1 (write first, confirm they fail)

- [x] T009 [US1] Write tests first in `jircd-core/src/test/java/net/jircd/core/session/SessionWriterByteLimitTest.java` for the 16384-byte floor: many small entries fit below the limit, the first exceeding enqueue overflows once, drain releases reservations, and a maximum-size legal line fits an empty queue. Compare recipient charges with rendered wire bytes including CRLF: capability combinations, message-tags gating, only forwarded client `+` tags, multibyte/escaped values, delimiters, collisions and truncation; exact ordinary charges and a conservative upper bound in all cases. Include a legal 4094-byte server tag data contribution (4096 with framing). Add tests for thread-safe contribution-byte memoization once per message/capability, distinct messages/capability identities, and accounting/render consistency across queued negotiation/reload changes. Add purity tests for `MessageTagsExtension` and `ServerTimeExtension` under their modules' `src/test/java`: repeated calls with the same immutable message yield equal tags derived from its messageId/sentAt. Record pre-implementation failures of the new accounting behavior; purity characterization tests may already pass.
- [x] T010 [US1] Update `jircd-core/src/test/java/net/jircd/core/session/SessionWriterCloseTest.java` and other tests that assume the 256-entry cap (`rg -l 'QUEUE_CAPACITY|256' jircd-core/src/test`) to fill from a byte limit; preserve the final-line and second-close drain guarantees. Write these tests before changing the queue.
- [x] T011 [US1] Add `jircd-integration-tests/src/test/java/net/jircd/integration/SingleWaveJoinStormLoadTest.java`, tagged `load`: 2,000 continuously reading clients JOIN one channel from one gate, all reach their own end-of-NAMES, zero overflow/unexpected disconnects through explicit teardown; no latency assertion, with finite failure/step/cleanup timeouts. Reuse the hardened shared failure future, bounded connect concurrency and ClientPool ownership. Keep existing `JoinStormLoadTest` waves of 100, scheduled sender, support tests and all latency/time bounds unchanged. Confirm the separate survival test fails on the old queue. If required, configure only the integration build's `loadTest` maximum heap to `2g` with a comment citing T003 (512 MiB exhausts; 2 GiB reaches 2,000 clients); use the same setting for before/after comparisons.

### Implementation for User Story 1

- [x] T012 [US1] In `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java` replace `ArrayBlockingQueue(256)`/`QUEUE_CAPACITY` with a `LinkedBlockingQueue` plus atomic `queuedBytes` against a `limitBytes` constructor argument (keep the 3-argument constructor using `DEFAULT_SENDQ_LIMIT_BYTES`); entries carry their conservative wire-byte charge and release it when taken. Preserve final-line/close guarantees.
- [x] T013 [US1] Implement research D2 and `contracts/outbound-accounting.md` in `SessionWriter`, `OutboundMessage` accounting metadata and the tag-rendering path: compute each enabled capability's escaped contribution byte metadata once per message/capability, memoized safely for concurrent fan-out with message-scoped lifetime; sum the recipient's component bytes, forwarded client `+` entries only with message-tags negotiation, contributions of its negotiated enabled capabilities, all delimiters and CRLF. No fixed allowance or per-recipient serialization; rendering stays at drain time with no uncharged selection changes. Charge a conservative upper bound for collisions/truncation, reserve atomically, undo rejected reservations and invoke the existing overflow path. Document the pure-message `contributeTags` contract at `jircd-core/src/main/java/net/jircd/core/extension/CapabilityExtension.java`; both current implementations already use messageId/sentAt. Run T009's purity and rendered-wire/memoization tests to green.
- [x] T014 [US1] Read the T008 supplier once per connection and pass `sendqLimitBytes` into `new SessionWriter(...)` in `ConnectionHandler.java`; remove the interim discarded `sendqLimitBytes.get();` call.
- [x] T015 [US1] Run T009-T011 tests to green; run the separate single-wave survival load test seven times with all 2,000 clients completing and zero overflow/unexpected disconnects, without latency assertions. Run seven alternating before/after pairs of the unchanged wave-of-100 `JoinStormLoadTest`, `Story1DeliveryLatencyLoadTest` and `ConcurrentConnectionScaleLoadTest`, serially with `--no-daemon` and matching heap/JVM settings; force task execution for each sample. Compare comparable medians in `baseline.md`, retain T002's 512 MiB baseline separately if using 2 GiB, and record results plus written justification for regressions over 10%. Run `./gradlew --no-daemon spotlessApply` then `./gradlew --no-daemon build` to exit 0. Mark completed tasks and stop for review after T015; do not start Phase 4 or commit.

**Checkpoint**: the separate survival test passes at default settings, existing latency tests retain their bounds, and rendered-wire tests prove accounting; overflow still closes with the old reason and no ERROR line. Stop after T015.

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
- [ ] T025 [P] [US3] Document queued wire-data budgeting and additional JVM object/cache overhead, and the policy in `README.md` (FR-012): default, unit, key, minimum, ceiling, the exact `ERROR` text, QUIT/log reason, reload applies to new connections only, worst-case memory (`connections x limit`: 10,000 x 512 KiB = 5 GiB at the default, 10,000 x 2 MiB = 20 GiB at the ceiling), and guidance for raising it; add `sendqLimitBytes` to the example configuration if the README has one (the gitignored local `jircd-server/jircd.yaml` MUST NOT be touched)
- [ ] T026 [US3] Run T022-T023 to green

**Checkpoint**: all three stories complete

---

## Phase 6: Polish and cross-cutting

- [ ] T027 [P] Check that no other code path assumes the removed 256-entry capacity or the old shared overflow callback: `rg 'setOnOverflow|QUEUE_CAPACITY|Excess Flood' --glob '*.java'`
- [ ] T028 `./gradlew --no-daemon spotlessApply` then `./gradlew --no-daemon build` (tests, Spotless, SpotBugs, PMD, coverage 85% line / 75% branch); fix findings without suppressions
- [ ] T029 Run `./gradlew --no-daemon loadTest` once end to end and the quickstart.md steps; paste the before/after medians into the PR description
- [ ] T030 Mark completed tasks here and in `baseline.md` with the results; leave the docs commit separate from code per the repo's convention (do not commit without the user's review)

---

## Dependencies and execution order

- Phase 1 is complete (T001 via rebase; T002 baseline and T003 accepted peak/default are recorded).
- Phase 2 gates everything: T004 -> T005 -> T006 -> T008; T007 parallel after T006.
- US1 (T009-T015) is the MVP and gates US2 because both change `SessionWriter.java`; US2 (T016-T021) follows. US3's test and log work (T022-T024) touches `ConnectionMonitorLog.java` and could start after Phase 2, but its integration test T023 needs US2's overflow path.
- T025 (README) is independent of code after the default is fixed by T003.
- Polish last.

## Parallel examples

- T009 includes tests in core and the two capability modules; assign disjoint files if delegating. T010 and the separate T011 load test have distinct files. All Gradle runs remain serial.
- US2 tests T016, T017, T018 (different files / independent cases).
- T022 and T025 alongside US2 implementation.

## Implementation strategy

MVP is Phase 1 + 2 + US1: the byte limit and a default that passes the storm. Then US2 for the ERROR and reason, then US3 for the log and docs. Stop at each checkpoint for the user's review; do not commit without it.
