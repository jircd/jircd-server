# PR 4 (#102) results (T064, T066)

Uncommitted diff on `014-capability-faults`, base main `3b7d66d` (#108). Review in [review.md](review.md), measurements in [performance.md](performance.md), irctest in [irctest.md](irctest.md).

## What changed

- **`CapabilityFaults`** (new, `jircd-core/.../extension`): the registry's `FaultReporter`. A fault by a published provider starts one incident. The FAILED withdrawal is handed to an executor, never the faulting thread; faults while the incident is pending schedule nothing more. Diagnostics, all under the `FaultReporter` logger:
  - one warning per failure transition: provider, hook kind, category, withdrawn dependents;
  - other fault reports and stop-failure warnings throttled per provider (10 s);
  - no exception text, stack or client content.
- **`ExtensionRegistry`:** `failProvider` runs under the lifecycle lock. It withdraws the provider and its hard dependents through `CapabilityRuntime.withdraw` (one swap, all-session prune, one `DEL`), then stops each, dependents first, and records `FAILED`; a stop that throws leaves the cleanup owed. Also new: `ExtensionRegistry(Executor)`, and `observeCapabilityPublications` for stage timings.
- **`CapabilityRuntime`:** publication split into wait / swap+prune / enqueue timings (`PublicationTiming`, reported after the critical section). Behavior is unchanged.
- **`FaultCategory.COMPOSITION_LIMIT`:** an aggregate tag overflow of valid contributions; `providerFault()` is false, so it is reported but withdraws no one.
- **`FaultReporter.logging()`** removed (replaced by `CapabilityFaults`).

## Tests first (T055, T056, T058)

| Class | Cases | Against a stub registry (constructor present, no withdrawal) | Final |
| --- | --- | --- | --- |
| `jircd-core` `CapabilityFailureIsolationTest` | 31 at first, 33 final | 31 of 31 failed (FAILED expected, ENABLED found; no `DEL`; diagnostics differ) | 33 pass |
| `CapabilityFailureIsolationTest` (sockets, every wire hook) | 8 | 8 failed | pass |
| `CapabilityReplyCompositionTest` | 7 | 1 failed for the missing behavior (an over-bound escaped value did not fail its owner). 1 failed only on my fixture's 353 parsing, since fixed; it pins existing behavior. 5 passed, pinning existing behavior | pass |
| `CapabilityDependencyTest` | 6 | 2 failed (fault cascade, late fault after re-enable); 4 passed, pinning existing behavior | pass |
| `CapabilityFailedReloadTest` (sockets, discovered probes; the user's reload decision) | 2 | 1 failed: an unrelated reload restarted the FAILED provider. The EXTENSION recovery case pins existing behavior | pass |
| `jircd-core` `CapabilityFaultsTest` (review round 2) | 2 | The stale-report interleaving failed (2 incidents scheduled, 1 expected); the round-1 replacement case passes | pass |

Later core additions to `CapabilityFailureIsolationTest`:
- **written after the implementation**, for coverage: rejected scheduling, publication timings;
- **tests first**, failing before their fix: the reload-retry rule, and review round 1's re-enabled-instance incident (A1);
- **corrected:** `CapabilityDependencyTest`'s cyclic-reload case, which had passed for the wrong reason (review.md, "Corrected test").

The core test covers each hook kind (event, inbound tags, recipients, outbound tags, member status, NAMES identity, output group, command) with an exception, a `null` and an illegal, unauthorized or out-of-bound result. For each one it asserts:
- the documented default;
- FAILED, with the snapshot, the prune and exactly one `DEL`;
- the unrelated provider still contributing;
- one sanitized diagnostic.

Further cases:
- dependents FAILED in one `DEL`, stopped dependents first;
- a stop failure with the cleanup retried later;
- no automatic recovery, and an explicit enable that restores no negotiation;
- a late fault after a re-enable;
- bounded late-fault logging;
- one scheduled withdrawal per incident, off the faulting thread;
- stop overlapping an in-flight callback without waiting;
- a valid composition overflow that fails no one;
- a pending withdrawal after a disable;
- a rejected scheduling;
- publication timings.

## Scoped runs

- `./gradlew :jircd-core:test --tests '…CapabilityFailureIsolationTest'`: 33 passed.
- The three socket classes: 21 passed, in 5 consecutive `--rerun`s before the S2925 change and 5 after.
- `./gradlew test`: every module passed after the implementation. The one regression it showed was `CommittedEventTest`, updated as described in review.md.
- Load classes: see performance.md.

## FR / SC traceability (final artifacts)

| Requirement | Evidence after #102 |
| --- | --- |
| FR-001 / SC-001 | Unchanged from #101/#97: six modules own behavior, provider absence removes it (`CapabilityProviderAbsenceTest`, unchanged). No capability-name branch was added to core; the new code keys on provider id, hook kind and category only |
| FR-002 | Event, inbound, recipient, tag, status, identity, group and command hooks all exercised with faults over sockets. Future seams mapped in README ("Planned capabilities") |
| FR-003 | A withdrawn provider's offer and behavior go together: after a fault, `CAP LIST` excludes it, its tag/status/identity/group/command stop, and its command answers `421` |
| FR-004 | Existing integration suite unchanged and passing; irctest comparison in irctest.md |
| FR-005 | Fault withdrawal reuses #97's single publication: snapshot swap and all-session prune under the mutex, then `DEL` (core and socket tests). Lifecycle load: after every change no session holds an unoffered name, and captures agree with their snapshot |
| FR-006 | Runtime hook faults end FAILED through the same lifecycle as `EXTENSION`/`REHASH`. Repeated faults by one instance are one incident; a re-enabled instance gets its own (`CapabilityFaultsTest`). Invalid graphs, including a real cycle, and missing prerequisites change nothing (`CapabilityDependencyTest`). A reload retries a FAILED provider only when its own entry changed; `EXTENSION ENABLE` always retries (`CapabilityFailedReloadTest`) |
| FR-007 | Fault `DEL` goes to 302/cap-notify clients only: a legacy client sees none (socket test). Dependents share the one `DEL` line (`CAP alice DEL :base dependent grand`) |
| FR-008 / FR-009 | Unchanged from #97 |
| FR-010 | No negotiation restored on re-enable after FAILED; dependents need their own explicit enable; batch's message-tags prerequisite stays soft (socket tests) |
| FR-011 | Stop runs while a captured callback is still in flight, without waiting (core test); a provider's own lock keeps its captured callback safe (socket test); old captured work may finish after `DEL` (no cutoff asserted anywhere) |
| FR-012 / SC-003 | Every hook kind × exception/null/invalid. No rollback of committed AWAY/JOIN/NICK/QUIT (`CommittedEventTest`, socket AWAY case). Cleanup continues after a stop failure. Diagnostics bounded per incident and sanitized (marker string never logged) |
| FR-013 | Composed NAMES/WHO/366 at the exact budget; escaped UTF-8 tag origins; an over-bound escaped value fails its owner; impossible bounds rejected at enable, reload and startup; full-group SendQ (`CapabilityReplyCompositionTest`) |
| FR-014 / SC-005 | `CapabilityFanoutLoadTest` and `CapabilityLifecycleLoadTest` (tag `load`). On AC (series 4): the lifecycle budget is met (wait + swap/prune p99 1.617 ms against 100 ms), delivery is under 1 s, and the JOIN-storm budgets hold. The fan-out findings past 10% (throughput −20.5% for 100-recipient AWAY and −16.0% for 100-recipient groups, plus p99/CPU increases) were accepted by the user: each is within about 1.4 A/A noise units (performance.md, "Decision"). Five pairs cannot rule out a difference below about 30–40% in the noisiest phases; [#111](https://github.com/jircd/jircd-server/issues/111) tracks reducing that noise. Series 1–3 ran on battery and are not decision-grade |
| FR-015 | Public API changes listed in README's migration table |
| FR-016 / SC-006 | Canonical contracts, data model, README and Javadoc updated (review.md "Docs and contracts") |
| SC-002 | The #97 matrix, unchanged and passing. Fault withdrawal over sockets for implicit CAP 302 clients (get `DEL`) and legacy clients without notifications (get none). The explicit legacy cap-notify class shares the same notification path and is covered by #97's matrix |
| SC-004 | irctest.md |

**Future-feature exclusions** (unchanged): account state and `ACCOUNT_CHANGED`, history storage, label wire rules, `userhost-in-names` policy, dynamic unload and sandboxing. Also not built: forced termination of a hung hook, and callback lifetime tracking. The contracts rule both out.

**Risks**:
- A late fault from work captured before a re-enable withdraws the re-enabled provider. This is by design and tested.
- A reload retries a FAILED provider only when its own entry changed (review.md decision 5, decided by the user). `CapabilityFailedReloadTest` and a core test cover it.
- The fan-out test cannot resolve differences below about 30–40% in its noisiest phases, so a smaller real regression there may be hidden (performance.md, "Decision"; follow-up [#111](https://github.com/jircd/jircd-server/issues/111)).

## Build (T066)

After the Sourcery round on PR #112 (review.md, "Sourcery round"): `./gradlew build` exit 0, 1,416 tests, 0 failed; coverage line **96.13%** (6,315 of 6,569), branch **89.39%** (2,587 of 2,894); SonarCloud gate OK with 0 open issues. The build recorded below is the one before that round.

Final build, after both review rounds and the series-4 loads: `./gradlew spotlessApply`, then `./gradlew build --rerun-tasks`, exit code 0, `BUILD SUCCESSFUL in 1m 34s`.
- 1,411 tests, 0 failed, 0 skipped.
- Spotless, SpotBugs, PMD and coverage verification green.
- Aggregate coverage: line **96.11%** (6,308 of 6,563), branch **89.31%** (2,581 of 2,890). Both are at or above #108 (96.04% / 89.09%).

The build before it failed once: `CapabilityDependencyTest.aPrerequisitesFaultWithdraws…` got no `DEL`. The cause was in the test (review.md, "After round 2"); it was fixed, and the four affected socket classes then passed 5 consecutive `--rerun`s before this build.

irctest was identical case by case ([irctest.md](irctest.md)). Load evidence on AC is in [performance.md](performance.md). No test server, Gradle test worker or pytest process was left running.

**Checkpoint:**
- **Pass:** FAILED withdrawal and bounded diagnostics, the lifecycle budget, and irctest with no change.
- **SC-005:** met with the user's acceptance of the series-4 fan-out regressions past 10%, justified by the A/A control (performance.md, "Decision"; review.md, decision 1). The follow-up is [#111](https://github.com/jircd/jircd-server/issues/111). T055–T066 are all checked.
