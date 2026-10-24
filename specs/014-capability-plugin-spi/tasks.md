# Tasks: Capability Plugin Ownership and Runtime Lifecycle

**Input**: [spec.md](spec.md), [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md), [SPI](contracts/capability-spi.md), [lifecycle](contracts/runtime-lifecycle.md), [budgets](contracts/reply-budgets.md), [quickstart.md](quickstart.md).

**Authorization**: Design docs only until user review. Every implementation task remains unchecked. No commit, push or PR creation is authorized here.

**Organization**: Exactly four PR task groups in delivery order. Every task has exactly one `[PR100]`, `[PR101]`, `[PR97]` or `[PR102]` owner; `[USn]` identifies the story where relevant. `[P]` permits only disjoint-file preparation after prerequisites, in separate worktrees when writers run in parallel. One writer per worktree. Each PR's final task is its own green `./gradlew build` checkpoint.

**Tests first**: New behavior/bug tests must demonstrate expected failure before the relevant change. #100/#101 leave existing integration sources unchanged. #97 updates only expected CAP LS/provider-absence behavior for advertised core cap-notify; no unrelated assertions weakened. Basic hook containment lands in #100; complete FAILED withdrawal/diagnostics and dedicated SC-005 measurements land in #102.

## PR 1 — #100: SPI foundation + server-time + design docs (T001–T016)

**Goal**: Prove safe typed registration/dispatch, generic reply/frame budgets and one migrated provider, while retaining legacy hooks for the others. Final runtime behavior is delivered by later PRs.

### Evidence and foundation tests first

- [x] T001 [PR100] Record baseline server `5912b68` (or exact later main used consistently), machine/JDK/JVM, existing build/coverage/load results and actual report paths in `specs/014-capability-plugin-spi/validation/pr-100/baseline.md`; use coordinator-provisioned project worktrees, never temporary code workspaces or sandbox git writes.
- [x] T002 [P] [PR100] Freeze/hash irctest HEAD `76b91582ca8f8cf6528f750c10f104e0df45865a` plus the already-modified controller/requirements, dependency environment and configuration in `specs/014-capability-plugin-spi/validation/pr-100/irctest-environment.md`; preserve user modifications and obtain coordinator provisioning for isolated harness execution without stash/reset.
- [x] T003 [PR100] Include all ten reviewed feature design files under `specs/014-capability-plugin-spi/` in this PR and record existing integration hashes in `validation/pr-100/integration-fixtures.md`; preserve them through #101 and document the narrow #97 CAP LS expectation exception.
- [x] T004 [P] [PR100] Add descriptor/registrar tests in `jircd-core/src/test/java/net/jircd/core/extension/capability/CapabilityRegistrarTest.java` for nonempty protocol-safe ids/offers, uniqueness/reserved core cap-notify, immutable staged registrations, “Immutable set; no self, duplicate, unknown or cyclic hard dependency”, slot/key conflict, bounded metadata and idempotent rollback/close.
- [x] T005 [PR100] Define/document `CapabilityDescriptor.java`, `CapabilityRegistrar.java` and typed contracts under `jircd-core/src/main/java/net/jircd/core/extension/capability/`; add `CapabilityExtension.register` and AbstractCapabilityExtension boilerplate, explicit prerequisite/default/ownership rules and staged resource scopes, retaining contributeTags/includeSenderInFanOut for unmigrated modules only.
- [x] T006 [PR100] Add typed-dispatch tests in `jircd-core/src/test/java/net/jircd/core/extension/capability/CapabilityDispatchTest.java` for every event/inbound-tag/outbound-tag/recipient/member/field/group/command registration; assert valid result behavior and exception/null/invalid-result containment/fallback, no partial core outcome damage, and no callback under domain/admin/queue locks; do not demand runtime FAILED withdrawal before #102.
- [x] T007 [PR100] Implement immutable RuntimeSnapshot/typed HookDispatcher in `jircd-core/src/main/java/net/jircd/core/extension/capability/` and wire provider-owned services/commands via `extension/ServerContext.java`, `CommandRegistrar.java`, `ExtensionRegistry.java` and `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`; stage atomically, route each provider through exactly one legacy/new path, preserve admin/cloak and dependency direction, and provide basic safe result guard plus existing sanitized operator-visible error reporting for T006; #102 completes provider-level bounded diagnostics and FAILED withdrawal.
- [x] T008 [PR100] Add whole-frame queue tests in `jircd-core/src/test/java/net/jircd/core/session/PreparedDeliveryTest.java` for immutable materialized frames, exact bytes including CRLF/tags, “No partial admission or independently droppable close”, SendQ overflow and release-once cleanup; ordinary output uses captured data without lifecycle revalidation.
- [x] T009 [PR100] Implement generic `PreparedDelivery.java`/`FrameSequence.java` and complete queue append in `jircd-core/src/main/java/net/jircd/core/session/SessionWriter.java`; prevalidate/reserve bounded full sequences, keep callbacks/materialization outside locks/drain, use only the existing queue lock for ordinary append, and retain the legacy batch entry point until #101.
- [x] T010 [PR100] Add `jircd-core/src/test/java/net/jircd/core/config/CapabilityReplyBudgetTest.java` for “Nonnegative finite byte bound evaluated from validated configuration; no null, overflow or unbounded result”, compatible slot max/sum, current NAMES/WHO safe envelope, startup/reload/enable parity and independent escaped tag origins.
- [x] T011 [PR100] Define ReplyLayout/ReplyContribution under `jircd-core/src/main/java/net/jircd/core/extension/capability/` and generic validators in `config/ConfigurationLoader.java`/`HostmaskReplyBudget.java`; reserve discovered compatible decorations, retain safe legacy widths until #101, fit permitted text/list fields only, and account for exact serialized tags/group frames without silently dropping output.

