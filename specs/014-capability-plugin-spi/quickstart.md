# Quickstart: Validate the Four Feature 014 PRs

Future implementation validation only; no command below was run during this design revision. User review is required before implementation. Use Git worktrees, JDK 25 and existing Gradle toolchains. Never read `~/.gradle/gradle.properties`, stop shared Gradle daemons or commit credentials. A coordinator handles any required worktree/git metadata operations; do not bypass a failed mandatory step.

## Baseline and evidence ownership

Record baseline server revision `5912b68`, machine/JDK/JVM, existing test/static/coverage results and applicable load metrics before changing code. If comparison is against a later main, record that exact revision and use it consistently. Keep baseline distribution/code in an approved project worktree, not a temporary code workspace.

Record existing integration source hashes. #100/#101 must keep those sources/assertions unchanged. #97 updates only expectations deliberately affected by advertised core cap-notify (including exact LS/provider absence); record that narrow diff rather than treating all test edits as forbidden. #102 must preserve those expectations without unrelated weakening.

Future results live under `specs/014-capability-plugin-spi/validation/pr-100/`, `pr-101/`, `pr-97/` and `pr-102/`. Each PR records test commands, actual reports, fixture changes, review, relevant measurements and its own final green build. Design docs land in #100.

## PR 1 — #100 foundation and server-time

Write typed-registration, result guard, reply-bound and complete frame queue tests first. Exercise every hook shape with valid/throwing/null/invalid output; basic containment/fallback must protect committed core state and unrelated providers. Retain sanitized operator-visible error reporting through the existing diagnostic path; full runtime FAILED withdrawal/provider-level bounded diagnostic acceptance belongs to #102 after #97.

Stage typed registrations/immutable tables, generic budgets/frames and provider-owned services. Keep legacy contributeTags/includeSenderInFanOut for unmigrated providers and prove exactly one active path per provider. Migrate server-time and verify exact timestamp/pure immutable cache behavior, independent of message-tags.

```sh
./gradlew :jircd-core:test --tests '*Capability*' --tests '*SessionWriter*'
./gradlew :jircd-capabilities:server-time:test
./gradlew :jircd-integration-tests:test --tests 'net.jircd.integration.Story2NegotiatedDeliveryTest'
```

Run applicable existing tagged delivery/JOIN loads, record comparison, review public contracts, then finish with `./gradlew build` green. Record proof in `validation/pr-100/`. No notification or cap-notify LS change is claimed by this PR.

## PR 2 — #101 remaining providers

Migrate one at a time, writing module tests first and recording a green checkpoint before moving on:

```sh
./gradlew :jircd-integration-tests:test --tests 'net.jircd.integration.TagmsgCommandTest' --tests 'net.jircd.integration.Story2MessageIdTest' --tests 'net.jircd.integration.ConnectionAndCapPrecisionTest'
./gradlew :jircd-integration-tests:test --tests 'net.jircd.integration.DirectMessageGuaranteesTest'
./gradlew :jircd-integration-tests:test --tests 'net.jircd.integration.AwayNotifyTest'
./gradlew :jircd-integration-tests:test --tests 'net.jircd.integration.MultiPrefixTest' --tests 'net.jircd.integration.NamesByteBudgetTest'
./gradlew :jircd-capabilities:batch:test
```

These correspond to message-tags, echo-message, away-notify, multi-prefix and batch. Also run each provider's own tests and relevant core tests. Verify all NAMES/JOIN/SAJOIN/WHO routes, client-only tags from an unnegotiated sender where current behavior permits them, stable msgid/time and no duplicate self-target echoes. Add successful/no-op/rejected event coverage for away/join/part/quit/nick.

For module absence use an isolated discovery/runtime catalog or test classpath, omit the missing id from test-owned desired config, never delete installed JARs. Assert absent offer AND behavior and unaffected core/other providers. #97 later extends this matrix so LS retains core cap-notify even without all providers.

Remove both legacy hooks/adapters only after the last migration; audit every inventory site/literal alias in research. Run synthetic field/request/group composition probes, generic budget boundary tests and the full frozen-harness irctest comparison below. Record migration/API/docs/review/performance evidence under `validation/pr-101/`, then finish with `./gradlew build` green.

## PR 3 — #97 runtime lifecycle

Update exact CAP LS tests to include cap-notify as always-offered core infrastructure. A no-provider runtime still advertises it. Explicit REQ is governed by the offered set; test normal positive acceptance, sticky >=302 support, legacy removal and implicit-only omission from LIST.

Use TestServer-owned generated YAML/credentials, never local `jircd-server/jircd.yaml`. Matrix: {EXTENSION, REHASH} × {explicit legacy cap-notify, implicit CAP LS >=302, legacy no-notify} × {multi-prefix, message-tags}. New test names become runnable when implemented:

```sh
./gradlew :jircd-integration-tests:test --tests '*CapabilityLifecycleTest' --tests '*CapabilityGroupLifecycleTest' --tests '*CapabilityProviderAbsenceTest'
./gradlew :jircd-core:test --tests '*CapabilityLifecycleNegotiationTest'
```

Walkthrough:

