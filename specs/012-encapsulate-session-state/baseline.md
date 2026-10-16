# Feature 012 baseline

Date: 2026-10-16

Commit: `6088e519700ad2f23665c84f007383baf615e0ef` (unchanged production code).

## T001: full build

`./gradlew build`: PASS, exit code 0.

## T003: accessor mutations (SC-003)

Command from quickstart §2:

```sh
rg -n --glob '**/src/main/**' '\.(members|operators|voiced|activeModes|bans|invited|channelMemberships|userModes)\(\)\.(add|remove|clear|removeIf|addAll|retainAll)' .
```

14 sites:

```text
./jircd-core/src/main/java/net/jircd/core/session/command/KickCommandHandler.java:115:    target.get().channelMemberships().remove(channel);
./jircd-core/src/main/java/net/jircd/core/session/command/UserModeCommandHandler.java:95:        session.userModes().add(mode);
./jircd-core/src/main/java/net/jircd/core/session/command/UserModeCommandHandler.java:101:          session.userModes().remove(mode);
./jircd-core/src/main/java/net/jircd/core/session/DisconnectCleanup.java:72:      session.channelMemberships().remove(channel);
./jircd-core/src/main/java/net/jircd/core/session/command/PartCommandHandler.java:87:    session.channelMemberships().remove(channel);
./jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java:170:      channel.invited().remove(CaseMapping.fold(session.nickname()));
./jircd-core/src/main/java/net/jircd/core/session/command/InviteCommandHandler.java:128:      channel.invited().add(CaseMapping.fold(target.get().nickname()));
./jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java:270:        channel.activeModes().remove(CoreChannelModes.SECRET);
./jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java:272:        channel.activeModes().remove(CoreChannelModes.PRIVATE);
./jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java:274:      channel.activeModes().add(mode);
./jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java:276:      channel.activeModes().remove(mode);
./jircd-core/src/main/java/net/jircd/core/session/ChannelRegistry.java:58:    session.channelMemberships().add(channel);
./jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/SamodeCommandHandler.java:80:      channel.operators().add(session);
./jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/SamodeCommandHandler.java:83:      channel.operators().remove(session);
```

## FR citations in plan Source Code files (SC-006)

Counts are occurrences of `\bFR-\d+` (not lines). New files absent at baseline count as 0.

| File | Count |
|---|---:|
| `jircd-core/src/main/java/net/jircd/core/session/ChannelRegistry.java` | 5 |
| `jircd-core/src/main/java/net/jircd/core/session/Channel.java` | 9 |
| `jircd-core/src/main/java/net/jircd/core/session/ClientSession.java` | 7 |
| `jircd-core/src/main/java/net/jircd/core/session/Recipients.java` | 0 |
| `jircd-core/src/main/java/net/jircd/core/session/JoinResult.java` | 0 |
| `jircd-core/src/main/java/net/jircd/core/session/Departure.java` | 0 |
| `jircd-core/src/main/java/net/jircd/core/session/DisconnectCleanup.java` | 2 |
| `jircd-core/src/main/java/net/jircd/core/session/command/Replies.java` | 1 |
| `jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java` | 12 |
| `jircd-core/src/main/java/net/jircd/core/session/command/NamesCommandHandler.java` | 3 |
| `jircd-core/src/main/java/net/jircd/core/session/command/PartCommandHandler.java` | 0 |
| `jircd-core/src/main/java/net/jircd/core/session/command/KickCommandHandler.java` | 5 |
| `jircd-core/src/main/java/net/jircd/core/session/command/NickCommandHandler.java` | 5 |
| `jircd-core/src/main/java/net/jircd/core/session/command/AwayCommandHandler.java` | 7 |
| `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java` | 13 |
| `jircd-core/src/main/java/net/jircd/core/session/command/TopicCommandHandler.java` | 7 |
| `jircd-core/src/main/java/net/jircd/core/session/command/InviteCommandHandler.java` | 3 |
| `jircd-core/src/main/java/net/jircd/core/session/command/UserModeCommandHandler.java` | 1 |
| `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/SajoinCommandHandler.java` | 1 |
| `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/SamodeCommandHandler.java` | 2 |
| `jircd-core/src/test/java/net/jircd/core/session/ChannelRegistryTest.java` | 0 |
| `jircd-core/src/test/java/net/jircd/core/session/ChannelTest.java` | 0 |
| `jircd-core/src/test/java/net/jircd/core/session/RecipientsTest.java` | 0 |
| `jircd-core/src/test/java/net/jircd/core/session/MembershipStressTest.java` | 0 |
| `jircd-core/src/test/java/net/jircd/core/session/command/RepliesListedTest.java` | 0 |

