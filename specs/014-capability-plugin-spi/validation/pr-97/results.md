# PR 3 (#97) results (T052, T054)

Recorded on the uncommitted working tree of branch `014-capability-lifecycle` (base `193752d`, main after #106), JDK Temurin 25.0.4+7, Gradle 9.8.1, Apple M4 Pro. The review self-check is in [review.md](review.md).

## Design decision taken during implementation

The contracts call for "the existing administrative control mutex" to cover snapshot publication, the all-session prune, CAP state and `ConnectionHandler.liveSessions`. No such mutex existed: `ExtensionRegistry.lifecycle` serializes enable/disable but is held across provider `start`/`register`/`stop`, `ConfigurationReloader` holds its monitor across file I/O, and `liveSessions` and CAP REQ took no lock at all. I stopped and asked, and the user chose a two-level design:

- `ExtensionRegistry.lifecycle` stays the outer serializer of whole transitions. Provider callbacks run under it and nothing else.
- `CapabilityRuntime` owns a short inner monitor, the administrative mutex. It is taken for publish + prune + DEL/NEW enqueue, CAP LS/LIST/REQ, `liveSessions` add/remove/writer attach, the coherent capture of snapshot + negotiation, and a group's admission choice.
- Lock order: lifecycle lock, then the administrative mutex, then at most one session writer's queue lock. No provider callback, socket I/O or channel monitor is held under the mutex.

Code outside core gets `CapabilityControl` (the mutex without publication methods), so `ExtensionRegistry` still exposes no `CapabilityRuntime` (an existing test guards this).

## Tests first: baseline failures before the change (T042–T045)

The new and updated integration tests compile against the old API. They ran on the unchanged tree before any production edit: `20 tests completed, 11 failed`.

| Test | Baseline result | Why (#97) |
| --- | --- | --- |
| `Story2CapabilityListTest` (exact LS) | fail | no `cap-notify` in LS |
| `CapabilityProviderAbsenceTest` full catalog, each-missing, all-missing | 3 fail | no `cap-notify`; all-missing LS was empty |
| `CapabilityLifecycleTest` matrix {EXTENSION, REHASH} × {multi-prefix, message-tags} | 4 fail | LS lacked `cap-notify` for the explicit legacy client |
| `CapabilityLifecycleTest.aRepeatedToggleSendsNoDuplicateNotification` | fail | no `DEL` at all |
| `CapabilityLifecycleTest.anInvalidReloadChangesNoStateAndSendsNothing` | fail | (assertion on single-token LIST format, since corrected; see below) |
| `CapabilityGroupLifecycleTest.afterMessageTagsIsDisabledANewGroupIsPlain...` | fail | still framed after disable: message-tags never pruned |
| the other group / absence tests | pass | retained behavior |

The core and protocol tests (`CapabilityLifecycleNegotiationTest`, `CapabilityRuntimeTest`, `CapabilityNegotiationGrammarTest`) use new API (`notificationEligible`, `CapabilityControl`, `lsReplies`, …) and could not compile before the change, so their "before" is a compile failure, not a red run.

The serializer omits the trailing `:` on a one-token list (`CAP * LIST multi-prefix`). That is existing wire behavior and was left alone; my new tests' single-token expectations were corrected to it.

## Socket matrix, negotiation and group tests (T052)

All pass in the final build:

| Suite | Tests | Covers |
| --- | --- | --- |
| `CapabilityLifecycleTest` | 6 | {EXTENSION, REHASH} × {multi-prefix, message-tags}, each run with an explicit legacy cap-notify client, an implicit `CAP LS 302` client, a legacy no-notify client and an unnamed (`*`) never-held observer: exact DEL/NEW lines and targets, none for legacy, LS keeps `cap-notify` and drops the capability, LIST pruned, NAMES/msgid behavior off for commands sent after the change, `-name` on the pruned name ACKs, positive REQ NAKs while disabled, NEW restores nothing, behavior returns only after a new REQ. Repeated toggles through both paths send no duplicate; an invalid REHASH changes nothing and sends nothing |
| `CapabilityGroupLifecycleTest` | 3 | an admitted group keeps its close across a disable; after message-tags is disabled a new group is plain while `batch` stays in LIST; re-enable restores nothing until re-request; an overflowing recipient is closed alone |
| `CapabilityProviderAbsenceTest` | 10 | as before, plus `cap-notify` retained with any provider missing and with all six missing, where `CAP REQ :cap-notify` ACKs and LIST shows it |
| `CapabilityLifecycleNegotiationTest` (core) | 20 | LS with no providers; sorted offers; explicit vs implicit cap-notify in LIST; `-cap-notify` NAK after 302 (also when explicitly held), ACK for legacy; sticky version across lower/omitted/0/malformed/negative; huge version saturates; held-but-unoffered removal; pruned known removal; unknown and bare `-` NAK; unavailable addition atomic NAK; repeated/conflicting tokens in order with exact echo; idempotent re-request; empty NAK; oversize 417 without mutation; DEL targets (`*`/nick) to explicit, implicit and holder, none to legacy; NEW without restoration; no notification for no-op toggles or session-only REQ |
| `CapabilityRuntimeTest` (core) | 11 | every capture under the mutex sees old state or new snapshot with all 200 sessions pruned; 200 REQ-vs-disable races never leave the name negotiated; publication blocks while CAP/session tracking holds the mutex (bounded); an old captured view still frames after DEL while the next capture is current; a group still preparing when `tags` is disabled is admitted plain (latch-held hook); an admitted group on a stalled socket keeps its close; overflow while queuing DEL closes only that recipient; a notification that cannot be built for one recipient does not stop the others; hard dependents withdrawn in the same publication and not restored by re-enabling the prerequisite; missing prerequisite rejected; start/stop see the mutex free |
| `CapabilityNegotiationGrammarTest` (protocol) | 11 | version parsing and saturation; NEW/DEL dedup, sort, token-boundary split; LS/LIST 302 continuation; legacy one-line rejection |
| `ConfigurationReloaderLifecycleTest` (core) | 5 | prerequisites enabled before dependents; dependency-invalid reload changes nothing; omitted-but-enabled prerequisite counts; start failure reported, rest applied; reload disable cascades |
| `LifecycleOutcomeReportingTest` (admin) | 2 | REHASH reports a provider that failed to start; EXTENSION reports the state actually left (`failed` after a no-op disable of a FAILED provider, where the old fixed text said "disabled") |

**Quickstart admin walkthrough.** Its steps (administrator plus the three client classes; disable and enable through EXTENSION and through test-owned YAML + REHASH; exact DEL/NEW/LS/LIST; pruned removal; concurrent CAP and session insertion against one prune; old captured output after DEL) are what `CapabilityLifecycleTest` and `CapabilityRuntimeTest` drive over the real `JircdServerApplication` and real sockets. I did not run a separate hand-driven session against a started binary: that would need a local config with credentials, and `jircd-server/jircd.yaml` is off limits.

## Publication timing at 1,000 live sessions (T052)

`CapabilityPublicationTimingLoadTest` (tag `load`, run by `./gradlew loadTest`) opens 1,000 real socket sessions that all hold `multi-prefix`, then does 5 warm-up and 40 measured disable/enable cycles, re-requesting on every client between cycles so every disable prunes all 1,000. Pass 1 has no notification-eligible client (wait + publish + prune). Pass 2 sends `CAP LS 302` from every client first (plus DEL/NEW enqueue to 1,000 writers). Each figure is the whole `ExtensionRegistry.disable`/`enable` call, which also includes the provider's own stop/start and command removal, so it bounds the publication step from above. With 40 samples, p99 equals the maximum.

| Run | no-notify disable p50 / p99 (ms) | no-notify enable p50 / p99 | notify-302 disable p50 / p99 | notify-302 enable p50 / p99 |
| --- | --- | --- | --- | --- |
| 1 | 0.306 / 0.526 | 0.150 / 0.305 | 1.104 / 2.682 | 0.986 / 3.452 |
| 2 | 0.328 / 0.567 | 0.152 / 0.340 | 1.170 / 2.766 | 1.058 / 2.829 |
| 3 | 0.336 / 0.617 | 0.159 / 1.001 | 1.108 / 1.972 | 1.035 / 3.542 |
| loadTest 1 | 0.414 / 0.688 | 0.172 / 0.403 | 1.101 / 2.407 | 0.935 / 2.246 |
| loadTest 2 | 0.304 / 1.114 | 0.146 / 0.979 | 1.075 / 2.843 | 0.998 / 2.508 |

Budget: administrative-mutex wait + snapshot publication/all-session prune p99 ≤ 100 ms. Measured upper bound: ≤ 1.2 ms. Notification enqueue to 1,000 eligible writers adds roughly 0.8 ms at p50. Socket drain is not part of any figure: nothing waits for it. The test asserts the no-notify disable p99 ≤ 100 ms. The dedicated final SC-005 load classes and JFR comparison are #102's.

The first version of this test timed out at cycle 10. An in-process virtual-thread dump showed no blocked thread. The per-connection rate limit (20-token bucket) silently drops over-rate commands, so the barrier PING was never answered. The fixture now raises `rateLimit` for this test only. Not a server defect.

## Applicable existing loads

Tag preparation now captures snapshot + negotiation under the mutex once per recipient, so the delivery loads apply. `./gradlew loadTest` ran twice on this tree; all 9 load tests passed both times. The comparison is with the #101 tree, which is what main `193752d` merged ([pr-101/results.md](../pr-101/results.md)). Per the instruction to stay in this worktree, I did not run a fresh main baseline.

| Load test | #101 tree (run 1 / run 2) | this tree (run 1 / run 2) |
| --- | --- | --- |
| ConcurrentConnectionScale p50 / p99 (ms) | 0.902 / 1.641; 1.053 / 1.499 | 0.890 / 1.694; 0.839 / 1.614 |
| ExceptionModes join; message p50 (ms) | 2.247, 0.251 / 0.682, 0.260 | 0.451, 0.124 / 0.479, 0.151 |
| JoinStorm (2000) storm ms; quiet p50 / p99 (ms) | 2208; 26.5 / 156.5 · 2244; 31.5 / 171.6 | 2481; 34.1 / 220.6 · 2328; 30.1 / 63.4 |
| SingleWaveJoinStorm ms; overflow disconnects | 1956; 0 · 1942; 0 | 1968; 0 · 1923; 0 |
| Story1DeliveryLatency (50) p50 / p99 (ms) | 0.554 / 2.300; 0.578 / 2.397 | 0.559 / 0.912; 0.603 / 1.205 |

**Reading:** no regression visible at this resolution. Every figure is within run-to-run noise of #101 and far inside its budget (delivery < 1 s, JOIN-storm quiet p99 < 500 ms, storm < 15 s). The JoinStorm quiet p99 is a maximum over about 20 samples, and its two runs here (220.6 and 63.4 ms) bracket #101's. This is not the controlled ≥5-run comparison with CPU/allocation profiles, which belongs to #102.

## Final build (T054)

| Command | Outcome |
| --- | --- |
| `./gradlew spotlessApply` | applied, then clean |
| `./gradlew build --rerun-tasks` | **BUILD SUCCESSFUL** (1 m 32 s, after review round 4): 1340 tests in the module XML reports, 0 failed, 0 skipped; Spotless, SpotBugs (main and test), PMD (main and test) and `testCodeCoverageVerification` all green |

Earlier builds that failed were fixed, not retried: PMD `CloseResource` on a session writer read for one append in `CapabilityRuntime` and on test writers or clients closed elsewhere (suppressed with the reason at each declaration); SpotBugs `USO_UNSAFE_OBJECT_SYNCHRONIZATION` on a test's `synchronized (ByteArrayOutputStream)` (dropped, since the stream synchronizes itself).

| Module | Tests |
| --- | --- |
| jircd-protocol | 69 |
| jircd-core | 873 |
| jircd-capabilities: away-notify / batch / echo-message / message-tags / multi-prefix / server-time | 7 / 11 / 13 / 13 / 5 / 6 |
| jircd-server-extensions:admin | 22 |
| jircd-integration-tests | 321 |

### Coverage (aggregate over every module with `src/main/java`)

Report: `build/reports/jacoco/testCodeCoverageReport/`.

| | Covered | Floor | After #101 | Result |
| --- | --- | --- | --- | --- |
| Line | **96.03%** (6165 of 6420) | 85% | 95.72% | pass, higher |
| Branch | **89.11%** (2520 of 2828) | 75% | 88.70% | pass, higher |

An intermediate build measured 95.69% lines, below #101's figure. The new uncovered lines were the REHASH failure report and the per-recipient notification guard. Tests were added for both (`LifecycleOutcomeReportingTest`, `CapabilityRuntimeTest`), and `RuntimeSnapshot.without`, which no production code used any more, was removed.

## Checkpoint

Both admin paths, atomic snapshot/prune, DEL/NEW to exactly the eligible clients, negative REQ, core advertised cap-notify and the retained batch rules (admitted groups close, unadmitted groups plain once a prerequisite is ineffective, batch stays negotiated) pass.

Not done here:
- SonarCloud branch analysis and the other-vendor review: see [review.md](review.md).
- irctest: not run, since it runs only on request.
- Commit: the work is uncommitted, as instructed.
