# Implementation Plan: Capability Plugin Ownership and Runtime Lifecycle

**Branch**: `014-capability-plugin-spi` | **Date**: 2026-10-22 | **Spec**: [spec.md](spec.md)

**Input**: Issues #98/#97, the original feature description and the user's three design-review decisions. Design only until user review; no implementation or commits in this workflow.

## Summary

The six capability modules own behavior through typed provider registrations. Core retains CAP negotiation/lifecycle, committed domain events, authorized delivery, reply layouts, budgets and serialization. Ordinary output uses one captured immutable runtime/session view. Under the existing administrative control mutex, a state change publishes a new RuntimeSnapshot and physically prunes ConnectionHandler.liveSessions, then queues DEL/NEW. Older captured/preparing output may finish after DEL. No strict output cutoff is promised. Core advertises cap-notify independently of plugins.

[Research](research.md) records evidence/alternatives; [SPI](contracts/capability-spi.md), [lifecycle](contracts/runtime-lifecycle.md), [budgets](contracts/reply-budgets.md), [model](data-model.md) and [validation guide](quickstart.md) are the implementation contracts.

## Technical Context

**Language/Version**: Java 25, existing Gradle multi-module server, unreleased `0.1.0-SNAPSHOT`.

**Primary Dependencies**: Existing ServiceLoader, java.util.concurrent, JUnit 6/AssertJ, SLF4J and jircd-protocol. No new runtime library or separate public API module.

**Storage**: Immutable in-memory runtime/negotiation views, transient events/contributions, existing desired-state YAML. No persistent negotiation/history/account state.

**Testing**: Tests first; SPI/provider unit contracts, socket lifecycle/failure/composition tests, irctest comparison and tagged loads. Existing integration files are unchanged in #100/#101. #97 updates exact-LS/provider-absence expectations for advertised cap-notify; no unrelated assertions weakened.

**Target Platform / Project Type**: Existing JVM server with virtual-thread connections/writers and capability leaf modules; Linux/macOS/CI unchanged.

**Performance Goals**: Existing delivery max <1 s and quiet-channel JOIN-storm p99 <500 ms/max <1 s/storm <15 s. #102 measures <=10% throughput regression versus recorded main baseline unless justified, and flags >10% latency/CPU/allocation increases. At 1,000 live sessions, administrative-mutex wait + snapshot/prune publication p99 <=100 ms, with notification enqueue/config I/O/provider callbacks/socket drain reported separately.

**Constraints**: Command <=512 bytes including CRLF; independent tag-origin/section caps; exact SendQ including framing; no plugin callbacks under core locks or on writer drain; no socket I/O during publication; no global registry/session scan per recipient. Batch's final current-prerequisite choice is a short group-only administrative-mutex + writer-lock operation; ordinary output admission uses only its queue lock.

**Scale/Scope**: Six providers; all live sessions including pre-registration; finite bounded groups. O(sum shared-channel memberships) event audience, O(applicable hooks + output bytes) per recipient, O(sessions × removed names) publication. Account/history/label/userhost features are mapped, not implemented.

## Constitution Check

These are design checks; implementation evidence is required per PR.

| Principle/gate | Result | Implementation proof |
| --- | --- | --- |
| I: responsibility/dead code/public contracts | PASS | Typed hooks justified by six current providers; no new module/event bus; #101 deletes legacy behavior and documents public API migration |
| II: deterministic tests/no coverage decrease | PASS | Tests first, regression proof, retained concurrency cases with bounded cleanup; every PR build/static/coverage floors (85% line/75% branch) plus no aggregate decrease |
| III: path equivalence/real usage/breaking changes | PASS | Shared lifecycle and socket walkthrough; intentional cap-notify LS changes tested; migration guide/PR explanation under unreleased first-release version rule |
| IV: budgets/re-runnable measurement | PASS | Budgets declared above; each hot-path PR records relevant existing load results/comparison, #97 records repeatable publication timing from its socket fixture, #102 adds dedicated final load/JFR proof; >10% regressions require optimization or justification |
| Ordered merge gates | REQUIRED per PR | Scoped automated tests → full build/static checks → other-vendor review → real-use walkthrough/irctest as applicable → relevant measured performance before merge; rerun build after review fixes |