### server-time test, migration and PR checkpoint

- [x] T012 [PR100] [US1] Add `jircd-capabilities/server-time/src/test/java/net/jircd/capabilities/servertime/ServerTimeExtensionTest.java` for exact envelope timestamp tag, purity, bounded immutable contribution/cache by envelope/hook identity, failure containment and independence from message-tags.
- [x] T013 [PR100] [US1] Migrate `jircd-capabilities/server-time/src/main/java/net/jircd/capabilities/servertime/ServerTimeExtension.java` to outbound-tag registration; update generic `jircd-core/src/main/java/net/jircd/core/session/CapabilityTagRenderer.java`/`OutboundMessage.java` to share validated immutable contributions with no provider callback on drain, and prove the old hook is not also executed.
- [x] T014 [PR100] [US1] Run provider/core tag-byte/guard tests and unchanged `jircd-integration-tests/src/test/java/net/jircd/integration/Story2NegotiatedDeliveryTest.java` plus the integration suite; record exact outcomes and unchanged-fixture proof in `specs/014-capability-plugin-spi/validation/pr-100/results.md`.
- [x] T015 [PR100] Update public Javadoc in `jircd-core/src/main/java/net/jircd/core/extension/`/`README.md` for the new SPI and temporary bridge; run applicable existing tagged delivery/JOIN loads, record baseline comparison, and complete `.github/REVIEW_CHECKLIST.md`/other-vendor review plus trusted-branch SonarCloud when available in `validation/pr-100/review.md`; fix findings before the final build.
- [x] T016 [PR100] Finish #100 with `./gradlew spotlessApply` then `./gradlew build` green (test/static/85% line/75% branch floors and no aggregate coverage decrease), recording reports in `specs/014-capability-plugin-spi/validation/pr-100/results.md`; checkpoint: safe SPI, server-time wire unchanged, design docs included, legacy hooks still available only for the remaining providers.

## PR 2 — #101: Five migrations + legacy hook removal (T017–T041)

**Goal**: Migrate message-tags → echo-message → away-notify → multi-prefix → batch, green after each; no capability-specific behavior remains in core.

### Shared delivery and message-tags

