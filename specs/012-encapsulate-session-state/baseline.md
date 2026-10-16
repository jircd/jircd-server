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
