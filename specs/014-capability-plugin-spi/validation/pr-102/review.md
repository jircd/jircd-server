# PR 4 (#102) review (T065)

Self-check of the uncommitted diff on `014-capability-faults` (base `3b7d66d`) against [`.github/REVIEW_CHECKLIST.md`](../../../../.github/REVIEW_CHECKLIST.md) and the 014 contracts. Results are in [results.md](results.md), measurements in [performance.md](performance.md).

## Scope

- **SonarCloud branch analysis and the other-vendor (Codex) review:** run in the review rounds below. Every finding is resolved there.
- **No commit, push or PR.**

## Decisions

1. **Decided: the fan-out regressions past 10% are accepted** ([performance.md](performance.md), "Decision" and "Series 4 on AC power"). Five alternating pairs plus a warmup per revision, on AC throughout, with the current fan-out class on both revisions.
   - **Throughput regressions past 10%:** 100-recipient AWAY −20.5% and 100-recipient groups −16.0%.
   - **Other increases past 10%:** 100-recipient AWAY p99 +27.3%; 100-recipient group p99 +36.9% and CPU +18.0%; 1,000-recipient NAMES/WHO p99 +25.9%; 10-recipient AWAY CPU +11.7%.
   - **The 1,000-recipient message finding from the battery runs does not reproduce:** +3.2% throughput, −5.9% CPU.
   - These phases spread widely run to run on both revisions; the run tables are in performance.md.
   - No cause is claimed. The per-recipient cost review found nothing this PR adds to the success path.
   - **Accepted by the user (2026-10-27)** as the recorded justification the spec requires, on three grounds:
     - every flagged A/B difference is within about 1.4 noise units of the A/A control (performance.md, "A/A control");
     - this PR adds no per-recipient work on the successful hook path;
     - the regressed phase moved between series: the 1,000-recipient message phase on battery, the 100-recipient phases on AC.
   - **Limit:** five pairs cannot rule out a real difference below about 30–40% in the 100-recipient AWAY and group phases and the 1,000-recipient NAMES/WHO phase. The follow-up to reduce the test's noise is [#111](https://github.com/jircd/jircd-server/issues/111).

   Series 1–3 ran on battery and are kept only as not-decision-grade raw data.
2. **Fault withdrawal runs off the faulting thread.**
   - The production `ExtensionRegistry()` hands each incident to a new virtual thread, which takes the lifecycle lock.
   - The faulting call has already used its default by then.
   - Until that thread publishes, further faults from that provider schedule nothing and are logged, throttled.
   - A delivery thread therefore never blocks behind a reload, and never runs a provider's `stop`.
   - The contracts don't say which thread withdraws. Tests use `new ExtensionRegistry(Runnable::run)` for determinism.