- [x] T017 [PR101] [US1] Add `jircd-core/src/test/java/net/jircd/core/session/MessageDeliveryTest.java` for shared target/send gates, bans/privacy/NOTICE silence, channel sender exclusion/direct self exactly once, merged roles and “unsolicited peer traffic does not inherit request correlation”; assert stable envelope id/time and no registry scan per recipient.
- [x] T018 [PR101] [US1] Extract `MessageDelivery.java`, immutable DeliveryIntent/RequestContext under `jircd-core/src/main/java/net/jircd/core/session/` from `command/MessageCommandHandler.java`; preserve core authorization/identity, recipient+envelope dedup, typed sender/recipient prerequisites, bounded provider-namespaced context and captured immutable inputs without lifecycle retry.
- [x] T019 [PR101] [US1] Add `MessageTagsExtensionTest.java`/`TagmsgCommandHandlerTest.java` under `jircd-capabilities/message-tags/src/test/java/net/jircd/capabilities/messagetags/` for client-only normalization/forged keys, sender without negotiation where permitted, exact shared msgid, TAGMSG recipient/send/error behavior and malformed/oversize tag containment.
- [x] T020 [PR101] [US1] Migrate `jircd-capabilities/message-tags/src/main/java/net/jircd/capabilities/messagetags/MessageTagsExtension.java` to inbound/msgid/recipient/command registrations; move core `session/command/TagmsgCommandHandler.java` into this module using MessageDelivery, remove MESSAGE_TAGS behavior from `CapabilityTagRenderer.java` and hard-wiring from `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`.
- [x] T021 [PR101] [US1] Run module/core tests and unchanged `TagmsgCommandTest.java`, `Story2MessageIdTest.java` and `ConnectionAndCapPrecisionTest.java` in `jircd-integration-tests/src/test/java/net/jircd/integration/`; record green checkpoint/fixture hashes in `specs/014-capability-plugin-spi/validation/pr-101/migrations.md` before echo-message.

### echo-message

- [x] T022 [PR101] [US1] Add `jircd-capabilities/echo-message/src/test/java/net/jircd/capabilities/echomessage/EchoMessageExtensionTest.java` for sender-copy policy on direct/channel/self PRIVMSG/NOTICE/TAGMSG, role merge/no duplicate recipient, immutable sender negotiation and request context propagation.
- [x] T023 [PR101] [US1] Migrate `jircd-capabilities/echo-message/src/main/java/net/jircd/capabilities/echomessage/EchoMessageExtension.java` to typed sender policy; remove provider scanning/echo assumptions from `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`; module TAGMSG shares the generic role pipeline.
- [x] T024 [PR101] [US1] Run provider/core self-direct tests and unchanged `jircd-integration-tests/src/test/java/net/jircd/integration/DirectMessageGuaranteesTest.java`/`Story2NegotiatedDeliveryTest.java`; append green checkpoint to `specs/014-capability-plugin-spi/validation/pr-101/migrations.md` before away-notify.

### Events and away-notify

- [x] T025 [PR101] [US1] Add `jircd-core/src/test/java/net/jircd/core/session/CommittedEventTest.java` and `jircd-capabilities/away-notify/src/test/java/net/jircd/capabilities/awaynotify/AwayNotifyExtensionTest.java` for successful/rejected/no-op away/join/part/quit/nick, accepted repeated AWAY, old NICK identity/pre-departure audience, idempotent cleanup, shared-peer dedup/self exclusion/cloak and committed state after a listener fault.
- [x] T026 [PR101] [US1] Publish generic committed facts after locks from core `session/ChannelRegistry.java`, `DisconnectCleanup.java`, `command/AwayCommandHandler.java`, `NickCommandHandler.java`, `JoinCommandHandler.java` and `PartCommandHandler.java`, including reused admin SAJOIN/KILL outcomes; move AWAY peer selection/message behavior into `jircd-capabilities/away-notify/src/main/java/net/jircd/capabilities/awaynotify/AwayNotifyExtension.java`, retaining core away state/confirmations.
- [x] T027 [PR101] [US1] Run event/module tests and unchanged `jircd-integration-tests/src/test/java/net/jircd/integration/AwayNotifyTest.java`/`AwayStatusTest.java`/`DepartureRecipientsTest.java`; append green checkpoint to `specs/014-capability-plugin-spi/validation/pr-101/migrations.md` before multi-prefix.