Basic exception containment begins in #100. Final FAILED withdrawal/diagnostics and SC-005 load acceptance are completed in #102; earlier PRs do not claim the entire feature is done. No constitution waiver is requested or build result claimed here.

## Delivery

All four issues are sub-issues of #98. Every checklist task in [tasks.md](tasks.md) belongs to exactly one PR, in this order. Design docs land with #100. No implementation task runs before user review.

| PR | Issue / scope | Tasks | Count | Exit checkpoint |
| --- | --- | --- | --- | --- |
| 1 | [#100](https://github.com/jircd/jircd-server/issues/100): SPI foundation + server-time + design docs | T001–T016 | 16 | Safe typed dispatch/byte accounting, server-time wire unchanged, legacy hooks retained for remaining providers; `./gradlew build` green |
| 2 | [#101](https://github.com/jircd/jircd-server/issues/101): message-tags → echo-message → away-notify → multi-prefix → batch; remove legacy hooks | T017–T041 | 25 | All six own behavior, provider absence removes it, no forbidden core branch, migration docs/irctest evidence; `./gradlew build` green |
| 3 | [#97](https://github.com/jircd/jircd-server/issues/97): runtime lifecycle, prune, negative REQ, advertised cap-notify | T042–T054 | 13 | Both admin paths/all client classes, new captures use current state, batch fallback/closure, exact-LS updates; `./gradlew build` green |
| 4 | [#102](https://github.com/jircd/jircd-server/issues/102): FAILED withdrawal, bounded diagnostics, dedicated load measurement | T055–T066 | 12 | Faults isolated over socket paths, <=10% throughput regression unless justified, SC-005 evidence/canonical docs; `./gradlew build` green |

Tasks remain story-labelled within each PR; the PR boundary governs delivery. Each PR owns its own tests, documentation, review evidence and final build checkpoint. No task belongs to two PRs. Dedicated final load classes land in #102, while earlier hot-path PRs still run applicable existing repeatable loads/timings to meet their merge gates.

## Project Structure

### Documentation

`specs/014-capability-plugin-spi/`: spec, plan, tasks, research, data-model, quickstart, three contracts and requirements checklist. Validation reports are future artifacts under its `validation/` directory, not generated by this revision.

### Planned source changes

```text
jircd-core/src/main/java/net/jircd/core/
  extension/{CapabilityExtension,AbstractCapabilityExtension,ServerContext,ExtensionRegistry}.java
  extension/capability/       # descriptor/registrar/hooks/runtime/guard/layout contracts
  capability/CapabilityNegotiator.java
  session/                   # generic events/member facts/delivery/prepared frames
  session/{ClientSession,ConnectionHandler,SessionWriter,CapabilityTagRenderer,OutboundMessage}.java
  session/{Channel,ChannelRegistry,DisconnectCleanup}.java
  session/command/            # core event publishers/CAP/JOIN/NAMES/WHO, no feature-name branches
  config/{ConfigurationLoader,ConfigurationReloader,HostmaskReplyBudget}.java
jircd-capabilities/{server-time,message-tags,echo-message,away-notify,multi-prefix,batch}/
  src/{main,test}/java/
jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java
jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/
  {SajoinCommandHandler,ExtensionCommandHandler,RehashCommandHandler}.java
jircd-protocol/src/main/java/net/jircd/protocol/CapabilityNegotiationGrammar.java
jircd-integration-tests/src/test/java/net/jircd/integration/
```

Keep SPI in core, preserve dependency direction and admin/cloak operational model. Use provider-owned staged command registration, not a rewrite of server extensions. ConnectionHandler.liveSessions is the all-session authority; NicknameRegistry omits unnamed sessions.

## Architecture and responsibilities

1. **Registration/guard**: Descriptor + CapabilityRegistrar stage typed hooks with owner, stage/slot, prerequisites, fallback and finite bounds. Validate ids/offers/key/slot conflicts and dependency DAG. Immutable tables are published as RuntimeSnapshot. Basic guard rejects/catches bad contributions in #100 and reports them through the existing sanitized operator-visible diagnostic path; #102 connects runtime faults to FAILED withdrawal and completes provider-level bounded diagnostics.
2. **Events**: Core commits state, captures permitted identity/audience facts, releases registry/channel locks, then invokes hooks. Implement away/join/part/quit/nick; retain accepted repeated-AWAY behavior, no-op JOIN/nick/duplicate cleanup silence and pre-departure peers. Hooks return intents, not socket writes.
3. **Delivery/tags**: Core authorizes targets once, merges sender-copy/ordinary/request-reply roles, deduplicates recipient+envelope and supplies one immutable captured view per recipient. Share pure validated tag contributions by envelope/hook identity; no callback lifetime tracking. Move semantic inbound tags/msgid/TAGMSG to message-tags; server-time is independent.
4. **Replies**: Core exposes consistent ordered held statuses and permitted identity facts. Ordinary output uses highest prefix; multi-prefix owns the status transform. Separate NAMES identity slot composes with future userhost-in-names. Fit complete fields/tokens after composition; generic bounds cover NAMES/WHO and all other existing layouts. No capability-shaped JOIN strings or booleans remain after #101.
5. **Groups**: Batch owns refs, syntax and tags. Core validates full finite alternatives and reserves complete frames including close. At final group-only admission, the existing administrative mutex protects the current prerequisite choice and queue append; missing batch/message-tags selects plain. No plugin call under locks. Previously admitted groups remain closed regardless of DEL timing.
6. **Lifecycle/capture**: Under the existing administrative control mutex, publish snapshot + physical prune, then queue DEL/NEW. CAP/session mutation and snapshot capture share that mutex. Ordinary preparation/append does not coordinate with later changes. Old captured work may follow DEL. Stop runs outside core locks; providers own safe overlap with their captured callbacks. Enable never restores prior negotiations.

## Migration order and green checkpoints

Use a temporary explicit bridge for unmigrated providers only. Exactly one behavior path per provider; never execute legacy and registered hooks together. #100 retains legacy methods for others; #101 removes both after migration.

| PR / step | Capability | Required scoped checkpoint before next migration |
| --- | --- | --- |
| #100 / 1 | server-time | Pure tag registration/cache; Story2NegotiatedDeliveryTest + tag-byte/guard tests |
| #101 / 2 | message-tags | Inbound normalization/forwarding, msgid, owned TAGMSG + shared target gates; TagmsgCommandTest, Story2MessageIdTest, ConnectionAndCapPrecisionTest |
| #101 / 3 | echo-message | Sender role; direct/channel/self PRIVMSG/NOTICE/TAGMSG, no duplicates; DirectMessageGuaranteesTest/self-direct unit tests |
| #101 / 4 | away-notify | Event-owned interested-peer AWAY; dedup/cloak/core confirmations; AwayNotifyTest + generic event tests |
| #101 / 5 | multi-prefix | Member snapshots/render slots and bounds across NAMES/JOIN/SAJOIN/WHO; MultiPrefixTest/NamesByteBudgetTest |
| #101 / 6 | batch | Provider framing + atomic full queue operation/plain alternative; provider/group tests and socket probe |

#100/#101 run the existing integration suite unchanged; core/provider unit fixtures may migrate APIs. #97 updates exact CAP LS and provider-absence assertions to include always-offered core cap-notify, without weakening unrelated behavior. #102 reuses those final expectations. Each PR ends with its own full green build.

## Dependencies and future capabilities

| Consumer | SPI seam | Separate future work |
| --- | --- | --- |
| batch | Owner + session message-tags prerequisite; missing prerequisite uses plain, no offer cascade | None for current finite groups |
| server-time | Independent pure outbound tag | No message-tags dependency |
| echo-message + labeled-response | Sender-copy/request-reply roles + immutable RequestContext, inbound/tag/group/completion stages, no cross-plugin calls | Label wire rules, no duplicate ACK when echo is response, no labels on unsolicited peers |
| account-notify #6 | Committed account-change event + interested peers/recipient negotiation | Account/auth state and real publisher; no dummy domain producer now |
| chathistory #7 | Provider-owned command, historical envelope ids/times, finite groups with batch prerequisites | Storage/query/auth/paging and history batch type; no unlimited streaming |
| userhost-in-names | Identity slot independent of status slot + byte bound | Provider wire behavior and `!ident@displayHost` bound/privacy policy |

Hard dependencies are a validated DAG with atomic dependent withdrawal; session prerequisites only suppress behavior. Optional interactions compose explicit phases/roles/context. Future domain events/layouts may be added; core must not add capability-name behavior branches.

## Validation and checklist coverage

- Every hook type: valid/invalid/throwing result containment in #100; runtime FAILED/DEL/prune and sanitized rate-bounded diagnostics in #102. No rollback of committed core operations; cleanup continues after stop failure.
- Retained concurrency cases: CAP mutation versus prune, live-session insertion/attachment, a valid old capture finishing after DEL, and batch prerequisite choice/whole-group append. Use bounded coordination to prove these cases; do not assert suppression of old captured work or wait for callback completion.
- Reply/queue budgets: NAMES whole tokens + 366, WHO flags/fitted text, independent escaped tag origins, complete group reservation/close and recipient-only overflow cleanup.
- Invalid reload/REQ/dependency/size requests change nothing; accepted known removals and exact echoes/sticky 302 behavior are tested.
- No per-recipient global scans or callbacks in domain/admin/queue critical sections. Measure mutex hold/wait and notification append separately; each hot-path PR records existing loads, #102 supplies dedicated final measurement.
- Each PR updates public Javadoc/docs it changes, passes `.github/REVIEW_CHECKLIST.md`/other-vendor review and ends with build green. Canonical lifecycle docs change in #97 and gain final failure/measurement details in #102.

## Complexity Tracking

**Amendment (2026-10-24, #97 implementation)**: "the existing administrative control mutex" throughout this plan is the two-level arrangement recorded in [research.md](research.md) Decision 2. The existing `ExtensionRegistry` lifecycle lock serializes whole transitions, including a whole reload, and is the only lock provider callbacks run under. A short `CapabilityRuntime` mutex, taken after it, covers publication + prune + notification enqueue, CAP state, live-session tracking, nickname changes, captures and group admission. No such single mutex existed before #97.

No constitution violations. Immutable typed registrations/bounds serve current modules. Existing administrative control provides atomic snapshot/prune; only batch's finite admission needs its short current-prerequisite selection. No new lifecycle synchronization subsystem or general event bus is justified.

## Risks and accepted tradeoffs

- Captured output may follow DEL, and stop may overlap captured callbacks. This is the accepted rule; clients/tests cannot treat DEL as a strict output barrier. Record any irctest conformance consequence openly.
- Providers must make captured callbacks and start/stop thread-safe. A late fault belongs to the provider identity and may withdraw a newly re-enabled provider; core does not forcefully stop blocked trusted code.
- The administrative mutex visits all live sessions and also protects short captures/group selection. Measure contention/prune/notification append separately; slow sockets and plugin callbacks cannot run under it.
- Pre-materialized tag/group alternatives may increase allocation/CPU. Share immutable work, retain byte guards and prove SC-005 rather than assuming cheap dispatch.
- cap-notify changes exact LS/absence expectations in #97, including all-plugins-absent LS. Its core ownership and sticky 302 semantics must not depend on provider discovery.
- Existing unreleased public APIs/unit harnesses change in #100/#101. Keep the migration guide synchronized; external binary adapters remain outside scope.
- The local irctest harness already has user modifications. Freeze/hash its state and use identical selection/config for both runs; do not hide new failures/skips.
