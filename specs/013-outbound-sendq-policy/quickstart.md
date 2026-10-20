# Quickstart: validating the sendq policy

Prerequisites: JDK 25 toolchain, worktree `.worktrees/013-outbound-sendq-policy`. Run every Gradle
invocation serially with `--no-daemon`. No implementation starts until the revised docs are reviewed.

1. Scoped unit tests first:
   `./gradlew --no-daemon :jircd-core:test --tests '*SessionWriter*' --tests '*ConfigurationLoader*' --tests '*ConnectionMonitorLog*'`
   Run the new purity tests in `:jircd-capabilities:message-tags:test` and
   `:jircd-capabilities:server-time:test`. Verify the conservative accounting bound against rendered
   wire bytes, including escaped tags, a legal 4094-byte server contribution and memoization.
2. Overflow integration behavior (after Phase 4):
   `./gradlew --no-daemon :jircd-integration-tests:test --tests '*SendqOverflow*'`
   Expect a stuck reader to close, best-effort ERROR, and other readers unaffected.
3. Existing latency regression test (load tag, excluded from `test`):
   `./gradlew --no-daemon :jircd-integration-tests:loadTest --tests 'net.jircd.integration.JoinStormLoadTest' --rerun`
   Expect waves of 100, storm <15 s, quiet p99 <500 ms, max <1 s; all bounds unchanged.
4. Separate survival test:
   `./gradlew --no-daemon :jircd-integration-tests:loadTest --tests 'net.jircd.integration.SingleWaveJoinStormLoadTest' --rerun`
   Expect 2,000 clients in one wave, all end-of-NAMES, zero overflow/unexpected disconnects; no
   latency assertion. The build's integration `loadTest` task may use a documented 2 GiB heap,
   based on T003; no init script is needed after implementation.
5. T015 measurements: seven alternating before/after pairs for the existing wave-of-100 test,
   Story1DeliveryLatencyLoadTest and ConcurrentConnectionScaleLoadTest with matching heap/JVM
   settings, plus seven post-change survival runs. Keep the original 512 MiB T002 figures labeled;
   record comparable medians and explain regressions over 10% in `baseline.md`.
6. Config check: use a disposable test configuration with `sendqLimitBytes: 100`, then `2097153`;
   each startup error names the setting. Never edit the local `jircd-server/jircd.yaml`.
7. Full gate: `./gradlew --no-daemon spotlessApply`, then `./gradlew --no-daemon build` (tests,
   Spotless, SpotBugs, PMD, coverage 85%/75%). Stop after T015 for review.