### multi-prefix

- [x] T028 [PR101] [US1] Add `jircd-core/src/test/java/net/jircd/core/session/MemberReplyRenderingTest.java` and `jircd-capabilities/multi-prefix/src/test/java/net/jircd/capabilities/multiprefix/MultiPrefixExtensionTest.java` for member facts with no multi-prefix flag, consistently ordered held statuses, core highest default, independent identity-slot composition and complete fitted tokens/WHO flags.
- [x] T029 [PR101] [US1] Migrate `jircd-capabilities/multi-prefix/src/main/java/net/jircd/capabilities/multiprefix/MultiPrefixExtension.java` to status/bound hooks; replace capability-shaped listing/booleans in core Channel/ChannelRegistry/JoinCommandHandler/WhoCommandHandler and admin SajoinCommandHandler with generic snapshots/rendering, and remove hidden fixed capability widths from `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java`/`HostmaskReplyBudget.java`.
- [x] T030 [PR101] [US1] Run member/module/budget tests and unchanged `MultiPrefixTest.java`, `NamesByteBudgetTest.java`, `Story7WhoChannelTest.java` and `Story6SamodeTest.java` under `jircd-integration-tests/src/test/java/net/jircd/integration/`; append green checkpoint to `specs/014-capability-plugin-spi/validation/pr-101/migrations.md` before batch.

### batch

- [x] T031 [PR101] [US1] Add `jircd-capabilities/batch/src/test/java/net/jircd/capabilities/batch/BatchExtensionTest.java` plus a registered finite-group socket probe in `jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityGroupTest.java`; assert both/either/missing prerequisite fallback, prefixless framing, valid unique ASCII refs, empty/concurrent/nested bounded groups, full reservation and retained close on admitted groups.
- [x] T032 [PR101] [US1] Implement group shaping in `jircd-capabilities/batch/src/main/java/net/jircd/capabilities/batch/BatchExtension.java`; move ref/BATCH/tag/gating out of core `session/SessionWriter.java`, migrate enqueueBatch callers/unit fixtures to generic enqueueGroup and validated framed/plain alternatives with whole-queue append; core expresses prerequisites generically, and #97 adds the atomic current-state choice during concurrent transitions.
- [x] T033 [PR101] [US1] Run batch/provider/generic writer/socket group tests and unchanged integration suite; append green checkpoint to `specs/014-capability-plugin-spi/validation/pr-101/migrations.md`; verify batch-only still negotiates/offers and delivers plain members, with server-time independent of message-tags.

### Composition, absence and cleanup

