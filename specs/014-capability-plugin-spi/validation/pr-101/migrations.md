# PR 2 (#101) migration checkpoints (T021, T024, T027, T030, T033)

Recorded on the uncommitted working tree of branch `014-capability-migrations` (base `fc3aeab`), JDK Temurin 25, Gradle 9.8.1. Each section was written when its checkpoint was green, before the next migration started.

Fixture proof used at every checkpoint: the sorted per-file `sha256` listing of `jircd-integration-tests/src/test` hashes to `ba2b56c66a7acd6bcd833be0a4ae0c50e07f837871c593133713932d85b8b474` (117 files), identical to [pr-100/integration-fixtures.md](../pr-100/integration-fixtures.md), and `git status --short jircd-integration-tests` lists nothing. The only integration source this PR adds are the two new files the tasks ask for (`CapabilityGroupTest`, `CapabilityProviderAbsenceTest`), recorded in `results.md`.

## 1. message-tags (T017–T021)

| Check | Result |
| --- | --- |
| `./gradlew :jircd-core:test :jircd-capabilities:message-tags:test` | green (`MessageDeliveryTest`, `MessageTagsExtensionTest`, `TagmsgCommandHandlerTest` and the existing core suite) |
| `./gradlew :jircd-integration-tests:test --tests '*TagmsgCommandTest' --tests '*Story2MessageIdTest' --tests '*ConnectionAndCapPrecisionTest'` | green: 13 tests, 0 failed |
| Integration fixture hash | `ba2b56c6…8b474`, 117 files, unchanged |

What moved: `MessageDelivery` (core) owns target resolution, send gates, the envelope and role selection. `MessageTagsExtension` owns the client-only-tag selection (inbound hook), `msgid` (outbound hook) and `TAGMSG` (provider command, `TagmsgCommandHandler` moved out of core). Core's `CapabilityTagRenderer` forwards a selected client tag to a recipient that negotiated the owner of an inbound-tag hook; it no longer names `message-tags`. The echo-message scan in `MessageDelivery` is a temporary bridge removed in the next migration.

## 2. echo-message (T022–T024)

| Check | Result |
| --- | --- |
| `./gradlew :jircd-core:test :jircd-capabilities:echo-message:test :jircd-capabilities:message-tags:test` | green (`EchoMessageExtensionTest`: 13 tests; `SelfDirectMessageTest` now stages a recipient hook instead of `includeSenderInFanOut`) |
| `./gradlew :jircd-integration-tests:test --tests '*DirectMessageGuaranteesTest' --tests '*Story2NegotiatedDeliveryTest'` (plus the message-tags set again) | green: 2 + 1 tests (and `TagmsgCommandTest`, `Story2MessageIdTest`), 0 failed |
| Integration fixture hash | `ba2b56c6…8b474`, 117 files, unchanged |

What moved: `EchoMessageExtension` registers a recipient hook returning `SENDER_COPY`. Core applies it only when the sender negotiated the owner, merges it with the ordinary role (a self-target is delivered once) and applies the recipient rule of the command to the copy (TAGMSG still needs `message-tags` on the copy). The temporary `includeSenderInFanOut` scan in `MessageDelivery` is gone.

## 3. away-notify and committed events (T025–T027)

| Check | Result |
| --- | --- |
| `./gradlew :jircd-core:test :jircd-capabilities:away-notify:test` | green (`CommittedEventTest`: 13 tests (at the end of the PR; 12 when this checkpoint was taken), `AwayNotifyExtensionTest`: 7 tests) |
| `./gradlew :jircd-integration-tests:test --tests '*AwayNotifyTest' --tests '*AwayStatusTest' --tests '*DepartureRecipientsTest'` (plus `Story6SajoinTest`, `SajoinRepeatTest`, `KillCommandTest`, `NickBroadcastTest`, `QuitNeighborsTest`) | green: 6 + 5 + 1 tests, 0 failed |
| Integration fixture hash | `ba2b56c6…8b474`, 117 files, unchanged |

