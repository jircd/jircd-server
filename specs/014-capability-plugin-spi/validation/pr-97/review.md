# PR 3 (#97) review (T053)

Self-check of the uncommitted diff on `014-capability-lifecycle` (base `193752d`) against [`.github/REVIEW_CHECKLIST.md`](../../../../.github/REVIEW_CHECKLIST.md) and the #97 contracts. Results are in [results.md](results.md).

## Not run here

- **SonarCloud branch analysis and Codex review:** run by the coordinator after the first pass; their findings and fixes are in "Review round 1" below.
- **irctest:** not run, since it runs only on request. The known tradeoff to record when it is run: output captured before a disable may follow the `DEL`. Both contracts accept this and promise no output cutoff.

## Integration-test edits are limited to the planned ones

Existing integration files changed:

| File | Change |
| --- | --- |
| `Story2CapabilityListTest` | exact LS set gains `cap-notify` (test renamed to say so) |
| `CapabilityProviderAbsenceTest` | the service-file filter hides a set of classes instead of one (to test "all six absent"); expected LS sets gain `cap-notify`; the LS parser accepts a one-token list without `:`; new all-providers-absent test; each missing-provider case also checks `CAP REQ :cap-notify` ACKs |

No other existing integration assertion changed. New files: `CapabilityLifecycleTest`, `CapabilityGroupLifecycleTest`, `CapabilityPublicationTimingLoadTest` (tag `load`).

Core unit fixtures that mutated `negotiatedCapabilities()` in place (5 files) now go through `TestNegotiation` (T047). Two existing core tests encoded the old contract and were updated:
- `ExtensionRegistryCapabilityTest.disablingAProviderThatAnotherRequires…` now expects the dependent to be withdrawn with its prerequisite, not the disable refused.
- `CapabilityNegotiatorTest` expects core `cap-notify` among the offers and uses `candidate` instead of the removed `isAcceptable`.

## Out-of-scope mechanisms

- No strict output-cutoff test: nothing asserts that output captured before a disable is suppressed.
- No lifecycle admission barrier: ordinary output appends without rechecking state; only group admission makes the current prerequisite choice.
- No callback lifetime tracking: stop does not wait for callbacks.

## Checklist