- [x] T034 [PR101] [US3] Add `jircd-core/src/test/java/net/jircd/core/extension/capability/CapabilityCompositionTest.java` for a test-only capability using existing committed events, identity/status slots, bounded RequestContext/roles and finite groups without a core name change; map account-notify/chathistory/label/userhost seams and remaining domain work without implementing those future features.
- [x] T035 [PR101] [US1] Add `jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityProviderAbsenceTest.java` using isolated catalogs/classpaths with matching desired config; test all six missing providers' offer AND behavior absence, core/unrelated behavior and no destructive JAR deletion. #97 extends this test to assert core cap-notify remains when any/all providers are absent.
- [x] T036 [PR101] [US1] Remove contributeTags/includeSenderInFanOut, temporary bridge, stale Javadoc and all dead capability behavior from `jircd-core/src/main/java/` and `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`; audit every research inventory site and literal/CapabilityName alias, leaving only CAP infrastructure and generic core behavior.
- [x] T037 [PR101] [US1] Migrate affected core/provider unit API fixtures under `jircd-core/src/test/java/` and `jircd-capabilities/`; document public hook/ServerContext/member/group API migration at definitions and `README.md`, with the unreleased `0.1.0-SNAPSHOT` first-release version signal. Keep existing integration sources unchanged.
- [x] T038 [PR101] Update ownership/extension claims in `specs/001-ircv3-server/data-model.md`, its `contracts/irc-protocol-commands.md` and `specs/011-away-notify-batch/contracts/away-notify-and-batch.md` to match module-owned behavior and generic reply/group rules; do not claim runtime #97/#102 mechanics are already implemented.
- [x] T039 [PR101] Run full baseline/candidate irctest with T002's identical frozen harness/config and `--timeout=60 --timeout-method=signal`; record case-level pass/fail/skip comparison and zero new failures/skips under `specs/014-capability-plugin-spi/validation/pr-101/`, retaining unrelated baseline failures and the full unchanged integration fixture proof.
- [x] T040 [PR101] Run applicable existing tagged fan-out/delivery/JOIN loads, record comparisons and resolve `.github/REVIEW_CHECKLIST.md`/other-vendor/SonarCloud findings as available in `specs/014-capability-plugin-spi/validation/pr-101/review.md`; verify docs/API/zero-branch inventory and all five migration checkpoints before final build.
- [x] T041 [PR101] Finish #101 with `./gradlew spotlessApply` then `./gradlew build` green, including static/coverage gates and no aggregate coverage decrease; record in `specs/014-capability-plugin-spi/validation/pr-101/results.md`. Checkpoint: all six modules own behavior, legacy hooks gone, unchanged existing integration suite and irctest evidence recorded.

## PR 3 — #97: Runtime lifecycle + advertised cap-notify (T042–T054)

**Goal**: Publish snapshot/prune atomically under the existing administrative mutex, then queue notifications. New captures see new state; older work may finish after DEL. Batch prerequisites are chosen at group admission.

### Tests first

- [ ] T042 [P] [PR97] [US2] Update exact-LS expectations in `jircd-integration-tests/src/test/java/net/jircd/integration/Story2CapabilityListTest.java` and other affected CAP LS tests, and extend `CapabilityProviderAbsenceTest.java` to retain advertised core cap-notify with any/all providers missing; add normal offered positive-REQ coverage without unrelated assertion weakening.
- [ ] T043 [P] [PR97] [US2] Add `jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityLifecycleTest.java` for {EXTENSION, REHASH} × {explicit legacy cap-notify, implicit >=302, legacy no-notify} × {multi-prefix, message-tags}; assert exact LS/DEL/NEW/LIST, commands whose runtime/session view is captured after publication, named/unnamed/never-held observers, re-enable without restoring negotiations, no-op/invalid reload and expected #97 baseline failures.
- [ ] T044 [P] [PR97] [US2] Add `jircd-core/src/test/java/net/jircd/core/capability/CapabilityLifecycleNegotiationTest.java` and protocol CapabilityNegotiationGrammarTest for held-but-unoffered/already-pruned/unknown removals, mixed/repeated/empty/oversize request atomicity/exact echo, sticky version/malformed/overflow handling, explicit/implicit LIST and legacy-vs-302 cap-notify removal.
- [ ] T045 [PR97] [US2] Add atomic snapshot/prune/CAP/session-insertion tests in `jircd-core/src/test/java/net/jircd/core/extension/capability/CapabilityRuntimeTest.java` and `jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityGroupLifecycleTest.java`; prove old captured work may complete after DEL, subsequent captures use new state, admitted groups keep close, unadmitted groups choose plain after prerequisites go ineffective, overflow is recipient-local and stop runs outside locks. Do not assert suppression of old captured output.

### State, protocol and administrative path