3. **Decided: an aggregate tag overflow from valid contributions fails no provider.** The user kept this behavior. Each contribution was within its own bound; only together do they exceed one origin's 4,094 bytes. Per reply-budgets.md ("valid bounded data hitting a documented size limit uses the declared fallback … without faulting"), the later contribution is dropped and a `COMPOSITION_LIMIT` diagnostic is logged. `server-configuration.md` ("Runtime capability faults") states it.
4. **Dependents of an administratively disabled prerequisite still end `DISABLED`** (#97 open point 3, unchanged). Dependents withdrawn because their prerequisite *faulted* end `FAILED`, as T057 says.
5. **Decided and changed: a reload retries a FAILED provider only when its own entry changed.**
   - **Rule:** `REHASH`/`SIGHUP` compare each `capabilities` entry with the configuration last applied (`ConfigurationReloader.current`). A changed value, or an added or removed entry, is an explicit retry. Anything else leaves a FAILED provider FAILED, even if the file still says `enabled`. `EXTENSION ENABLE` always retries.
   - **Scope:** hard dependents follow the same rule by their own entries. It also covers a provider whose start failed: that is the same FAILED state, and the rule makes no exception. Capability entries hold only `enabled`/`disabled`, so "any of its settings" has nothing more to compare today.
   - **Spec check:** this matches the explicit-recovery wording. 014 data-model.md says "explicit administrative enable is required for recovery". runtime-lifecycle.md says an explicit desired enable, "including a later valid REHASH, *may* retry them". The rule narrows that "may" to a changed entry, without conflict.
   - **Edge:** a dependent whose entry changed while its prerequisite is still FAILED makes the reload invalid (missing prerequisite), so nothing changes.
   - **Implementation:** `ExtensionRegistry.DesiredState.changedCapabilities`, computed by `ConfigurationReloader.changedEntries`. The previous six-argument constructor means "every listed entry changed", for a first application. `ExtensionRegistry.isUnrequestedRetry` skips the rest.
   - **Tests, written first:**
     - `CapabilityFailedReloadTest`, over sockets with discovered probes. It failed on the old behavior: an unrelated reload restarted the provider. It covers unrelated SIGHUP and REHASH, a changed entry, the dependent's own entry, and EXTENSION ENABLE.
     - The core test `aReloadRetriesAFailedProviderOrDependentOnlyWhenItsOwnEntryChanged`.
6. **REPLY_FIELD has no socket test.** No reply schema declares an extension slot yet, so no provider can stage one through the registry. Its dispatcher containment is covered by #100's `CapabilityDispatchTest`, and the report → FAILED path is the same for every hook kind.

## Corrected test

`CapabilityDependencyTest.aReloadWithACyclicGraphIsRejectedAndChangesNothing` passed for the wrong reason. Its providers were registered after startup, so the configuration loader didn't know their ids, and `REHASH` was rejected as an unknown id rather than for the cycle. The test only checked "Rehash failed". It now uses providers discovered at startup (`DiscoveredProbes`, a test helper that serves a temporary `META-INF/services` file through the context class loader) and asserts "cyclic provider dependency".

## Integration-test edits

No existing integration test file changed (`git diff HEAD -- jircd-integration-tests` is empty). New files only:
- `CapabilityFailureIsolationTest`, `CapabilityReplyCompositionTest`, `CapabilityDependencyTest`;
- `CapabilityFailedReloadTest`;
- `Withdrawals` and `DiscoveredProbes` (helpers);
- `CapabilityFanoutLoadTest` and `CapabilityLifecycleLoadTest` (tag `load`).

One existing core unit test encoded the #100 rule that a faulting listener stays published:
- `CommittedEventTest.committedStateStandsAndLaterEventsStillFlowAfterAListenerFault` asserted the faulty listener was called for all five events.
- It now asserts the committed state, the listener withdrawn as FAILED after its first call, and the healthy listener receiving every event.
- Its registry runs withdrawals on the calling thread, so the outcome is deterministic.

## Checklist

| Item | Finding / resolution |
| --- | --- |
| S7467 / S1181 | Catches are `RuntimeException \| LinkageError`, never `Throwable`/`Error`; unused parameters are `_` |
| S3077 | New mutable shared state is `AtomicReference` (`timings`) or a `ConcurrentHashMap` (`pending`); no `volatile` of a non-thread-safe type |
| S2445 | No new `synchronized`; the existing `control` monitor is a `private final Object` |
| S3776 | `failProvider` and `publishLocked` are short; the publication was split rather than grown |
| S2629 | Log calls use placeholders; the dependents list is formatted only in the once-per-incident warning |
| S2925 (tests) | The new fault tests wait on the provider's `stop` latch, then on a no-op `disable` that takes the lifecycle lock the withdrawal holds (`Withdrawals.awaitFailed`), with no sleep. The lifecycle load class waits for ACKs on a latch and paces its generators with `LockSupport.parkNanos`. **Left as is:** `CapabilityFanoutLoadTest.awaitCount` polls with `Thread.sleep(5)`, because the measured class must stay byte-identical to the copy run on the baseline; change it together with any re-measurement. `LogCapture.awaitMessage` (existing) is no longer used by the new tests |
| S5841 / S5853 | `isNotEmpty()` precedes `allSatisfy`; consecutive assertions on one subject are chained |
| PMD | `LooseCoupling` (queue fields typed as `Queue`) and `CloseResource` (a loop variable over clients) fixed; `./gradlew build` runs PMD and SpotBugs green |
| SpotBugs | `VA_FORMAT_STRING_USES_NEWLINE` in the lifecycle class's YAML builder fixed by building the YAML without `formatted` |
| Failure and cleanup paths | A stop that throws after a fault keeps the provider withdrawn and `FAILED` with `cleanupPending`, and the remaining stops still run (tested). A disable later retries it (tested). A rejected scheduling of the withdrawal is logged and frees the incident, so the next fault tries again (tested) |
| Concurrency and reload | Lock order is unchanged: lifecycle → administrative mutex → one writer lock. The withdrawal takes the lifecycle lock first, then `CapabilityRuntime.withdraw` takes the mutex for the swap, prune and `DEL`; `stop` runs under the lifecycle lock only. A provider disabled before its pending withdrawal runs is left `DISABLED` (tested). A fault from an old capture after a re-enable withdraws the new instance, by design (tested in core and over sockets) |
| Protocol size limits | Composed NAMES at exactly the budget (511 bytes; a lone token needs no `:`), WHO ≤ 512, the 366 exact, and the escaped tag origin bound counted with the serializer's escaping (all tested). An over-bound contribution falls back and fails its owner. A group larger than the SendQ closes only that recipient, with no partial group |
| Hostile / oversized input | A forged server tag in an inbound selection, an unauthorized event recipient, a status not held and an identity token with a space are each invalid and fail the provider (core test, every kind × exception/null/invalid) |
| Extension boundaries | Defaults on fault: no intents, no forwarded tags, no sender copy, no tag, highest status, nickname, plain group; a faulting command has no reply and is `421` afterwards. Committed AWAY/JOIN/NICK/QUIT state stands |
| Rejected requests change nothing | Enable over budget (`353=513`), a reload over budget, a cyclic graph reload and an enable missing its prerequisite publish nothing and send no `CAP` line (tested) |
| Security | Diagnostics carry provider id, hook kind, category, dependent ids and, for a stop failure, the exception's simple class name only: no message, no stack trace, no client content. Tests feed a marker string through exception messages, AWAY reasons and tag values and assert it never reaches a log |
| Determinism | `DEL` names sorted (existing); the tests compare tags and NAMES tokens as sorted sets where order is not part of the contract |
| Public API compatibility | Unreleased `0.1.0-SNAPSHOT`. README's migration table gains two rows: `FaultReporter.logging()` is removed (use the registry's reporter or your own), and an aggregate tag overflow is reported as `COMPOSITION_LIMIT`. Also new: `ExtensionRegistry(Executor)`, `ExtensionRegistry.observeCapabilityPublications`, `CapabilityRuntime.observePublications` / `PublicationTiming`, `FaultCategory.COMPOSITION_LIMIT` / `providerFault()` |
| Tests that pass while the code is wrong | All 31 core cases failed against a stub registry that ignored the executor; 12 of the 21 new socket tests failed on it. The 9 that passed pin behavior #102 must keep: rejection at enable, reload and startup, the escaped tag origin, the SendQ group, the batch soft prerequisite, the cyclic reload, the missing prerequisite and the provider-owned lock overlap (results.md) |
| Test reliability | Every wait is bounded. Readers drain to a PING barrier before the load assertions, and clients are closed in `try`/`finally`. Load classes are tagged `load`. The three new socket classes passed 5 consecutive `--rerun`s before the S2925 change and 5 after |
| Docs and contracts | `server-configuration.md` ("Runtime capability faults", "Lifecycle cost"), `irc-protocol-commands.md` (`CAP DEL` row, runtime faults note), `data-model.md` (FAILED lifecycle), README (SPI failure paragraph, future-capability seam map, status, migration rows), and Javadoc on `HookDispatcher`, `FaultReporter`, `FaultCategory`, `Extension.stop`, the capability package, `CapabilityFaults`, `ExtensionRegistry` and `CapabilityRuntime` |