| Item | Finding / resolution |
| --- | --- |
| S3077 volatile non-thread-safe types | First draft had `volatile Set`/`Collection`/`Supplier` fields in `ClientSession` and `CapabilityRuntime`. Now `AtomicReference`; the CAP version is an `AtomicInteger` updated with `accumulateAndGet(max)` |
| S2445 synchronize on private final | The administrative mutex is a `private final Object`. A test's `synchronized` on a stream was removed (SpotBugs USO) |
| S1181 / extension boundary | Provider code under the mutex: `providedCapability()` was first called under it to classify "known" names. It is now read once at `register` (`knownCapabilityNames`), outside every lock. Group admission calls no provider; `prepareGroup` runs the shaper before the mutex |
| S3776 complexity, S3358 nested ternary | Helpers extracted (`chunks`, `line`, `prerequisitesFirst`/`place`, `requirePrerequisitesEnabled`); no nested ternaries |
| S5841 / S5853 tests | `isNotEmpty()` before `noneMatch`; the race test asserts `isEmpty()` rather than `doesNotContain` |
| S2925 no sleep | No `Thread.sleep`; latches and bounded `get`/`join` throughout |
| Failure and cleanup paths | Publication swaps, prunes and then notifies. One recipient whose notification cannot be built (an over-long target) is logged and skipped; the state stays published (tested). Overflow while queuing `DEL` hands that recipient to its existing cleanup thread and does not block (tested). Rolling back a failed enable withdraws nothing it did not publish. Dependents withdrawn by a prerequisite's disable are stopped and their handlers removed; each records its own outcome |
| Concurrency and reload | One mutex covers publish + prune and CAP read-validate-commit-reply, so a REQ racing a disable cannot resurrect a pruned name (200-iteration race test). Live-session add/remove and writer attach take the same mutex. Lock order is lifecycle → mutex → one writer lock; no delivery path takes the mutex under a channel monitor (`Channel.underMonitor` is used only by registry membership transitions). REHASH validates the whole file and the whole candidate provider graph before changing anything, then holds the lifecycle lock for the whole reconciliation: starts (prerequisites first, outside the mutex), one publication with the new limits and configuration, then stops (dependents first). A nickname change and its own `NICK` echo take the mutex too (round 1, A4) |
| Protocol size limits | NEW/DEL split on name boundaries into independently valid lines ≤ 512 bytes. LS/LIST use 302 continuation when needed. Enable rejects an offer set that cannot fit one legacy LS line at the configured nickname maximum. A later longer nickname limit would make LS/LIST fall back to continuation lines rather than throw or truncate (defensive; see open points). ACK/NAK are never split, and an oversize echo is still `417` with no mutation |
| Hostile / oversized input | LS version parsing is linear and saturating, with no allocation that grows with the input; a 23-digit version is tested. Oversize REQ echo gets 417 without mutation |
| Validation edge cases | Empty REQ, bare `-`, unknown `-name`, `-cap-notify` after 302, malformed/negative/empty/zero/huge versions, an all-providers-absent catalog, a reload whose dependent's prerequisite is disabled or omitted: all tested |
| Rejected requests change nothing | NAK and 417 paths commit nothing (unit and socket tests check LIST afterwards); invalid REHASH changes no state and sends no DEL (socket test) |
| Counters and accounting | DEL/NEW use the writer's ordinary byte accounting; no new counters |
| Security | Notifications carry names only. REHASH failure notices carry the provider id and the exception message, as `EXTENSION` already did for its own failures |
| Determinism | LS offers, LIST and NEW/DEL are sorted; no locale- or time-dependent output (state names use `Locale.ROOT`) |
| Public API compatibility | Unreleased `0.1.0-SNAPSHOT`. The README migration table lists: `negotiatedCapabilities()` is an immutable snapshot; `CapabilityNegotiator.isAcceptable`/`apply` → `candidate`/`request`; `CapabilityNegotiationGrammar.ls`/`list` → `lsReplies`/`listReplies` (+ `notifications`); `CapabilityRuntime.withdraw` returns the withdrawn ids; disabling a prerequisite now withdraws its dependents. `RuntimeSnapshot.without` was removed once no production code used it. Also new: `ExtensionRegistry.capabilityControl`, `attachLiveSessions`, `knownCapabilityNames`, `applyDesiredState` with `DesiredState` and `InvalidDesiredStateException`; `RuntimeSnapshot.withdrawalOrder`, `descriptors`; `CapabilityRuntime.publish(change, alongside)`; `ConfigurationReloader.reloadWithOutcome`; `TagRenderer.prepareGroup`/`admitGroup` (defaults keep custom renderers working) |
| Tests that pass while the code is wrong | The new integration tests failed on the unchanged tree (results.md). Assertions are exact wire lines, final negotiated sets and states. The overlap tests prove the overlap: the unadmitted-group test holds the hook open on a latch while the disable runs; the mutex test shows the disable has not completed while the mutex is held |
| Test reliability | Every wait is bounded; writers and clients are closed in `try`/`finally`; the load test is tagged `load` and excluded from `test` |
| Docs and contracts | Updated `specs/001-ircv3-server/contracts/server-configuration.md` (runtime capability changes, amending path equivalence), `irc-protocol-commands.md` (LS/LIST/REQ/NEW/DEL rows, allowed LS names, runtime note), `data-model.md` (negotiation fields, capability lifecycle) and README (runtime section, status, migration rows). Javadoc on `CapabilityRuntime`, `CapabilityControl`, `PreparedGroup`, `ClientSession`, `CapabilityNegotiator`, `CapCommandHandler`, `ConfigurationReloader`, `ConnectionHandler.liveSessions`, `TagRenderer`. Runtime hook-fault withdrawal is left to #102, as the contracts say |

## Open points for the reviewer