## Approved plan corrections

- T006: retain shared #a/#b; add A-only #solo for the empty-channel removal assertion.
- T002: existing load tests report no measured latency/throughput; record outcomes, assertion bounds, workload and Gradle durations instead. These durations are not delivery-latency measurements and are recorded for information only. SC-005 requires all load tests to pass their asserted bounds.

## T002: unchanged load-test baseline

`./gradlew loadTest`: PASS, exit code 0; Gradle total: 32s.

No test emits measured latency or throughput. Durations below are JUnit/Gradle whole-test durations, including setup, pacing and teardown; they are not per-message latency.

| Test | Outcome | Duration (s) | Workload / assertion |
|---|---|---:|---|
| `Story1DeliveryLatencyLoadTest.deliveryLatencyStaysWithinOneSecondBudget()` | PASS | 5.721 | 50 messages, paced 110ms; each delivery <1s |
| `ConcurrentConnectionScaleLoadTest.deliveryLatencyStaysWithinBudgetAtOneThousandConnections()` | PASS | 2.809 | 1,000 connections; 20 messages, paced 110ms; each delivery <1s |
| `KeepAliveLoadTest.idleConnectionIsProbedThenDisconnectedIfNeverAnswered()` | PASS | 15.041 | 2s configured idle interval; probe within 10s; unanswered probe closes within 20s read window; answered probe permits JOIN |
| `KeepAliveLoadTest.respondingToTheServersPingKeepsTheConnectionAlive()` | PASS | 5.016 | 2s configured idle interval; probe within 10s; unanswered probe closes within 20s read window; answered probe permits JOIN |
| `RateLimitLoadTest.floodFromOneConnectionDoesNotDegradeOtherWellBehavedClients()` | PASS | 2.306 | 500-message flood; 20 well-behaved messages, paced 110ms; each delivery <1s |

## Delivery Step 1 validation

- T008: `./gradlew :jircd-integration-tests:test --tests '*SajoinRepeat*' --tests '*DepartureRecipients*' --tests '*QuitNeighbors*' --tests '*ModeFanout*'` — PASS, exit code 0, on unchanged production code. Four new characterization tests passed. The initial run failed because the new sentinel helper expected a prefixed PONG; corrected to match the existing unprefixed wire format, without changing timeouts.
- T009–T011 red check: `./gradlew :jircd-core:test --tests '*Recipients*' --tests '*RepliesListed*' --tests '*ChannelTest*'` — expected FAIL, exit code 1: missing APIs at compilation, followed by all nine new test methods failing with UnsupportedOperationException against placeholder APIs, before implementation.
- T021: `./gradlew :jircd-core:test --tests '*Recipients*' --tests '*RepliesListed*' --tests '*OutboundMessageBudget*' --tests '*ChannelTest*'` — PASS, exit code 0. Gradle's JaCoCo dependencies also ran the integration suite.
- T021 final command: `./gradlew build` — PASS, exit code 0, including tests, Spotless, SpotBugs and PMD. An earlier build failed on three PMD CloseResource false positives in new test loops; documented suppressions explain that the enclosing try-with-resources owns and closes all clients.
- Formatting: `./gradlew spotlessApply` — PASS, exit code 0.
- No existing integration test case was modified; the four planned characterization tests were added. Review item 5 adds a barrier method to RawIrcClient test support and migrates only those four new tests to it. No timeout increases or retries were added.
- T001–T021 complete; T022 onward remain unchecked. Phase 4 was not started. No commits or stashes; optional before/after implementation commit hooks skipped per user instruction.