What moved: core publishes `AWAY_CHANGED`, `JOINED`, `PARTED`, `QUIT` and `NICK_CHANGED` through `CommittedEvents` after the transition, with no monitor held, from `AwayCommandHandler`, `JoinCommandHandler`, `PartCommandHandler`, `NickCommandHandler`, `DisconnectCleanup` (which every QUIT, KILL, timeout and sendq path funnels through) and the admin `SajoinCommandHandler`. A kind nobody listens for resolves neither the presented identity nor the audience. `AwayNotifyExtension` owns the AWAY notice: it answers the event with one notice for the audience, and core narrows it to peers that negotiated `away-notify`. Core keeps the away state and the 305/306 confirmations.

## 4. multi-prefix (T028–T030)

| Check | Result |
| --- | --- |
| `./gradlew :jircd-core:test :jircd-capabilities:multi-prefix:test :jircd-server-extensions:admin:test` | green (core: 793 tests, 0 failed; `MemberReplyRenderingTest` 7 tests, `MultiPrefixExtensionTest` 5 tests) |
| `./gradlew :jircd-integration-tests:test --tests '*MultiPrefixTest' --tests '*NamesByteBudgetTest' --tests '*Story7WhoChannelTest' --tests '*Story6SamodeTest'` (plus `ReplyBudgetReloadTest`, `BareNamesTest`, `Story1NamesTest`, `Story6SajoinTest`, `Story5NamesPrefixTest`, `Story4InvalidConfigTest`, `Story4LengthLimitConfigTest`) | green: 2 + 11 + 2 + 2 tests, 0 failed |
| Integration fixture hash | `ba2b56c6…8b474`, 117 files, unchanged |

What moved: `Channel` no longer takes a multi-prefix flag; it exposes `memberFacts()` (the session plus every held status, highest first, read as one consistent view) and keeps `memberListing()` as the highest-status form integration tests use. `JoinResult` carries unformatted `MemberFacts`; the new `MemberReplies` renders NAMES tokens and channel-WHO status for the requester through the status and NAMES-identity slots, resolving identity fields only when a provider needs them. JOIN, NAMES (targeted and bare), the admin SAJOIN and WHO all use it. `MultiPrefixExtension` replaces the status slot with all held symbols and declares its own 2-byte bound; the fixed 2-byte "legacy" width is gone from `ReplyBudgetCatalog`, `ConfigurationLoader` and `HostmaskReplyBudget`, so a server without the provider reserves only core's single highest-status byte. With the module on the classpath (every shipped build) the reserved envelope is unchanged: the same limits are accepted and rejected.

## 5. batch (T031–T033)

| Check | Result |
| --- | --- |
| `./gradlew :jircd-core:test :jircd-capabilities:{batch,multi-prefix,away-notify,echo-message,message-tags,server-time}:test :jircd-server-extensions:admin:test` | green (`BatchExtensionTest` 11 tests, `SessionWriterGroupTest` 7 tests at this checkpoint, 8 at the end; the old `SessionWriterBatchTest` is replaced by them) |
| `./gradlew :jircd-integration-tests:test` (whole suite, load tests excluded) | green: 302 tests, 0 failed, 0 skipped (includes the 6 new `CapabilityGroupTest` socket probes) |
| Integration fixture hash | the 117 tracked files hash to `ba2b56c6…8b474` again; the only change under `jircd-integration-tests` is the new untracked `CapabilityGroupTest.java` |

What moved: `SessionWriter.enqueueBatch` is gone. `SessionWriter.enqueueGroup(type, params, members[, context])` builds a finite `OutputGroup`, asks the renderer (`TagRenderer.frame`, implemented by `CapabilityTagRenderer` through the hook dispatcher) for the recipient's framing plan, and queues the validated frames as one `FrameSequence` entry: all frames or none, so an opener never lacks its closer, concurrent groups never interleave, and a group larger than the queue overflows only that recipient before any transformed copy is allocated. `BatchExtension` owns the reference, the prefixless `BATCH` lines and the `batch` tag, with `message-tags` as a session prerequisite: `batch` alone is still offered, acknowledged and inert, and plain members are sent. A member that already carries a `batch` tag keeps it (nesting). Core does not choose the framing again at admission: that atomic current-prerequisite choice under concurrent capability changes is the later runtime-lifecycle change (#97), which this PR does not claim.
