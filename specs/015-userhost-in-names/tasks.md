# Tasks: Userhost in NAMES

**Input**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contract](contracts/userhost-in-names.md).

**Prerequisites**: User reviews these artifacts before any implementation. All tasks remain unchecked. This generation makes no implementation, test-pass or performance claims.

**Tests**: Required, tests first. Write and run each story's tests before implementing behavior; save red/green evidence. For a new module, compilation failures from missing types are setup evidence, not sufficient behavioral red evidence: use a minimal no-op provider skeleton to expose failing assertions before adding its hook.

**Format**: `- [ ] Tnnn [P?] [USn?] Description with file path`. [P] is only for disjoint work after its dependencies. One writer per worktree; parallel writers require assigned disjoint scope or separate worktrees.

## Phase 1: Setup (Shared Infrastructure)

- [ ] T001 Record baseline revision (`a2a12ad` or an explicitly justified newer base), clean status, JDK/JVM settings and test commands in `specs/015-userhost-in-names/validation/environment.md`; preserve shared harness changes and never open credential-bearing config.
- [ ] T002 Create `jircd-capabilities/userhost-in-names/build.gradle.kts` with core/protocol dependencies and register the new module in `settings.gradle.kts`, mirroring multi-prefix without adding runtime behavior.

## Phase 2: Foundational (Blocking Prerequisites)

- [ ] T003 Verify actual `CapabilityRegistrar.onNamesIdentity`, `ReplyLimits` and `MemberReplies` contracts against `specs/015-userhost-in-names/contracts/userhost-in-names.md`; record any mismatch before changing scope, keeping capability behavior outside core.
- [ ] T004 Record the fixtures and expected assertions for FR-001–009 / SC-001–004 in `specs/015-userhost-in-names/validation/coverage-map.md`, including UTF-8 boundaries and invalid-reload atomicity; use existing fixtures instead of a new framework.

**Checkpoint**: Module can compile tests; existing SPI and validation matrix are confirmed.

## Phase 3: User Story 1 - Populate identities without WHO (Priority: P1, MVP)

**Goal**: Negotiated identities use only permitted display hosts; ordinary clients retain nickname identities.

**Independent Test**: Real socket negotiated/ordinary clients see exact expected tokens across NAMES/JOIN/SAJOIN, complete with 366; protected real hosts never appear.

### Tests first

- [ ] T005 [P] [US1] Add `jircd-capabilities/userhost-in-names/src/test/java/net/jircd/capabilities/userhostinnames/UserhostInNamesExtensionTest.java` for descriptor name, identity-only registration, exact rendering, no status duplication, and byte bound `I=U+H+2`; enforce non-null identity fields and nonnegative ReplyLimits through existing contracts rather than duplicate validation.
- [ ] T006 [P] [US1] Add `jircd-integration-tests/src/test/java/net/jircd/integration/UserhostInNamesTest.java` covering pre/post-registration CAP ACK/LIST, negotiated vs ordinary peers, CAP removal, uncloaked and cloaked display hosts, targeted/bare NAMES, JOIN/SAJOIN, empty/visibility cases, final 366 and unchanged WHO; assert complete content and explicit absence of protected real host.
- [ ] T007 [US1] Wire provider test/runtime dependencies as needed in `jircd-integration-tests/build.gradle.kts`, then record behaviorally failing T005/T006 runs in `specs/015-userhost-in-names/validation/tests.md` before implementing the hook.

### Implementation

- [ ] T008 [US1] Add `USERHOST_IN_NAMES` to `jircd-protocol/src/main/java/net/jircd/protocol/CapabilityName.java` and implement/document `jircd-capabilities/userhost-in-names/src/main/java/net/jircd/capabilities/userhostinnames/UserhostInNamesExtension.java`: register only the identity hook, render `nickname!ident@displayHost`, declare `I=U+H+2` with checked long arithmetic, no mutable resources, I/O, live-session lookup or multi-prefix dependency.
- [ ] T009 [US1] Add `jircd-capabilities/userhost-in-names/src/main/resources/META-INF/services/net.jircd.core.extension.CapabilityExtension` and runtime packaging in `jircd-server/build.gradle.kts`; use discovery rather than a hard-coded core provider list.
- [ ] T010 [US1] Run scoped provider/socket tests and record green results and negotiated-vs-ordinary real-use transcript in `specs/015-userhost-in-names/validation/tests.md` and `validation/socket.md`.