## Review round 1

Every item was checked against the code before any change. All 14 are real; none was declined.

### Codex (cross-vendor)

| # | Finding | Verified | Change |
| --- | --- | --- | --- |
| A1 (P2) | A fault after a re-enable can be lost: the incident marker is removed after the lifecycle lock is released, so the re-enabled instance's fault meets the old marker | Real: the marker was keyed by provider id (`putIfAbsent`) and removed in the task's `finally`, after `failProvider` had unlocked. New test `aFaultByAReEnabledInstanceStartsItsOwnIncidentWhileTheOldOneIsStillOpen` forces the interleaving deterministically: a queue executor holds incident 1 open while the provider is disabled, re-enabled and faults again. It failed before (1 withdrawal scheduled, 2 expected) | Incidents are keyed by the provider *instance*: the descriptor published when the incident opened, compared by identity. A fault by a newer instance opens its own incident even while an older one is open, and an incident's end removes only its own marker (`ConcurrentHashMap.remove(id, incident)`), so the timing of the removal no longer matters. The old incident's withdrawal still withdraws the current instance, as the contract's late-fault rule says; the new one then finds nothing to withdraw (tested) |
| A2 (P2) | The lifecycle budget passes with no measurements | Real: `allMatch` passes on an empty queue and `p99Ms` returned 0 for no samples | The test now requires exactly `2 * CYCLES` publication timings (each covering 1,000 sessions), `2 *` the REHASH cycles and `2 *` the EXTENSION cycles of whole-change samples (20 and 60), and `CYCLES * 500` `DEL` receipts. `p99Ms` refuses an empty sample. These counts match every earlier run's printed output. **Compiled, not run** (no load runs until AC power) |
| A3 (P2) | Fan-out group verification accepts missing members | Real: a group counted as received when its last member arrived, and opens/closes were only totals | Each reader checks the exact sequence it receives. Every message and AWAY must arrive once (bitsets, duplicates are violations), and every group member must arrive in order. Members of a framed group must sit inside an opener and carry `@batch`, plain ones must have neither, and a closer must follow exactly three members; any other line inside a group is a violation. Phase waits use latches counted down by the readers, and each reader drains to a PING barrier before the totals are checked. The end-of-test cleanup check quits every client and reads to end of stream: the server releases a nickname before it closes the writer (`DisconnectCleanup.java:156`), so no polling is needed. **Compiled, not run** |