- [ ] T046 [PR97] [US2] Extend `jircd-protocol/src/main/java/net/jircd/protocol/CapabilityNegotiationGrammar.java` and core `capability/CapabilityNegotiator.java`/`session/command/CapCommandHandler.java` with advertised always-offered core cap-notify, overflow-safe sticky LS-version tracking, explicit-vs-implicit LIST and bounded deterministic NEW/DEL/LS/LIST packing including mandatory core-offer byte budget.
- [ ] T047 [PR97] [US2] Implement new immutable RuntimeSnapshot publication and physical all-session prune in one step under the existing administrative control mutex through `jircd-core/src/main/java/net/jircd/core/extension/capability/CapabilityRuntime.java`, ExtensionRegistry, ClientSession and ConnectionHandler.liveSessions; capture state/session views and register/remove/attach sessions under the same mutex, queue DEL/NEW after publication, keep old captured work valid and stop/cleanup outside locks; migrate direct-mutating negotiation unit fixtures under `jircd-core/src/test/java/` to the coordinator/test helper in this PR, without unrelated integration edits. Add only the generic group current-prerequisite choice + full append under administrative→one writer lock order, without plugin calls there.
- [ ] T048 [PR97] [US2] Replace separate validation/apply use in `jircd-core/src/main/java/net/jircd/core/capability/CapabilityNegotiator.java`/`session/command/CapCommandHandler.java` with one administrative-mutex candidate-set/reply-size/mutation operation; accept held or known-pruned negative tokens, ordinary offered positives including cap-notify, and preserve zero mutation on NAK/417 with exact request echo.
- [ ] T049 [PR97] [US2] Publish validated candidate capability config/dependency/budget changes coherently through `jircd-core/src/main/java/net/jircd/core/config/ConfigurationReloader.java`; hard dependent withdrawal shares publication, batch uses session-only fallback, invalid config changes nothing, valid provider start failures stay isolated, and enable never restores old negotiations; ensure start/stop callbacks are outside core locks.
- [ ] T050 [PR97] [US2] Route `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/ExtensionCommandHandler.java`/`RehashCommandHandler.java` through that lifecycle with existing privilege/file-independence/no-op behavior and actual outcome reporting; preserve SIGHUP/REHASH path equivalence and notify ordering by administrative transition, without ordering all captured output against DEL.
- [ ] T051 [PR97] Update runtime/CAP/path-equivalence text in `specs/001-ircv3-server/contracts/server-configuration.md`, `contracts/irc-protocol-commands.md` and `data-model.md` plus `README.md`/public Javadoc for snapshot capture, allowed old output after DEL, immutable negotiation, core cap-notify in LS, negative REQ, enable behavior and provider-safe stop overlap; reserve full hook-fault/diagnostic completion for #102.
- [ ] T052 [PR97] [US2] Run socket matrix/negotiation/group tests and quickstart's admin walkthrough, applicable existing loads and repeatable 1,000-session publication timings from the socket fixture; record exact frames/state/fixture diff, <=100 ms p99 wait+publish/prune and separate notification/callback/drain measurements in `specs/014-capability-plugin-spi/validation/pr-97/results.md`; final dedicated SC-005 load proof belongs to #102.
- [ ] T053 [PR97] Resolve `.github/REVIEW_CHECKLIST.md`/other-vendor/SonarCloud review findings as available and verify only planned exact-LS/provider-absence integration edits in `specs/014-capability-plugin-spi/validation/pr-97/review.md`; no strict-cutoff tests, lifecycle admission barrier or callback lifetime tracking may be introduced.
- [ ] T054 [PR97] Finish #97 with `./gradlew spotlessApply` then `./gradlew build` green, including static/coverage/no-decrease gates; record reports in `specs/014-capability-plugin-spi/validation/pr-97/results.md`. Checkpoint: both admin paths, atomic snapshot/prune, DEL/NEW, negative REQ, core advertised cap-notify and retained batch rules pass.

## PR 4 — #102: FAILED withdrawal + bounded diagnostics + load proof (T055–T066)

**Goal**: Complete runtime hook fault isolation on #97's path and prove SC-005 with dedicated loads and <=10% throughput regression unless justified.

### Tests first and isolation