1. **Mutex amendment.** "The existing administrative control mutex" in spec/plan/contracts is implemented as the two-level design the user chose (results.md). The canonical contracts I updated describe it as "the capability runtime's administrative mutex". The 014 design docs still say "existing"; I did not edit those approved design docs.
2. **Capture per recipient.** Ordinary tag preparation takes the mutex for each recipient to capture snapshot + negotiation together (FR-005). The existing delivery loads show no change at this resolution; #102's controlled measurement should profile it.
3. **Dependents' state after a prerequisite's administrative disable.** They end `DISABLED` (they stopped cleanly). Start failure still ends `FAILED`. The contracts define FAILED for start failure but name no state for an administrative cascade; tell me if `FAILED` is wanted.
4. **Missing hard prerequisite on enable.** Rejected with an actionable error and recorded `FAILED`, matching every other rejected enable in the existing tests, rather than left `DISABLED`. A REHASH with such a configuration is now rejected as invalid before anything changes.
5. **Legacy LS after a later nickname-limit increase.** Enable validates the one-line LS at the current nickname maximum, but a REHASH raising `nicknameMaxLength` is not re-checked against it. In that case the handler sends continuation lines rather than throwing. With today's seven offers (about 80 bytes) this cannot happen: the reply budgets every reload already enforces (WHO's `2S + 2N + … ≤ 512`) keep server name plus nickname far below what would push an LS line past 512 bytes.

## Review round 1

Each item was checked against the code before any change. All were real. The behavior bugs got failing tests first; every one of these tests failed on the round-0 code and passes now.

### Codex (cross-vendor)

| # | Finding | Verified | Change |
| --- | --- | --- | --- |
| A1 | Reload prevalidation checked only desired prerequisite booleans, not the graph: an A↔B cycle passed, an unrelated disable applied, the enables failed, `current` became the invalid config | Real: `requirePrerequisitesEnabled` only looked at booleans | `ExtensionRegistry.applyDesiredState` validates the whole candidate graph before changing anything. The candidate is the published providers, minus what the reload withdraws (including cascaded dependents), plus every provider it enables, staged. `RuntimeSnapshot.of` checks it (unknown or disabled prerequisite, cycle, duplicate offer, owned key, slot, command), and the reply budgets are checked at the new lengths. A provider desired enabled but pulled out by a disable cascade is also invalid. Invalid → `ConfigurationException`, nothing changed. A provider whose own `register` throws is a provider fault, not an invalid file: it and its staged dependents end FAILED alone. Tests: `aDependencyCycleIsInvalidAndAnUnrelatedDisableIsNotApplied`, `disablingAPrerequisiteOfADependentDesiredEnabledIsInvalid`, `aProviderWhoseStagingFailsIsIsolatedWithItsDependents` |
| A2 | Reconciliation published each toggle separately and released the locks in between; limits and config were published afterwards; `EXTENSION` could interleave | Real: `reconcileSection` called `enable`/`disable` per id, and `updateConfiguredLengths` ran after | Reload now runs inside one lifecycle-lock hold. Starts run first, prerequisites first, outside the runtime mutex. Then one `CapabilityRuntime.publish(change, alongside)` swaps a snapshot carrying every capability change and the new reply limits, and, in the same critical section, the advertised lengths and `ConfigurationReloader.current`. That gives one prune and one `DEL`/`NEW` per reload. Withdrawn providers stop after the publication, outside the mutex. Server extensions are reconciled one by one inside the same hold. Tests: `aValidReloadIsOnePublicationSentAsOneDel` (one `CAP * DEL :base other`; round 0 sent two lines), `theWholeReloadHoldsTheLifecycleSoNothingIsPublishedUntilEveryStartIsDone` (mid-start the disable is not yet published, and a concurrent `EXTENSION` waits). The publish-failure fallback that would have published a partial batch was removed: the graph is validated under the same lock hold, so refusal is impossible, and if it happened anyway every start in the batch is rolled back with nothing published |
| A3 | `withdrawalClosure` was a `TreeSet`, so cleanup stopped dependents in name order | Real: with `z→a→root`, the round-0 test stopped `a, z, root` | `RuntimeSnapshot.withdrawalOrder` returns a dependents-first order (each provider before everything it requires, deterministic). `withdraw` and the reload's stops use it. The snapshot's provider map is now sorted, so the order never depends on hashing. Test: `transitiveDependentsAreStoppedBeforeWhatTheyRequire` (`z, a, root`) |
| A4 | Notification target races a nickname change | Real: a 200-change race test caught `CAP n0 DEL` after the client had been sent `NICK n1` | Fixed, small: `NickCommandHandler` sets the nickname and queues the client's own `NICK` line in one step under the administrative mutex; peers are told outside it. Lock order is unchanged (mutex → one writer lock; NICK holds no other lock there). A publication therefore addresses the nickname the client knows at that point in its stream. Tests: `aNickChangeWaitsForAPublicationHoldingTheMutex` (deterministic, bounded) and `racingPublicationsAlwaysAddressTheNicknameTheClientCurrentlyKnows` (200 changes against 200 toggles; stable over 5 repeated runs) |