### SonarCloud branch scan

| Rule | Location | Change |
| --- | --- | --- |
| S8491 | `ConfigurationReloader` | Real, my mistake: `changedEntries` was inserted between `reloadSerialized`'s Javadoc and the method. Moved below it |
| S3416 | `CapabilityFaults` logger | Kept deliberately, with `// NOSONAR S3416` and the reason: the logger is named after the public `FaultReporter`, which operators configure for capability faults. It was the name before #102 too, and the class itself is package-private |
| S7467 | `CapabilityFaults` catch | `catch (RejectedExecutionException _)` |
| S1481 ×2 | `CapabilityFailedReloadTest` | The try resource is now the started server itself (`started`), assigned to the field in the body |
| S6204 | `CapabilityFailureIsolationTest` (sockets) | `Stream.toList()` |
| S6213 | `CapabilityFanoutLoadTest.record` | Gone with the A3 rewrite (`check`, `checkFrame`, `checkMember`) |
| S2925 ×2 | `CapabilityFanoutLoadTest` | Both sleeps replaced: phase latches, and quit-and-read-to-EOF for cleanup. No `Thread.sleep` is left in any file this PR adds |
| S5853 | `CapabilityLifecycleLoadTest` | One `allSatisfy` chain |
| S9358 | `CapabilityReplyCompositionTest` | The conditional moved inside `substring` |

**Fan-out class and the baseline.** The class changed, so the copy the init script injects into the baseline was replaced; both have sha256 `a54e02e3c88f7c79e2a2b76498ae3447a9fc3e3ee96dcffcc8cbdcca1878a6bf` (recorded in performance.md). The baseline compiles it, run with compile only (`:jircd-integration-tests:compileTestJava`); its worktree has no changes.