## Retained baseline and Step 1 output excerpts

Unchanged `./gradlew build` (T001, exit code 0):

```text
BUILD SUCCESSFUL in 1m 18s
103 actionable tasks: 103 executed
```

Unchanged `./gradlew loadTest` (T002, exit code 0):

```text
ConcurrentConnectionScaleLoadTest > deliveryLatencyStaysWithinBudgetAtOneThousandConnections() PASSED
KeepAliveLoadTest > idleConnectionIsProbedThenDisconnectedIfNeverAnswered() PASSED
KeepAliveLoadTest > respondingToTheServersPingKeepsTheConnectionAlive() PASSED
RateLimitLoadTest > floodFromOneConnectionDoesNotDegradeOtherWellBehavedClients() PASSED
Story1DeliveryLatencyLoadTest > deliveryLatencyStaysWithinOneSecondBudget() PASSED
BUILD SUCCESSFUL in 32s
48 actionable tasks: 3 executed, 45 up-to-date
```

Characterization gate T008 (unchanged production, exit code 0):

```text
DepartureRecipientsTest > partAndKickReachEveryPreDepartureMemberBeforeLaterReplies() PASSED
ModeFanoutTest > modeAndTopicChangesReachEachMemberExactlyOnce() PASSED
QuitNeighborsTest > quitDeduplicatesNeighborsAndDropsOnlyEmptyChannels() PASSED
SajoinRepeatTest > alreadyJoinedAdminBroadcastsAgain() PASSED
BUILD SUCCESSFUL in 1s
```

T009–T011 red check (exit code 1, placeholder APIs before implementation):

```text
20 tests completed, 9 failed
BUILD FAILED in 1s
```

T021 scoped gate (exit code 0), then final full build (exit code 0), before review fixes:

```text
BUILD SUCCESSFUL in 1m 7s
45 actionable tasks: 12 executed, 33 up-to-date
BUILD SUCCESSFUL in 2s
103 actionable tasks: 11 executed, 92 up-to-date
```

## Step 1 review corrections and validation

All review items 1–8 applied:

1. QUIT neighbors again accumulate in a LinkedHashSet after each channel removes the quitter; self is removed before Recipients.deliver. A registry-monitor-controlled regression admits a newcomer before a later channel removal. It failed before the correction with an empty output instead of QUIT, and passes afterward. No sleeps or retries. T035 records the later migration of this interleaving to serialized leaveAll behavior.
2. SC-005 and plan/research/quickstart/T037/T053 require all load tests to pass their asserted bounds; Gradle durations are information only.
3. RecipientsTest explicitly asserts size four and self first.
4. SajoinRepeatTest also asserts the admin's own repeated JOIN.
5. RawIrcClient is test support; its new readThroughPong helper applies an explicit socket timeout and uses a unique token. Only the four new characterization tests use it; existing integration test cases are unchanged.
6. T044 includes ChannelTest's operator/voice setup migration; the first-joiner operator grant is commented.
7. RepliesListedTest covers two tokens exactly filling the command budget and one byte over, requiring a split only for the latter.
8. Key original output is retained inline above; transient log paths have been removed.

Regression red check (before the QUIT correction):

```text
25 tests completed, 1 failed
expected: ":erin!erin@host.example QUIT leaving\r\n"
 but was: ""
BUILD FAILED in 1s
```

Scoped tests (exit code 0):

```sh
./gradlew :jircd-core:test --tests '*DisconnectCleanupTest' --tests '*RecipientsTest' --tests '*RepliesListedTest' --tests '*ChannelTest' :jircd-integration-tests:test --tests '*SajoinRepeat*' --tests '*DepartureRecipients*' --tests '*QuitNeighbors*' --tests '*ModeFanout*'
```

```text
BUILD SUCCESSFUL in 2s
45 actionable tasks: 23 executed, 22 up-to-date
```