### SonarCloud branch scan

| Rule | Location | Change |
| --- | --- | --- |
| javabugs:S2259 | `ExtensionCommandHandler` (`reportOutcome`) | `stateOf` is null for an unknown id. The id was checked before the toggle and registrations are never removed, but the null case now reports "unknown" instead of dereferencing |
| java:S3077 | `CapabilityRuntime.snapshot` | `AtomicReference<RuntimeSnapshot>` |
| java:S9412 | `CapabilityNegotiator`, `CapCommandHandler` | `List.sort(Comparator.naturalOrder())` |
| java:S5411 | `ConfigurationReloader` (boxed `Boolean` condition) | That code moved into `ExtensionRegistry.applyDesiredState`; every boxed condition there uses `Boolean.TRUE/FALSE.equals` |
| java:S7467 | `CapabilityRuntimeTest` catches | `catch (… _)` |
| java:S1130 | `CapabilityRuntimeTest.anAdmittedGroupOnAStalledSocket…` | `throws Exception` removed. Sonar's line numbers predate the round's new test fixture: its line 318 is this method |

### Design docs

`plan.md` (Complexity Tracking) and `research.md` (Decision 2) carry a dated amendment. It records that "the existing administrative control mutex" is implemented as the two-level arrangement, with the outer lifecycle lock now held for a whole reload and NICK serialized under the inner mutex. The contract `specs/001-ircv3-server/contracts/server-configuration.md` was updated for the reload order and the NICK serialization.

### Build after round 1

`./gradlew spotlessApply` then `./gradlew build --rerun-tasks`: exit code 0. 1333 tests, 0 failed, 0 skipped; Spotless, SpotBugs, PMD and coverage verification green. Coverage: line 95.93% (6181 of 6443), branch 88.79% (2527 of 2846), both above the #101 baseline (95.72% / 88.70%). One intermediate round-1 build measured 95.52% / 88.41%. The new batch failure paths had no tests; tests for start-failure cascade, staging failure, invalid cascade, retried cleanup (disable and enable) and an unheld id were added, and the unreachable fallback was removed, not covered.

## Review round 2

The Codex reviewer confirmed the four round-1 fixes and found no lock-order or deadlock issue. It found three P2 defects in the reload failure paths. Each was verified against the code and is real. Each got a test in the new `ConfigurationReloaderFailurePathTest` that failed on the round-1 code and passes now.