**Build after round 1:** `./gradlew spotlessApply` then `./gradlew build --rerun-tasks`: exit code 0. 1,409 tests, 0 failed, 0 skipped; Spotless, SpotBugs, PMD and coverage verification green. Coverage: line 96.11% (6,305 of 6,560), branch 89.30% (2,579 of 2,888), above #108 (96.04% / 89.09%). The first build of the round failed PMD's CloseResource on the new `quitAll` helper's loop variables. The clients belong to the caller, which closes them, so the helper takes the same suppression as the test method.

## Review round 2

Codex confirmed the round-1 fixes: the lifecycle sample counts and empty p99, missing group members, and the S3416 reason. It found no lock-order cycle and no persistent marker leak. Three P2s remained; each was checked against the code and all three are real.

| # | Finding | Verified | Change |
| --- | --- | --- | --- |
| 1 (P2) | An older report can replace a newer instance's incident marker | Real. The round-1 `compute` installed the reporter's incident whenever the existing marker was for another instance, including when the reporter's own instance was the stale one. New test `CapabilityFaultsTest.aReportForAnInstanceNoLongerPublishedNeverReplacesTheCurrentInstancesIncident` forces the interleaving deterministically: report A looks up instance A, and that lookup itself publishes B and runs B's report to completion before A reaches its `compute`. It failed before: 2 incidents scheduled where only B's should be | A report may install or replace a marker only while its instance is still the published one (rechecked inside the `compute`). A stale report changes nothing and is only logged, throttled. A current-instance fault still replaces an incident an older instance left open (round 1's A1), pinned by `aFaultByTheCurrentInstanceReplacesAnIncidentAnOlderInstanceLeftOpen` |
| 2 (P2) | Reordered messages and AWAYs pass the fan-out checks | Real: the bitsets caught duplicates and gaps, not order | Each reader checks every message and AWAY sequence number against the next one expected, as it already did for group members. Out of order is a violation. The final check also requires the next expected message to equal the total. **Compiled, not run** |
| 3 (P2) | The QUIT cleanup neither requires EOF nor enforces a total deadline | Real: `readLinesFor` returns quietly on a socket timeout, and every client got its own 60 s | Every client must reach end of stream before one shared deadline. The test waits through `RawIrcClient.readUntil` for a marker the server never sends; only its end-of-stream result (`"Connection closed …"`) is accepted. A timeout, an exhausted deadline or any other I/O failure fails the test. `RawIrcClient` is unchanged. **Compiled, not run** |

**Fan-out class and the baseline.** The injected baseline copy was replaced again; both have sha256 `4d4a7e5d358b438925d288fc30fced6a5c9c2f9b208b6e9cfd21705d71f6f1b8` (performance.md). The baseline compiles it (compile only), and its worktree has no changes.

**Build after round 2:** `./gradlew spotlessApply` then `./gradlew build --rerun-tasks`: exit code 0. 1,411 tests, 0 failed, 0 skipped; Spotless, SpotBugs, PMD and coverage verification green. Coverage: line 96.11% (6,308 of 6,563), branch 89.31% (2,581 of 2,890), above #108 (96.04% / 89.09%).

## After round 2: a test race found by the final build

A `./gradlew build --rerun-tasks` after the series-4 loads failed once, in `CapabilityDependencyTest.aPrerequisitesFaultWithdrawsItsDependentsAsFailedInOneDelAndOnlyExplicitEnablesRestore`: alice's CAP lines were empty.
- **Cause, in the test:** the fault happens while bob's message is prepared for alice. The withdrawal runs on its own thread, so alice's `DEL` can be queued before that message. The contract allows that order, since no `DEL` is ordered against captured work.
- **The bug:** `readUntil("PRIVMSG alice")` read and threw away a `DEL` that came first. `CapabilityFailedReloadTest.failBoth` and `CapabilityReplyCompositionTest`'s out-of-bound case had the same pattern.
- **Fix:** all three now keep every line read up to the message (`Withdrawals.linesThrough`) and assert on the `DEL` among all of them.
- **Checks:** the four fault socket classes passed 5 consecutive `--rerun`s, then the final `./gradlew build --rerun-tasks` passed (exit 0; 1,411 tests, 0 failed; coverage 96.11% / 89.31%).

No production code changed.
