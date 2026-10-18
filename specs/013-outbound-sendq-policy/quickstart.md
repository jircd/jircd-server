# Quickstart: validating the sendq policy

Prerequisites: JDK 25 toolchain, worktree `.worktrees/013-outbound-sendq-policy`.

1. Unit and integration tests (scoped first):
   `./gradlew :jircd-core:test --tests '*SessionWriter*' --tests '*ConfigurationLoader*' --tests '*ConnectionMonitorLog*'`
   `./gradlew :jircd-integration-tests:test --tests '*SendqOverflow*'`
   Expect: green; a non-reading client is closed, receives `ERROR ... (SendQ exceeded)` when it can, others unaffected.
2. JOIN storm (load tag, excluded from `test`):
   `./gradlew :jircd-integration-tests:loadTest --tests '*JoinStormLoadTest*'`
   Expect: 2,000 simultaneous JOINs, 0 overflow disconnects, budgets met (see spec SC-001, SC-002).
   Run at least 7 alternating before/after runs and compare medians (variance is high, see #48).
3. Config check: set `sendqLimitBytes: 100` (below the 16384 floor) and then `2097153` (above the
   2 MiB ceiling) in a test config and start the server; expect a startup error naming
   `sendqLimitBytes` each time.
4. Full gate: `./gradlew build` (tests, Spotless, SpotBugs, PMD, coverage 85%/75%).