- [ ] T055 [P] [PR102] [US3] Add `jircd-core/src/test/java/net/jircd/core/extension/capability/CapabilityFailureIsolationTest.java` for every hook/start/stop/partial-cleanup fault and null/illegal/unauthorized/out-of-bound result; assert FAILED via snapshot/prune/DEL, safe default for failed contribution, committed core outcomes, unaffected peers, bounded sanitized diagnostics, explicit recovery and stop/callback overlap without waiting for active calls.
- [ ] T056 [P] [PR102] [US3] Add `jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityFailureIsolationTest.java`/`CapabilityReplyCompositionTest.java` using real registered probes for every wire-affecting hook; assert exact failure/fallback/other-recipient content, maximum-length composed NAMES/WHO/366, UTF-8/escaped tag origins, full-group SendQ and startup/reload/enable rejection of impossible bounds.
- [ ] T057 [PR102] [US3] Complete runtime fault routing in `jircd-core/src/main/java/net/jircd/core/extension/capability/CapabilityRuntime.java`/HookDispatcher: ordinary exception or invalid-result faults withdraw provider/hard dependents as FAILED through #97's path, use documented fallback only for invalid contribution, continue cleanup after stop failure and emit provider/hook/category diagnostics rate-limited by provider/failure incident without raw payload/exception/credential leakage or automatic recovery.
- [ ] T058 [PR102] [US3] Add `jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityDependencyTest.java` for invalid hard graphs/missing prerequisites unchanged, atomic dependent FAILED withdrawal/notifications, explicit re-enable and late captured callback fault attribution after re-enable; preserve batch's soft message-tags fallback/offer and safe overlapping provider resource access.

### Dedicated load measurement and PR checkpoint

- [ ] T059 [P] [PR102] [US3] Add tagged `jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityFanoutLoadTest.java` for 10/100/1,000 mixed recipients and message/AWAY/member/group work; report p99/max/throughput and comparable JFR CPU/allocation with bounded resource cleanup, no uncontrolled rate-limit bursts/global scans and normal-test exclusion.
- [ ] T060 [P] [PR102] [US3] Add tagged `jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityLifecycleLoadTest.java` for 1,000 sessions changed during output/group work; measure <=100 ms p99 administrative-mutex wait + snapshot/all-session prune, with wait/hold, notification enqueue, config I/O, callbacks and drain separate; assert no deadlock, coherent new captures/sets and complete/plain group semantics without a post-DEL cutoff assertion.
- [ ] T061 [PR102] [US3] Run warmup plus at least five identical baseline/candidate runs and `./gradlew loadTest`, record machine/JVM/workload/raw reports/medians/p99/CPU/allocation in `specs/014-capability-plugin-spi/validation/pr-102/performance.md`; require <=10% throughput regression against the pinned main baseline unless justified and optimize or record review justification for >10% other-metric regressions, preserving existing delivery/JOIN budgets.
- [ ] T062 [PR102] Complete canonical FAILED/diagnostic and final lifecycle/performance documentation in `specs/001-ircv3-server/contracts/server-configuration.md`, `contracts/irc-protocol-commands.md`, `data-model.md`, public core Javadoc and `README.md`; retain captured-work/stop-overlap tradeoffs and the future-capability seam map.
- [ ] T063 [PR102] Rerun full baseline/candidate irctest after final lifecycle/fault behavior with T002's frozen harness/config and save case-level comparison under `specs/014-capability-plugin-spi/validation/pr-102/`; require zero new failures/skips, report any observed conformance tradeoff openly and retain known baseline failures rather than hiding them.
- [ ] T064 [PR102] [US3] Run all fault/composition/dependency/socket usage checks and FR-001–016/SC-001–006 traceability against final artifacts, checking existing integration edits remain limited to #97 expectations and no aggregate coverage decrease; record actual results/risks/future-feature exclusions in `specs/014-capability-plugin-spi/validation/pr-102/results.md`.
- [ ] T065 [PR102] Resolve `.github/REVIEW_CHECKLIST.md`/other-vendor/SonarCloud findings as available, validate all four checkpoints and >10% regression decisions in `specs/014-capability-plugin-spi/validation/pr-102/review.md`; fix real findings before the final build and do not create an unrequested extra review round/commit/push/PR.
- [ ] T066 [PR102] Finish #102 with `./gradlew spotlessApply`, all required load/test evidence, then `./gradlew build` green, including static/coverage floors and no decrease; record final reports in `specs/014-capability-plugin-spi/validation/pr-102/results.md`. Checkpoint: FAILED withdrawal/bounded diagnostics and dedicated SC-005 evidence pass; report feature completion without unrequested git writes.