| # | Finding | Verified | Change |
| --- | --- | --- | --- |
| 1 | A pending cleanup is forgotten when a later enabling reload fails | Real: `markFailed(entry, false)` assigned `cleanupPending = stopFailed \|\| commands installed`, clearing a flag an earlier failed `stop()` had set. On round-1 code the test's later `disable` was a no-op (no `stop`, state stayed FAILED) | `markFailed` only ever adds to `cleanupPending`; the flag clears only where a cleanup actually succeeds (`finish`) or a start is published. Test `aFailedReEnableKeepsTheCleanupAnEarlierFailedStopLeftForTheNextDisable`: failed stop → reload re-enable fails at staging → `disable` runs `stop` and ends DISABLED |
| 2 | A valid command hand-over fails during a reload | Real: `requireUnownedCommands` saw the withdrawn owner's handler, since cleanup runs after publication; on round-1 code B ended FAILED with "Command TAGMSG is already handled", A was withdrawn and the command was gone | Commands the reload's withdrawn providers own are releasable. A provider the batch enables that claims one gets its guarded handler built before the publication and installed inside it (`installHandover`, in the publication's critical section), so the command moves in the same step as the snapshot swap. Validation still rejects two candidates claiming one command (the candidate graph check). The old owner's later cleanup leaves the new handler alone, because removal is conditional on the handler being the one installed. A registrar failure there is contained and logged: the provider stays published without that command. Test `aReloadMovesACommandFromAWithdrawnOwnerToANewlyEnabledOne`: no failures, B ENABLED, A DISABLED, the command runs B's handler |
| 3 | Batch rollback stops prerequisites before dependents | Real, with a precision. An ordinary exception from C's start is caught and fails C alone; the whole-batch rollback runs only when something escapes the batch (an `Error` other than `LinkageError`, or a refused publication). In that case `started` (prerequisites first) was rolled back forwards: the test saw `stop c, stop a, stop b` | The rollback iterates `started` in reverse, so each provider stops before what it requires (the order round 1 gave withdrawals). Test `aStartThatEscapesRollsTheBatchBackDependentsFirst`: `start a, start b, stop c, stop b, stop a`, nothing published, the error propagates |

**Sonar S3776 (cognitive complexity 17 in `planCapabilities`):** extracted `classify`, `requireNoneDesiredEnabled` and `validateCandidate`. The round-2 changes would have pushed `startForBatch` over the limit too, so it was split the same way: `mayStart` for the prerequisite and pending-cleanup checks and `splitCommands` for the hand-over split. `applyCapabilities` moved its post-publication stops into `finishWithdrawn`, and its working state into a small `Batch` holder. Behavior is unchanged: the round-1 tests pass without edits. I estimated complexity by hand, since Sonar can only be rerun on the next branch scan.

**Build after round 2:** `./gradlew spotlessApply` then `./gradlew build --rerun-tasks` and a final `./gradlew build`, both exit code 0. 1336 tests, 0 failed, 0 skipped; Spotless, SpotBugs, PMD and coverage verification green. Coverage: line 95.96% (6230 of 6492), branch 88.79% (2543 of 2864), both above #101 (95.72% / 88.70%).

## Review round 3

The Codex reviewer confirmed the round-2 `cleanupPending` fix and the reverse-order rollback. It found three defects in the command hand-over's failure paths, and SonarCloud's gate failed on java:S3078. As asked, these were treated as one structural problem, not patched one at a time. Provider state was published in one step and command handlers were installed in another (core's mutable command table, after the swap).

**Spec/plan check before implementing.** The 014 contracts require "scoped command registration" and that a provider's command is executable only while the provider is enabled (capability-spi.md: `command(command, handler)`, "Enabled provider…"; plan.md: "provider-owned staged command registration"). Nothing requires installing provider commands into core's command table. Resolving them from the published snapshot is consistent with both, so I proceeded without stopping.

**Structure now.** A capability provider's commands are part of its published descriptor:
- **Index:** `RuntimeSnapshot` indexes them (`command(Command)`; validation already guarantees one owner).
- **Lookup:** `ExtensionRegistry.capabilityCommand(Command)` resolves a command from one read of the current snapshot and wraps it for fault containment.
- **Dispatch:** `ConnectionHandler` consults core's table first (core and server-extension commands), then that lookup.
- **One publication:** a provider and its commands therefore appear, disappear and move between providers in the same atomic swap, and dispatch sees the old set and its handlers or the new set and its handlers, never a mix. A handler resolved before a swap may still run afterwards, as captured work.
- **Nothing to fail:** there is no registration step left that can fail once anything is visible. The only command-related failure point is the provider's own staging, which runs before validation and publication and is handled as a staging failure.
- **Removed:**
  - capability commands no longer go through `CommandRegistrar`: install, rollback, `installedCommands`, conditional removal on disable, the "refuse once withdrawn" guard, the hand-over sets and `installHandover`
  - `HookDispatcher.guardedCommand(registration, live, refused)`
  - the command-related `cleanupPending` bookkeeping

  `cleanupPending` now records only an owed `stop`.
- **Unchanged:** `CommandRegistrar` stays for server extensions (admin), whose model is unchanged, and a provider still cannot claim a command core or a server extension handles.

| # | Finding | Verified | Resolution |
| --- | --- | --- | --- |
| 1 (P1) | An `Error` on the second hand-over registration left B published, stopped, and partly installed | Real. With round-2 sources restored, `anErrorFromCoresTableOnTheSecondMovedCommandCannotHalfEnableTheNewOwner` failed: the `Error` escaped the reload after the swap | No registration step exists after publication; the test passes (B ENABLED, both commands run B's handler) |
| 2 (P2) | A `RuntimeException` on a hand-over registration was only logged; B stayed ENABLED without the command, with no failure reported | Real. On round-2 sources `…RuntimeException…` failed with `421 client WALLOPS :Unknown command` | Same; the test passes |
| 3 (P2) | Between the swap and the handler installation, dispatch could fetch A's handler, see A gone and answer 421 | Real. `dispatchDuringRepeatedHandOversNeverAnswersUnknownCommand` (300 hand-overs against a tight real-dispatch loop through `ConnectionHandler.processLine`) failed on round-2 sources in 3 of 3 runs with repeated `421 client TAGMSG` | Lookup and handler come from one snapshot read; the test passes in 6 of 6 runs here (including 5 repeats) |
| S3078 | `markFailed` read-modify-wrote a volatile (`cleanupPending = cleanupPending \|\| …`) | Real | `markFailed` only sets the flag (`if (stopFailed) cleanupPending = true`); the installed-commands term is gone |

The round-2 sources for the "fails on current code" runs came from the round-2 build's JaCoCo HTML, which renders each source file in full: `ExtensionRegistry`, `ConnectionHandler`, `RuntimeSnapshot` and `HookDispatcher`. They were swapped in with only the `processLine` test seam added, the five new tests ran, and the round-3 files were restored from a backup. Two of the five new tests, a `RuntimeException` and an `Error` thrown by the provider's own staging at its second command, also pass on round-2 sources. That path was not broken; they pin the new design's single failure point: the new owner ends FAILED with nothing of it visible, or, for an `Error`, nothing changes and the old owner keeps both commands.

**Tests migrated** (they tested the removed installation mechanism):
- **`ExtensionRegistryCapabilityTest`:** nine tests of registrar install/unregister failures, leftover refusing handlers and hooks-before-commands ordering were replaced. `providerCommandsResolveFromTheSnapshotAndNeverTouchCoresTable` covers resolution while enabled, absence after disable, and an untouched core table. `aRegistrarThatCannotInstallOrRemoveHandlersCannotHalfEnableOrStrandAProvider` covers a registrar that throws on every install and removal, with no effect on enable or disable. The fault-containment test now resolves through `capabilityCommand`.
- **`TagmsgCommandHandlerTest` (message-tags):** dispatches TAGMSG through `capabilityCommand`, and asserts the provider never writes core's table.
- **`ConfigurationReloaderFailurePathTest`:** the round-2 hand-over test dispatches the same way.

**Coverage.** One run of the first round-3 build measured branch 88.69%, 0.01 pp under #101. The previous run of identical code had given 88.76%, because some branches are reached only under particular interleavings. Deterministic tests were added for the new failure paths:
- a withdrawn provider's failing stop
- a retried cleanup failing again, on disable and on re-enable
- a start failure without a message
- a rollback whose stop fails, keeping the cleanup owed
- a dependent's failing stop on its prerequisite's disable
- `RuntimeSnapshot.effective`

**Build after round 3:** `./gradlew spotlessApply` then `./gradlew build --rerun-tasks`, twice in a row, both exit code 0. 1339 tests, 0 failed, 0 skipped; Spotless, SpotBugs, PMD and coverage verification green. Coverage: line 96.02% (6160 of 6415), branch 89.11% then 89.08%. README (SPI table, migration rows) and Javadoc were updated for the snapshot-resolved commands.

## Review round 4

The Codex reviewer confirmed that snapshot-based dispatch fixes the three round-3 findings and found nothing wrong in case folding, registration gating, rate limits, HELP/ISUPPORT or lookup after withdrawal. Two findings and one Sonar issue remained; all three were verified against the code and are real.

| # | Finding | Verified | Change |
| --- | --- | --- | --- |
| 1 (P2) | `CommandRegistrar.isRegistered` saw only core's mutable table, so a server extension registering after a provider enabled X could claim X and, with core-first dispatch, shadow the still-advertised provider | Real: `isRegistered` returned `handlers.containsKey(command)`. New test `ConnectionHandlerCommandTableTest.aCommandAPublishedProviderOwnsIsRegisteredAndCannotBeClaimedByALaterServerExtension` failed on it (`isRegistered(TAGMSG)` was false) | The registrar's ownership now covers both sources dispatch reads. `isRegistered(X)` is true if core's table has X or the current snapshot's provider owns X. `register(X, …)` is refused with an `IllegalArgumentException` while a published provider owns X. Both key on the same `Command` that dispatch folds a line to (the test sends lowercase `tagmsg` and it reaches the provider). An extension's registrations run in its lifecycle callback, which the registry serializes with provider publication, so a provider enable cannot fall between the check and the install. Once the provider is withdrawn, X is free again (tested). The provider-side check (`requireUnownedCommands`) now counts only commands no published provider owns: provider-vs-provider ownership stays with snapshot validation, so the A→B hand-over within one reload keeps working (the round-2 and round-3 hand-over tests pass unchanged) |
| 2 (P2) | The concurrent hand-over test could pass with zero swaps: worker exceptions were dropped, `done` was set in `finally`, and the main loop was unbounded if the worker hung | Real, as described | The worker is a `CompletableFuture`; `get(30 s)` rethrows its failure or fails on a hang. Every completed swap is counted and the test asserts all 300 happened. The dispatch loop ends when the worker is done or after a 30 s deadline. The 421 and handled-count assertions are unchanged. Stable in 3 repeated runs plus the full build |
| S5778 | `CommandHandoverDispatchTest` (`assertThatThrownBy` lambda made two calls) | Real: `handOver(true)` was evaluated inside the lambda | The desired state is built before the assertion; the lambda makes one call |

**Build after round 4:** `./gradlew spotlessApply` then `./gradlew build --rerun-tasks`: exit code 0. 1340 tests, 0 failed, 0 skipped; Spotless, SpotBugs, PMD and coverage verification green. Coverage: line 96.03% (6165 of 6420), branch 89.11% (2520 of 2828).

## PR review (Sourcery)

Sourcery left ten threads on #108 after the rebase onto `037f0c8`. Each was checked against the code at `6a970b7`. Seven are real and fixed test-first, one is already prevented and gets a regression test only, one (H2) is fixed defensively at the user's request, and one is declined with evidence. Every "failed before the fix" below was run against `6a970b7` with only the new test added.

| # | Finding | Verdict | Evidence and change |
| --- | --- | --- | --- |
| H1 | `publish` swapped the snapshot before `alongside`, so an `alongside` that threw left the new snapshot published with no prune or notification, and the reload then rolled back providers that were in it | Real at the API level. Today's callback only assigns two fields, but `publish(change, alongside)` takes any `Runnable` | `alongside` now runs before the swap, and the registry's callback runs the configuration publication before the `supportedFeatures` write. From the swap on, nothing that can fail runs before the prune and the per-recipient (already guarded) notifications. `CapabilityRuntimeTest.aPublicationWhoseAlongsideStepThrowsSwapsPrunesAndNotifiesNothing` and `ConfigurationReloaderFailurePathTest.aConfigurationPublicationThatThrowsPublishesNothingAndRollsTheStartsBack` both failed before (snapshot swapped; provider `a` left in the snapshot) |
| H2 | Writer attached under control before the overflow callback is installed | Fixed defensively (no reachable failure today) | A publication queues to a session only if `notificationEligible()` (`highestCapVersion >= 302` or `cap-notify` negotiated, `ClientSession.java:230`), and both need a CAP line that is read only after the callbacks are installed, so the window could not be hit. At the user's request the order is swapped anyway: `serveSession` now installs `setOnSendqOverflow` and `setOnWriteFailure` before `session.attachWriter(writer)`, which still runs under `capabilityControl().runUnderControl`. No other behaviour changes. No test was added: the old order cannot be observed deterministically without a seam, because no publication can reach the session in that window |
| M1 | A provider and a server extension enabled in one reload can claim the same command; the extension fails after the capability publication | Declined: no pre-start data exists | Server extensions declare no commands: `ServerExtension` has no command method, and they register imperatively inside `start` (`AdminExtension.java:44`). Validating before publication would need a declarative command SPI for server extensions, which is outside #97. The outcome is already isolated and reported: `register` refuses a provider-owned command (round 4), and `reconcileServerExtensions` records the extension's failure in the outcome (REHASH notice, and with M6 the SIGHUP log). Capabilities reconciling before server extensions is main's order (`ConfigurationReloader.reconcile` on `037f0c8`) |
| M2 | A provider command that core or a server extension owns failed inside `startForBatch` before `start`, whose rollback then ran `stop`, and the conflict was an isolated failure | Real | The ownership check moved into `validateCandidate`, so the conflict makes the candidate invalid (`InvalidDesiredStateException`, REHASH "Rehash failed") before anything starts. `startForBatch` now stops a provider only after its `start` ran. Nothing else can register a command while the lifecycle lock is held for the capability batch. `aProviderCommandThatCoreOwnsRejectsTheReloadBeforeAnythingStartsOrStops` failed before (no exception; `stop a` ran without `start a`) |
| M3 | `register` inserted into `byId` before `providedCapability()`; a throw left a selectable entry and a retry skipped recording the name | Real, and new in this PR: on main `register` never called `providedCapability()` | The name is resolved first. A failure becomes an `IllegalArgumentException` naming the provider, which discovery already skips, and nothing is inserted. `aProviderWhoseCapabilityNameThrowsIsNotRegisteredAndCanRegisterOnceFixed` failed before |
| M4 | A withdrawn provider whose first `stop` failed, desired disabled, was stopped twice in one reload | Real | The pending-cleanup retry skips providers this reload already stopped; the next disable or reload retries them. `aWithdrawnProviderWhoseStopFailsIsReportedAndStaysWithdrawn` now asserts one stop and failed before (`["stop a", "stop a"]`) |
| M5 | Disabling a prerequisite whose withdrawn dependent fails to stop reported success | Real | `cleanUp` returns the provider's own failure, or otherwise the first dependent's (wrapped with both ids), with any others suppressed. `EXTENSION DISABLE` therefore reports "Failed to toggle a: 'b' failed to stop after its prerequisite 'a' was withdrawn: …" while `a` is DISABLED and `b` FAILED. Only the explicit disable path has dependents here: reloads already report each withdrawn provider separately. The renamed `aDependentWhoseStopFailsWhenItsPrerequisiteIsDisabledEndsFailedAndIsReported` failed before (no throwable) |
| M6 | SIGHUP discarded `ReloadOutcome.failures()` and logged success | Real, and a regression in this PR: main logged each reconcile failure in `ConfigurationReloader`; this PR moved reporting into the outcome and only REHASH read it | `SighupReloadHandler.reload(reloader)` uses `reloadWithOutcome()`. It logs the success line only for a clean reload and one warning per extension otherwise. `SighupReloadOutcomeTest` (integration, with `LogCapture`) failed before |
| M7 | `MessageDelivery.deliver` read the snapshot and the sender's negotiated set separately | Real: an old snapshot paired with a newly pruned set is the mix the captured-work rule excludes | `deliver` takes both from one `capture(sender)` under the mutex. The same split read was in `MemberReplies.namesTokens` and `whoStatus` (the requester's NAMES/WHO rendering), now fixed the same way. `MessageDeliveryTest.theSnapshotAndTheSendersNegotiationAreCapturedTogetherUnderTheMutex` and `MemberReplyRenderingTest.namesAndWhoCaptureTheSnapshotAndTheRequestersNegotiationTogether` hold the mutex and require the call to block on it. Both failed before (the thread ran to completion). The delivery test gates every recipient out, so only the capture can wait: its first version passed on old code because the writer's own capture blocked |
| M8 | A provider advertising `cap-notify` is not rejected | Already prevented; no code change | `CapabilityRegistrar`'s constructor rejects `cap-notify` as a capability name or provider id (`CapabilityRegistrar.java:70`, on main since #104). Every published descriptor comes from `stage`, which builds that registrar, so such a provider can never enter a snapshot, be withdrawn, prune `cap-notify` or cause a `DEL`. New regression test `aProviderClaimingCoreCapNotifyIsNeverPublishedSoItsDisableCannotWithdrawIt` covers `EXTENSION ENABLE`, a reload, and a later disable: it passed on `6a970b7` unchanged. The provider ends FAILED, nothing is offered, and a session's negotiated `cap-notify` is untouched |

**Docs.** `server-configuration.md` ("Runtime capability changes") now lists a provider command already handled by core or a server extension as an invalid candidate that starts or stops nothing. It also says SIGHUP failures go to the server log, and that a disable reports a withdrawn dependent's failed stop. `ExtensionRegistry.applyDesiredState` and `MemberReplies` Javadoc match.

**Build:** `./gradlew spotlessApply` then `./gradlew build`: exit code 0. 1351 tests, 0 failed, 0 skipped; Spotless, SpotBugs, PMD and coverage verification green. Coverage: line 96.04% (6184 of 6439), branch 89.09% (2532 of 2842).