**Checkpoint**: US1 is demonstrable without multi-prefix. It is an MVP increment, not permission to merge before all issue acceptance criteria.

## Phase 4: User Story 2 - Compose statuses and safe complete lists (Priority: P1)

**Goal**: Four negotiation combinations work with complete identities and bounded 353 output.

**Independent Test**: An operator+voice member receives the correct prefix/identity in each combination; multi-line output reconstructs the expected complete member set once and ends in 366.

### Tests first

- [ ] T011 [P] [US2] Extend `jircd-integration-tests/src/test/java/net/jircd/integration/UserhostInNamesTest.java` with the four-way matrix and exact `@+nick!ident@displayHost` assertion, multi-member/multibyte lists, and unchanged multi-prefix WHO behavior; run and record results before any corrective implementation.
- [ ] T012 [P] [US2] Extend `jircd-integration-tests/src/test/java/net/jircd/integration/NamesByteBudgetTest.java` using actual installed descriptors, `I=U+H+2` and P=2; verify exact-fit/one-byte-over configurations, disabled installed providers, 366 separately, maximum UTF-8 identities, complete token packing across multiple <=512-byte command sections including CRLF, no lost/duplicate members or truncated identities, and atomic invalid reload.

### Integration and verification

- [ ] T013 [US2] Run `NamesByteBudgetTest` and the four-way matrix, recording results in `specs/015-userhost-in-names/validation/tests.md`; if existing generic composition already passes, record that fact without artificial code changes; fix only attributable provider/wiring defects and reassess the plan before any new core behavior.
- [ ] T014 [US2] Document measured boundary values and the installed-but-disabled compatibility consequence in `specs/015-userhost-in-names/validation/byte-budgets.md`, verifying `S+2N+C+13+P+I<=512` and `S+N+C+29<=512` alongside existing independent hostmask constraints.

**Checkpoint**: Every bounded listing is complete and privacy rules still hold when status/identity hooks compose.

## Phase 5: User Story 3 - Control availability (Priority: P2)

**Goal**: Discovery/config/runtime controls behave like other capability providers.

**Independent Test**: Enabled/disabled/omitted startup configurations and connected-client toggles/reload show correct LS/LIST, ACK/NAK, NEW/DEL and newly captured identities.

### Tests first

- [ ] T015 [US3] Add `jircd-integration-tests/src/test/java/net/jircd/integration/UserhostInNamesLifecycleTest.java` for real discovery/config (`capabilities` only, `enabled`/`disabled`, omitted disabled), rejected invalid/kind-mismatched config, atomic combined REQ NAK, reload/toggle, cap-notify eligibility, withdrawal/pruning and re-enable requiring explicit REQ; respect output captured before DEL and preserve prior state on invalid reload.
- [ ] T016 [US3] Record red/green lifecycle results in `specs/015-userhost-in-names/validation/tests.md` after T015; fix provider metadata/packaging defects in `jircd-capabilities/userhost-in-names/src/main/java/net/jircd/capabilities/userhostinnames/UserhostInNamesExtension.java` only if needed, relying on generic lifecycle rather than bespoke core code.

**Checkpoint**: Operator controls and recipient negotiation produce consistent new captures.

## Phase 6: Polish and Cross-Cutting Validation