## Dependencies and Execution Order

```text
PR #100 T001–T016 → build green
  → PR #101 T017–T041: message-tags → echo-message → away-notify → multi-prefix → batch → build green
  → PR #97  T042–T054: lifecycle/LS/REQ/groups → build green
  → PR #102 T055–T066: FAILED/diagnostics/loads → build green
```

Tests precede their implementation within each PR. T004→T005, T006→T007, T008→T009 and T010→T011 stabilize foundation before server-time. In #101 each three-task migration checkpoint gates the next module; T017/T018 supplies shared delivery first. T034/T035 precede final removal/docs/absence verification; T039/T040 precede build. In #97 T042–T045 tests precede T046–T050, then docs/usage/review/build. In #102 T055/T056 precede fault routing; T058 precedes dependency corrections if needed; load classes T059/T060 precede T061 measurement; final validation/review precedes T066.

Every PR includes necessary actual behavior/API docs and test evidence. No PR is marked complete merely because its code compiles. A failing mandatory step or checkpoint blocks the next PR; no git workaround or implementation is authorized by these design tasks.

## Parallel Examples

- **#100 / US1**: T004 descriptor and T006 dispatch test preparation can be read/reviewed independently once contracts are known; T006 needs T005 types to compile. T002 harness evidence is disjoint from descriptor tests. One writer in this worktree.
- **#101 / US1/US3**: Read-only review of a migration checkpoint and later synthetic test design are independent. Do not parallelize the ordered production migrations or edit shared MessageDelivery/TestServer from multiple workers.
- **#97 / US2**: T042 exact-LS, T043 lifecycle socket and T044 grammar/negotiation test preparation touch disjoint files after #101; separate writers require separate worktrees. Group/CAP overlap tests follow stable state contracts, not removed strict-cutoff behavior.
- **#102 / US3**: T055 core and T056 socket tests are disjoint; T059/T060 load-class authoring is disjoint after lifecycle stabilizes. Run measured baseline/candidate samples sequentially to avoid contention.

## Acceptance Mapping

| Requirement | Main tasks |
| --- | --- |
| FR-001 | T005–T014, T017–T041 |
| FR-002 | T005–T007, T018–T020, T025–T034 |
| FR-003 | T017–T020, T025–T036, T042 |
| FR-004 | T003/T014, T021/T024/T027/T030/T033, T039/T041/T042, T063/T064 |
| FR-005 | T043/T045/T047, T055–T058 |
| FR-006 | T043/T047/T049/T050/T052, T055–T058 |
| FR-007 | T042–T047/T050/T052, T055–T058 |
| FR-008 | T042/T044/T046/T048 |
| FR-009 | T044/T048/T052 |
| FR-010 | T004/T005, T031–T033, T043/T047/T049/T058 |
| FR-011 | T008/T009, T031–T033, T045/T047/T052, T055/T058/T060 |
| FR-012 | T004–T007, T025, T055–T058/T062 |
| FR-013 | T008–T011, T028–T033, T056 |
| FR-014 | T001/T015/T017/T018/T040/T052, T059–T061 |
| FR-015 | T012–T014, T019–T041 |
| FR-016 | T003/T015/T038/T051/T062 |
| SC-001 | T014/T021/T024/T027/T030/T033/T035–T042 |
| SC-002 | T042–T054, T055–T058 |
| SC-003 | T004/T006/T008/T010/T025/T031/T045/T055/T056 |
| SC-004 | T002/T039/T063 |
| SC-005 | T001/T015/T040/T052/T059–T061/T066 |
| SC-006 | T034/T062/T064 |

**Task counts**: #100: 16; #101: 25; #97: 13; #102: 12; total **66**. All unchecked, consecutively numbered and owned by exactly one PR. This four-PR sequence replaces the previous story-only delivery grouping; no task carries work into two PRs.
