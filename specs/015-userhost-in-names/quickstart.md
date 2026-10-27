# Validation Guide: Userhost in NAMES

Run after implementation approval and completion. Nothing here is a claim of a test run.

## Prerequisites and scoped checks

Use Java 25 and this feature worktree. Do not read or edit the credential-bearing `jircd-server/jircd.yaml`; use test-generated configuration or a separate sanitized worktree-local configuration. Preserve baseline revision and matching machine/JVM settings before measurements.

```sh
./gradlew :jircd-capabilities:userhost-in-names:test
./gradlew :jircd-integration-tests:test --tests '*UserhostInNames*'
./gradlew :jircd-integration-tests:test --tests '*NamesByteBudgetTest'
./gradlew build
```

Write tests first and record the expected failing behavior before implementing. Full build includes static analysis/formatting and aggregate coverage; coverage must not decrease. Load tests require their separate commands below.

## Socket walkthrough

1. Start a server with both capability ids enabled as in [the contract](contracts/userhost-in-names.md), plus a test cloak configuration. Register an operator/voiced member and clients with the four negotiation combinations.
2. Exercise targeted NAMES, bare NAMES, JOIN and SAJOIN listings. Compare exact tokens against the contract; compare displayed hosts with ordinary presented identity. Verify that protected real hosts never appear. Repeat without cloaking.
3. Populate a channel so 353 splits, including maximum and multibyte identity inputs; reconstruct complete tokens across lines and assert every visible member once, 512-byte command limits including CRLF and final 366. Verify empty/invisible/private/secret cases and unchanged WHO.
4. Remove negotiation; disable/re-enable provider and reload enabled/disabled settings. Check LS/LIST, NAK atomicity, NEW/DEL and explicit renegotiation. Distinguish new captures from output captured before withdrawal.
5. Try exact-fit and overflowing configuration with both descriptors installed, including disabled ones. Assert actionable rejection and unchanged configuration after invalid reload. Record evidence in `validation/socket.md`.

## irctest (explicit harness change)

A coordinator must provision an isolated harness Git worktree, carrying the reviewed existing patch without altering the shared checkout. Start from the [spec-014 environment record](../014-capability-plugin-spi/validation/pr-100/irctest-environment.md), inspect current state, and record the new controller patch enabling and declaring multi-prefix and userhost-in-names. Preserve the old freeze; record new hashes, revision, dependencies, selection and JDK. Both server revisions must use the identical revised harness. No external harness changes are authorized by this planning document alone.

Build each server revision with `./gradlew :jircd-server:installDist`; set `JIRCD_LAUNCHER` to that worktree's installed launcher and `JIRCD_JAVA_HOME` consistently. In the provisioned harness worktree run:

```sh
.venv/bin/python -m pytest -p no:cacheprovider --controller irctest.controllers.jircd -m 'not deprecated and not Ergo and not Sable and not services' --timeout=60 --timeout-method=signal -q -rfEs --junitxml=<per-revision-result.xml>
```

Run the three `CapTestCase` targets (`testReqTwo`, `testReqOneThenOne`, `testReqPostRegistration`) first, then full selection on baseline and candidate, sequentially. Save case-level pass/fail/skip deltas, controller patch/hashes and raw output under `validation/irctest.md` and `validation/raw/`. Target cases must pass on candidate; explain all other changes. Inspect and clean up only server processes started by those runs.

## Performance

Add tagged `UserhostInNamesLoadTest` for 10/100/1,000-member negotiated NAMES, bounded queues, complete replies and p99 completion <1 s. Run:

```sh
./gradlew :jircd-integration-tests:loadTest --tests '*UserhostInNamesLoadTest'
./gradlew :jircd-integration-tests:loadTest --tests '*Story1DeliveryLatencyLoadTest' --tests '*JoinStormLoadTest' --tests '*CapabilityFanoutLoadTest'
```

Measure the repeatable NAMES workload against baseline using a matching test-only workload carried into a baseline worktree (never capability implementation); baseline legitimately uses nickname identities. Record that output-size difference. Use warmup and >=5 matching measured runs per revision with identical member sets/request rates/JVM settings. Preserve raw results, medians/p99/max, throughput and CPU/allocation profiles in `validation/performance.md`. Apply plan budgets, flag >10% regressions, and run full `./gradlew loadTest` because performance budgets are affected.

Before a future PR, follow `.github/REVIEW_CHECKLIST.md` and AGENTS.md Sonar/other-vendor review gates. Record real findings and fixes; no PR or commit is part of this planning phase.