1. Register an administrator and the three peer classes; negotiate target capabilities and establish +o/+v/NAMES/WHO/message behavior. Explicit cap-notify must first be visible in LS.
2. Disable through EXTENSION, or edit only test-owned YAML then REHASH. Under the administrative mutex, snapshot publication and pruning of all live sessions occur together, then DEL is queued. Check exact target/list, including eligible pre-nick `*` and a never-held observer. Legacy no-notify gets no DEL.
3. CAP LS excludes the withdrawn provider but includes cap-notify. LIST excludes pruned names. Commands initiated after the completed administrative operation capture new state and return ordinary prefix/tag behavior. `CAP REQ :-name` ACKs for already-pruned known names; held-but-unoffered is a separate controlled setup. Unknown/mixed/unavailable/empty/oversize requests reject atomically with exact echo/417.
4. Enable and observe NEW, with LIST still lacking the name until an explicit new request. Repeat/no-op toggle and invalid reload do not emit differences or mutate state. Use bounded PING/PONG barriers for absence checks.
5. Test concurrent CAP/session insertion/attachment against one all-session prune. Capture an old output view, publish disable and let that work finish: output after DEL is allowed. Assert the next capture uses current state; do not assert a strict cutoff or lifecycle retry.
6. For a finite group, prove all frames/close are admitted together. An already-open group's close survives (unless the connection terminates), without asserting a general ordering relative to DEL. Hold another group before final admission, disable a prerequisite, then admit it and assert entirely plain members. Exercise this through a real registered test command, not a private writer shortcut.
7. Verify stop is invoked outside core locks and provider callbacks can safely overlap it. Use bounded barriers only for retained state/group semantics and always release/close in finally blocks.

Record a repeatable 1,000-session publication timing sample using the socket fixture plus applicable existing tagged loads before this PR merges. The declared state-publication budget is <=100 ms p99 for administrative-mutex wait + snapshot/prune. Measure notification enqueue and stop/drain separately. #102 adds the dedicated load classes/final controlled comparison. Update canonical lifecycle contracts and review, then finish #97 with `./gradlew build` green and reports under `validation/pr-97/`.

## PR 4 — #102 FAILED withdrawal and measurements

Inject exceptions/null/illegal/oversized/unauthorized results for every hook category, using real sockets for hooks affecting wire output. Assert FAILED state and the same snapshot/prune/DEL path, bounded sanitized diagnostics, committed core outcomes, complete fallback and continued unrelated delivery. Test partial start/cleanup/stop failure, explicit recovery, hard-dependency cascades and a late callback fault after re-enable attributed to provider identity. Do not wait for active callbacks as a lifecycle precondition.

At maximum valid server/nick/channel/ident/host lengths verify combined status + synthetic identity decorations, WHO fitted realname, NAMES whole tokens/366 and escaped client/server tags. Assert exact serialized bytes, origin/total caps and complete group SendQ reservation. Impossible startup/reload/enable bounds reject without state mutation. Null/lying bounds fault the provider; valid text fitting is not a fault.

Run dedicated tagged CapabilityFanoutLoadTest (10/100/1,000 mixed recipients) and CapabilityLifecycleLoadTest (1,000 sessions) via `./gradlew loadTest`. Warm up and measure at least five identical runs per baseline/candidate, with same-machine/JVM/config/payload/negotiation/rate settings. Capture JFR CPU/allocations and report raw paths, medians, p99/max and throughput. Require <=10% message throughput regression unless justified; flag >10% latency/CPU/allocation increases. Preserve existing delivery <1 s and JOIN-storm quiet p99 <500 ms/max <1 s/storm <15 s. Compare publication wait/hold separately from notification enqueue/callbacks/drain; old output completion is not part of that budget.

Rerun full irctest with final cap-notify expectations, update canonical failure policy/README, perform full socket usage/review and coverage checks. Record under `validation/pr-102/`. After review fixes, finish with `./gradlew build` green. Every PR must meet build's Spotless/SpotBugs/PMD/coverage floors (85% line/75% branch) with no aggregate coverage decrease.

## Frozen irctest comparison

The local harness is `/Users/gcastro/Projects/jircd/irctest`, recorded HEAD `76b91582ca8f8cf6528f750c10f104e0df45865a`, with existing user modifications to controller and requirements. Preserve them. Hash those files and record dependency environment/controller config; HEAD alone does not pin it. The current controller declares the three Story2 capabilities; dedicated socket tests cover the added provider/lifecycle behavior too.

Use exactly the same frozen harness state for baseline/candidate, without new skips, reduced advertised features or user-file resets/stashes. A coordinator provisions any required harness worktree and reviewed controller patch. Set JIRCD_JAVA_HOME to the same JDK 25, build each server with `./gradlew :jircd-server:installDist`, and run from that harness with a revision-specific launcher/report path, for example in #101:

```sh
JIRCD_LAUNCHER=/Users/gcastro/Projects/jircd/jircd-server/.worktrees/014-capability-plugin-spi/jircd-server/build/install/jircd-server/bin/jircd-server python3 -m pytest --controller irctest.controllers.jircd --timeout=60 --timeout-method=signal --junitxml=/Users/gcastro/Projects/jircd/jircd-server/.worktrees/014-capability-plugin-spi/specs/014-capability-plugin-spi/validation/pr-101/irctest-candidate.xml
```

Use baseline/candidate result paths in each PR's directory and compare case ids/pass/fail/skip sets. SC-004 requires zero new failures/skips; record unrelated baseline failures. The accepted rule allowing captured work after DEL differs from the stronger protocol cutoff interpretation: if a conformance test exposes that tradeoff, report it as an unresolved observed failure for user review, not a skip or invented pass. No such outcome is claimed in this design phase.

## Per-PR review and stop rule

Follow `.github/REVIEW_CHECKLIST.md` and obtain the required other-vendor review before each implementation PR. On trusted branches use the documented SonarCloud gate when credentials are available through Gradle, without reading its properties file; otherwise record the repository-permitted no-token skip. Fix real findings, rerun required checks, and end that PR's task group with `./gradlew build` green. Final #102 results map all FR/SC acceptance evidence. No commit, push, PR creation or implementation is authorized by this document revision.
