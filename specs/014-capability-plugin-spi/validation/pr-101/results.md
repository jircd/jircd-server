# PR 2 (#101) results (T037, T041)

Recorded on the uncommitted working tree of branch `014-capability-migrations` (base `fc3aeab`, main after #104), JDK Temurin 25.0.4+7, Gradle 9.8.1, Apple M4 Pro. Per-checkpoint evidence is in [migrations.md](migrations.md), the self-check in [review.md](review.md). irctest (T039), SonarCloud and the other-vendor review were run by the coordinator: [irctest.md](irctest.md), [review.md](review.md).

## Final build (T041)

| Command | Outcome |
| --- | --- |
| `./gradlew spotlessApply` | applied, then clean |
| `./gradlew build --rerun-tasks` | **BUILD SUCCESSFUL** (1 m 36 s, rerun after review round 2): 1254 tests in the module XML reports, 0 failed, 0 skipped; Spotless, SpotBugs (main and test), PMD (main and test) and `testCodeCoverageVerification` all green |

Two earlier plain builds failed before this one and were fixed, not retried: `pmdTest` flagged `CloseResource` on test writers closed by an `@AfterEach` (suppressed with that reason at the six declarations) and `spotbugsTest` flagged `USO_UNSAFE_OBJECT_SYNCHRONIZATION` on a `synchronized` HashMap in `CommittedEventTest` (now a `ConcurrentHashMap`).

| Module | Tests |
| --- | --- |
| jircd-protocol | 58 |
| jircd-core | 810 |
| jircd-capabilities: away-notify / batch / echo-message / message-tags / multi-prefix / server-time | 7 / 11 / 13 / 13 / 5 / 6 |
| jircd-server-extensions:admin | 20 |
| jircd-integration-tests | 311 (302 existing and group probes, plus the 9 of `CapabilityProviderAbsenceTest`) |

### Coverage (aggregate over every module with `src/main/java`)

Report: `build/reports/jacoco/testCodeCoverageReport/` (`html/`, `testCodeCoverageReport.xml`).

| | Covered | Floor | After #100 | Result |
| --- | --- | --- | --- | --- |
| Line | **95.56%** (5744 of 6011) | 85% | 94.95% | pass, higher |
| Branch | **88.61%** (2341 of 2642) | 75% | 87.30% | pass, higher |

By package: `net.jircd.core.session` lines 1709 of 1746, branches 665 of 748; `net.jircd.core.extension.capability` lines 618 of 639, branches 314 of 354; each capability module's `main` code is fully covered by line (`message-tags` 51/51, `batch` 19/19, `away-notify` 10/10, `echo-message` 4/4, `multi-prefix` 5/5).

## Existing integration fixtures unchanged (acceptance)

`git status --short jircd-integration-tests` lists only the two new files the tasks ask for, `CapabilityGroupTest.java` (T031) and `CapabilityProviderAbsenceTest.java` (T035). The 117 tracked files under `jircd-integration-tests/src/test` hash to `ba2b56c66a7acd6bcd833be0a4ae0c50e07f837871c593133713932d85b8b474` (sorted `shasum -a 256` listing), identical to [pr-100/integration-fixtures.md](../pr-100/integration-fixtures.md), and `git diff HEAD -- jircd-integration-tests` is empty. The full existing socket suite passed against the migrated modules at every checkpoint and in the final build.

## Tests added or changed (T017–T037)

New (tests were written before the production code of each migration; I did not record a separate red run for each, but `MessageTagsExtension`/`TagmsgCommandHandler` did not compile and the echo-message tests failed on the unmigrated module before T023):

| Class (module) | Tests | Covers |
| --- | --- | --- |
| `MessageDeliveryTest` (core) | 13 | T017: send gates, bans, silent NOTICE, sender exclusion, direct self exactly once, role merging, one hook call per delivery, stable msgid/time, request context never reaching peers |
| `MessageTagsExtensionTest`, `TagmsgCommandHandlerTest` (message-tags) | 5 + 8 | T019: client-only tag selection and forged keys, msgid, TAGMSG recipient/send/error rules, oversize tags |
| `EchoMessageExtensionTest` (echo-message) | 13 | T022: sender copy on direct, channel and self PRIVMSG/NOTICE/TAGMSG, role merge, central sender gate |
| `CommittedEventTest` (core) | 13 | T025: every event kind, accepted-versus-rejected/no-op, repeated AWAY, old NICK identity, pre-departure audience, idempotent QUIT, listener and identity faults, unlistened kind costs nothing |
| `AwayNotifyExtensionTest` (away-notify) | 7 | T025: notice content, dedup, self exclusion, cloak, provider absent |
| `MemberReplyRenderingTest` (core), `MultiPrefixExtensionTest` (multi-prefix) | 7 + 5 | T028: facts without a flag, consistent status order, core highest default, independent slots, declared bound |
| `BatchExtensionTest` (batch), `SessionWriterGroupTest` (core), `CapabilityGroupTest` (integration) | 11 + 8 + 6 | T031: both/either/missing prerequisite, prefixless framing, unique ASCII references, empty/concurrent/nested groups, whole-group reservation, retained closer |
| `CapabilityCompositionTest` (core) | 5 | T034: a made-up capability from events, slots, request context, roles and groups |
| `CapabilityProviderAbsenceTest` (integration) | 9 | T035: each of the six providers hidden from the service file |

Changed unit fixtures (T037): `SelfDirectMessageTest` (recipient hook instead of `includeSenderInFanOut`), `ChannelTest` and `ChannelRegistryTest` (member facts; the 2 000 000-iteration status stress test now counts impossible reads and asserts once, because AssertJ per iteration made it take minutes), `ReplyBudgetConfigurationTest` and `CapabilityReplyBudgetTest` (explicit two-symbol catalog in place of the removed legacy one), `CapabilityTagRendererBudgetTest`, `CapabilityTagRendererMigrationTest`, `SessionWriterByteLimitTest` and `CapabilityDispatchTest` (typed hooks in place of `contributeTags` and `legacyOutboundTags`), `HostmaskReplyFitTest` and `MultiPrefixReplyTest` (a multi-prefix stand-in provider), `ServerTimeExtensionTest` (drops the check of the removed method). Removed: `SelfDirectTagmsgTest` (now `TagmsgCommandHandlerTest`), `SessionWriterBatchTest` (now `BatchExtensionTest` and `SessionWriterGroupTest`), `MessageTagsExtensionPurityTest` (now `MessageTagsExtensionTest`). Two old scenarios cannot exist any more and were reshaped: overlapping server tags from different providers (a tag key now has exactly one owner, rejected at publication) and a provider contributing a `+` key.

## Load comparison (T040)

`./gradlew loadTest` ran twice on main (`fc3aeab`, a temporary worktree, removed afterwards) and twice on this tree, interleaved, with nothing else running. All 8 load tests passed every time.

| Load test | main run 1 / run 2 | this tree run 1 / run 2 |
| --- | --- | --- |
| ExceptionModes join, p50 (ms) | 0.596, 0.144 / 0.630, 0.242 | 2.247, 0.251 / 0.682, 0.260 |
| ConcurrentConnectionScale p50 / p99 (ms) | 0.983 / 1.455; 0.716 / 1.737 | 0.902 / 1.641; 1.053 / 1.499 |
| JoinStorm (2000 clients) storm ms; quiet p50 / p99 (ms) | 2204; 33.9 / 119.4 · 2313; 27.9 / 82.9 | 2208; 26.5 / 156.5 · 2244; 31.5 / 171.6 |
| SingleWaveJoinStorm ms; overflow disconnects | 1924; 0 · 1951; 0 | 1956; 0 · 1942; 0 |
| Story1DeliveryLatency (50 messages) p50 / p99 (ms) | 0.524 / 0.845; 0.562 / 0.803 | 0.554 / 2.300; 0.578 / 2.397 |

Story1 alone, three more runs each, same order (p50 / p99 ms): main 1.369 / 1.903, 1.224 / 1.863, 1.413 / 2.165; this tree 1.432 / 2.303, 1.361 / 5.196, 1.384 / 1.957.

**Reading:** no meaningful regression. Storm times, overflow counts and the JOIN-storm quiet-channel latency are within run-to-run noise (storm ≈ 2.2 s against the 15 s budget, quiet p99 well under 500 ms). Story1's p99 is the maximum of 50 samples; in the interleaved runs this tree's was about 1.5 ms higher than main's, but the three isolated repeats overlap (1.96–5.20 ms against 1.86–2.17 ms) and the p50 is equal, so I read it as a single slow first delivery plus noise, not a systematic cost; all of it is three orders of magnitude under the 1 s budget. I did not profile it. The dedicated final load measurement belongs to the later change (#102).

## Deviations from tasks.md and why

1. **`DeliveryIntent`/`RequestContext` (T018):** the tasks say to add them under `session/`; #100 already defined both in `net.jircd.core.extension.capability`, and `MessageDelivery` uses those rather than a second pair. `MessageDelivery.Delivery` carries the envelope, the context and the roles per recipient.
2. **Forwarding client tags (T020):** instead of a name check, `CapabilityTagRenderer` forwards the tags an inbound-tag hook selected to a recipient that negotiated the owner of such a hook (`RuntimeSnapshot.forwardsClientTags`). Same wire behavior, no capability name in core.
3. **`TAGMSG` without the module (T020):** it is now an unknown command (`421`) when `message-tags` is absent or disabled; core used to register it unconditionally (recipients without the capability received nothing). The provider-absence test pins this. The command still works for a sender without `message-tags` when the module is enabled, as before.
4. **Event audience (T025/T026):** the audience excludes the subject, so "self exclusion" is the core's, not each listener's. A QUIT for a session that never had a nickname, ident and hostname publishes nothing, matching the existing QUIT notice.
5. **Reply API (T029):** `Channel.memberListing()` (no argument) and `JoinCommandHandler.sendNamesReply(requester, channel, server, List<String>)` are kept, because existing integration tests call them; the other `sendNames*` overloads now take the `ExtensionRegistry` first. `ReplyBudgetCatalog.legacyCompatible()` is removed; the multi-prefix module declares the 2 bytes itself.
6. **Groups (T032):** `enqueueGroup` queues a group as one entry, so a plain (unframed) group is also admitted whole where the old `enqueueBatch` queued members one by one; a member that already carries a `batch` tag keeps it (the old code overwrote it), which is what makes nesting possible. The "current prerequisite choice at admission under the administrative mutex" is the later runtime-lifecycle change (#97): framing is chosen when the group is prepared, and no document claims otherwise.
7. **Socket probe (T031):** `CapabilityGroupTest` enqueues the group on a live session's writer from the test thread rather than through a registered probe command, so no test extension is added to the shared integration classpath (it would have appeared in every test's extension listing).
8. **`CommittedEvents.publish` contains `RuntimeException`:** not in the tasks. A failure resolving the presented identity or the audience would otherwise cost a JOIN its NAMES reply or an AWAY its remaining replies after the state had committed.
9. **T039 (irctest):** run by the coordinator; no case-level differences from main. See [irctest.md](irctest.md).

## Open questions

- Whether the `time`/`msgid` tag hooks should one day be memoized on the compiled tag section rather than per hook; not needed for the budgets here.
- `CommittedEvents` logs a contained fault at WARN with the exception type only. The bounded, rate-limited diagnostic path is #102's; until then a persistently failing cloak would log once per event.