Then `./gradlew build` (exit code 0; tests, Spotless, PMD and SpotBugs pass):

```text
BUILD SUCCESSFUL in 1m 14s
103 actionable tasks: 40 executed, 63 up-to-date
```

No commit or stash. T022 onward remain unchecked; no Phase 4 implementation.

## Delivery Step 2 (T022–T037)

Implemented on the committed Step 1 head `b5b450b`, based on main `08bb476`, on 2026-10-16.

### Tests first

- Before production changes, `ChannelRegistryTest.twoKickHandlersForOneTargetBroadcastOnlyOneDeparture` failed (expected one KICK plus the losing command's numeric), and the approved `rejectedAdmissionDoesNotInsertMembership` assertion failed. Scoped command exit code 1; 3 tests ran, 2 failed.
- A forced legacy disconnect/JOIN reproduction completed cleanup while admission was blocked and then demonstrated the leftover membership. Exit code 1; 1 test ran and failed. The final regression is `ChannelRegistryTest.disconnectRacingAdmissionLeavesNoMembershipBehind`, using explicit admission/closing latches and `leaveAll`.
- T025's three-channel cleanup/idempotence characterization passed unchanged production code (exit code 0).
- The new transition, race and stress API tests were written before implementation; the first T037 scoped run failed compilation because `JoinResult`, `Departure`, `KickOutcome`, `part` and `leaveAll` did not exist. No new API implementation preceded that check.

### Changes and migrations

- Registry transitions own both sides of membership under registry → channel locking, return immutable snapshots, and drop empty registered channels. Delivery occurs after those monitors are released.
- JOIN handles `alreadyMember` silently; SAJOIN still broadcasts and sends the captured NAMES listing for a repeat membership. Closing sessions are refused without dereferencing a missing admission failure or throwing in SAJOIN.
- KICK/PART use the serial outcomes; only a successful removal broadcasts. The handler-level race regression now passes.
- The Step 1 QUIT regression now arranges the admitted join before cleanup, since join-between-channel-removals cannot occur under `leaveAll`'s registry lock. Its QUIT assertion is retained and the new ordering is documented in Javadoc. The prior admission/removal race also preserves its final assertions with serialized choreography.
- Stress workload: 32 sessions, 8 channels, 2,048 fixed-seed randomized operations over virtual threads, tracking recreated channel instances by identity. Final membership equivalence, registry non-emptiness, privilege subsets and recipient uniqueness pass.
- Package-private isolated setup remains in ChannelTest; OutboundMessageBudgetTest now creates its 60 memberships through registry joins. T044 records later migration of the voice setup added in T022/T025.

**Approved deviation**: rejected admission removes a channel created by that join only if still empty and still the registered instance. `rejectedAdmissionDoesNotInsertMembership` keeps its name and now asserts no session memberships and absent registry lookup, as approved. Recorded under T029 in tasks.md. No other design deviation.

### T037 gates

Executed in this order, each with exit code 0:

```sh
./gradlew :jircd-core:test --tests '*ChannelRegistry*' --tests '*DisconnectCleanup*' --tests '*MembershipStress*'
./gradlew build
./gradlew loadTest
```

Scoped tests (23 transition/cleanup/stress cases; Gradle's coverage dependencies also run integration tests):

```text
BUILD SUCCESSFUL in 1m 8s
47 actionable tasks: 26 executed, 21 up-to-date
```

Full build includes Spotless, SpotBugs, PMD and the new aggregate coverage floor:

```text
> Task :testCodeCoverageVerification
BUILD SUCCESSFUL in 10s
107 actionable tasks: 42 executed, 65 up-to-date
```

Aggregate coverage: LINE 3,071 / 3,330 = 92.22%; BRANCH 1,149 / 1,380 = 83.26%. Floors: 85% line / 75% branch.

Load-test gate:

```text
ConcurrentConnectionScaleLoadTest > deliveryLatencyStaysWithinBudgetAtOneThousandConnections() PASSED
KeepAliveLoadTest > idleConnectionIsProbedThenDisconnectedIfNeverAnswered() PASSED
KeepAliveLoadTest > respondingToTheServersPingKeepsTheConnectionAlive() PASSED
RateLimitLoadTest > floodFromOneConnectionDoesNotDegradeOtherWellBehavedClients() PASSED
Story1DeliveryLatencyLoadTest > deliveryLatencyStaysWithinOneSecondBudget() PASSED
BUILD SUCCESSFUL in 32s
36 actionable tasks: 2 executed, 34 up-to-date
```

All asserted bounds pass, including <1s per message delivery. Durations below are JUnit/Gradle whole-test durations for information only; no measured latency/throughput comparison is inferred (SC-005).

| Test | Outcome | Duration (s) |
|---|---|---:|
| `Story1DeliveryLatencyLoadTest.deliveryLatencyStaysWithinOneSecondBudget()` | PASS | 5.741 |
| `ConcurrentConnectionScaleLoadTest.deliveryLatencyStaysWithinBudgetAtOneThousandConnections()` | PASS | 2.861 |
| `KeepAliveLoadTest.idleConnectionIsProbedThenDisconnectedIfNeverAnswered()` | PASS | 15.045 |
| `KeepAliveLoadTest.respondingToTheServersPingKeepsTheConnectionAlive()` | PASS | 5.024 |
| `RateLimitLoadTest.floodFromOneConnectionDoesNotDegradeOtherWellBehavedClients()` | PASS | 2.32 |

No integration-test files changed in Step 2. `git diff --stat origin/main -- jircd-integration-tests/src/test/java/net/jircd/integration/` still shows exactly the four Phase 2 additions and RawIrcClient support changes from Step 1.

T022–T037 complete; T038 onward remain unchecked. No Step 3 implementation, commits or stashes. Optional implementation commit hooks remain skipped per user instruction.

## Step 2 review corrections

- Item 1: contract and data-model join descriptions now return `JoinResult.alreadyMember=true` for unchanged membership and document cleanup of a newly created, still-empty, still-registered channel on refusal/CLOSING. The result-type field list includes alreadyMember.
- Item 2: each workload departure includes its departed member; each successful non-repeated join includes its joiner; all recipients belong to the test's sessions. Removed the duplicate check on a Set. Conservation asserts initial 256 memberships + successful joins = departures + final memberships; the test counts memberships immediately before leaveAll while holding the registry monitor, without changing any production signature.
- Item 3: added the fifth randomized operation for voice grants, checking membership and adding voice under the channel monitor. Setup seeds voice so it is exercised even if randomized grants race with departures. MembershipStressTest is listed under T044 for migration to grantVoice.
- Item 4: admission remains blocked by a release latch while the test asserts the cleanup future is not done, before allowing the admitted join to finish.
- Item 6: Channel.members and ClientSession.channelMemberships each return one cached unmodifiable view stored in a final field. The existing live-view test now also checks stable view identity.
- Item 5 intentionally left unchanged for PR documentation, as requested.

Tests-first check: new cached-view identity assertion failed on the previous per-call wrapper allocation; strengthened cleanup/stress tests passed (3 tests, 1 expected failure; exit code 1). After caching, the scoped gate passed:

```sh
./gradlew :jircd-core:test --tests '*ChannelRegistry*' --tests '*DisconnectCleanup*' --tests '*MembershipStress*'
```

```text
BUILD SUCCESSFUL in 1m 8s
47 actionable tasks: 16 executed, 31 up-to-date
```

Then `./gradlew build` passed, exit code 0, including testCodeCoverageVerification, Spotless, PMD and SpotBugs:

```text
> Task :testCodeCoverageVerification
BUILD SUCCESSFUL in 10s
107 actionable tasks: 40 executed, 67 up-to-date
```

Aggregate BRANCH coverage: 1149/1380 = 83.26%.

Aggregate LINE coverage: 3074/3333 = 92.23%.

No integration-test files changed, no Step 3 implementation, no commit or stash. Load tests were not rerun for this review; the T037 load-test gate above remains the recorded run.