- [ ] T017 [P] Update `README.md` and `specs/001-ircv3-server/contracts/server-configuration.md` with capability availability, configuration example, cloak behavior, combined status example, module/SPI ownership and generic NAMES formula including I; explain disabled-installed bounds and how oversized S/N/C limits are reduced without weakening safety.
- [ ] T018 [P] Add tagged `jircd-integration-tests/src/test/java/net/jircd/integration/UserhostInNamesLoadTest.java` with a repeatable 10/100/1,000-visible-member negotiated NAMES workload, bounded queues, no missing members and p99 completion <1 s; reuse load fixtures and save a matching test-only baseline workload without copying capability implementation.
- [ ] T019 Run `./gradlew build` and resolve relevant failures, recording command, revision and results in `specs/015-userhost-in-names/validation/build.md`; require clean Spotless/SpotBugs/PMD, no coverage decrease and aggregate floors 85% line / 75% branch.
- [ ] T020 Provision a separate harness Git worktree through the coordinator and record the existing shared harness state plus new patch in `specs/015-userhost-in-names/validation/irctest-environment.md`; explicitly change `irctest/controllers/jircd.py` in that harness worktree to enable AND declare multi-prefix/userhost-in-names, preserving old frozen hashes and user modifications. This is a harness change, not server implementation; stop/report provisioning or permission blockers rather than editing the shared harness.
- [ ] T021 Build baseline/candidate distributions and run the three named CAP cases then the full identical revised irctest selection; save revised controller hashes, commands, JUnit/raw output and case-level delta in `specs/015-userhost-in-names/validation/irctest.md` and `validation/raw/`; require all three candidate targets pass and explain every new failure/skip, cleaning up only run-owned servers.
- [ ] T022 Run new NAMES and existing JOIN/delivery/fan-out loads plus `./gradlew loadTest`, with warmup and >=5 matching measured baseline/candidate runs; record medians/p99/max, throughput, CPU/allocation profiles, workload/JVM/machine and raw paths in `specs/015-userhost-in-names/validation/performance.md`; enforce plan budgets and flag >10% regressions for optimization or written review justification.
- [ ] T023 Execute the socket walkthrough in `specs/015-userhost-in-names/quickstart.md` and save exact negotiation, cloak, split-list and lifecycle evidence in `specs/015-userhost-in-names/validation/socket.md`.
- [ ] T024 Review the diff against `.github/REVIEW_CHECKLIST.md`; perform trusted-branch local Sonar analysis when available and obtain an independent other-vendor review before any PR as AGENTS.md requires, fixing real findings and recording results in `specs/015-userhost-in-names/validation/review.md` without reading credential files.
- [ ] T025 Rerun `./gradlew build` after review fixes as needed and reconcile acceptance criteria/results in `specs/015-userhost-in-names/validation/acceptance.md`; report unresolved blockers, and create no commit/PR without subsequent authorization.

## Dependencies and Execution Order

Setup T001–T002 → foundation T003–T004 → US1 T005–T010 → US2 T011–T014 → US3 T015–T016 → final validation T017–T025. US2 and US3 depend on US1 but are independently verifiable with their documented fixtures; sequential delivery minimizes shared-file conflicts. Within each story, tests precede behavior and corrective changes.

T005/T006 can be authored in parallel after foundation; T007 runs after both. T011/T012 use separate test files and can be authored together after US1. T017/T018 can proceed together after story checkpoints. T019 follows T017/T018; T020 provisions the isolated harness before T021. T021/T022 run sequentially to avoid measurement interference; both precede final acceptance. T024's fixes require relevant scoped checks and T025 build again when code changed.

## Parallel Examples per Story

- **US1**: T005 provider unit assertions and T006 socket scenarios touch different files; merge and execute both before T008.
- **US2**: T011 status matrix and T012 budget tests touch different files; combine both results in T013.
- **US3**: T015/T016 are sequential (test then verify/fix); a read-only reviewer can inspect lifecycle expectations while the single writer authors T015. No independent implementation task merits a parallel marker.

## Implementation Strategy

Deliver US1 as the first demonstrable increment, then prove combined limits and operator controls. Stop for verification at each checkpoint. US1 alone is not full acceptance for #117: all three stories plus irctest, docs, build, performance and review gates are required. If isolated harness provisioning is blocked, report the blocker; do not silently defer the three target cases or call the feature complete.

## Coverage and Task Summary

25 tasks: setup 2, foundation 2, US1 6, US2 4, US3 2, cross-cutting 9. FR-001/002/003/004 → T005–T010; FR-005/006/007 → T011–T014; FR-008/009 → T015–T016. SC-001/002 → story tests and T023; SC-003 → T020–T021; SC-004 → T018/T022. Documentation T017 and gates T019/T024/T025 complete the acceptance chain.
