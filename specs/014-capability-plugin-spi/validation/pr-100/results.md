# PR 1 (#100) results (T014, T016)

Refreshed after review round 2. Recorded on the uncommitted working tree of branch `014-capability-plugin-spi` (base `0d070b1`), JDK Temurin 25.0.4+7, Gradle 9.8.1, Apple M4 Pro.

## Final build (T016)

| Command | Outcome |
| --- | --- |
| `./gradlew spotlessApply` | applied, then clean |
| `./gradlew build` (after the PR #104 Sourcery fixes; not `--rerun-tasks`, so some modules were up to date) | **BUILD SUCCESSFUL**: 1148 tests in the module XML reports, 0 failed, 0 skipped; Spotless, SpotBugs (main and test), PMD (main and test) and `testCodeCoverageVerification` all green |

An earlier plain `./gradlew build` on the same tree failed `spotlessJavaCheck` and `pmdTest` (two test files emptied by my own scripting error, then four PMD test-source notes); both were fixed and the build re-run. A later plain build also passed before one last change (a stop failure after withdrawal is now recorded as FAILED, with a test), after which the `--rerun-tasks` run above is the final one.

### Coverage (aggregate over every module with `src/main/java`)

Report: `build/reports/jacoco/testCodeCoverageReport/` (`html/`, `testCodeCoverageReport.xml`).

| | Covered | Floor | Result |
| --- | --- | --- | --- |
| Line | 94.95% (5475 of 5766) | 85% | pass |
| Branch | 87.30% (2207 of 2528) | 75% | pass |
| New package `net.jircd.core.extension.capability` | lines 569 of 579, branches 252 of 292 | n/a | |

Against the SonarCloud baseline of main at `5912b68` (94.7% line, 87.5% branch), line coverage is higher and branch coverage is 0.2 points lower; the two tools count differently (JaCoCo here, SonarCloud there), so this is a direction check, not an exact comparison. The coordinator's SonarCloud run of the branch is the like-for-like figure.

### Test reports

Per-module HTML: `<module>/build/reports/tests/test/index.html`; XML: `<module>/build/test-results/test/`. PMD: `<module>/build/reports/pmd/`. SpotBugs: `<module>/build/reports/spotbugs/`.

## Tests added or changed (T004–T013)

Written before the production code in each case, but I did not run them against a missing implementation to record a red result: they were compiled and run together with the code. Two test files were also rewritten after a scripting mistake truncated them (content unchanged).

| Class (module) | Tests | Covers |
| --- | --- | --- |
| `CapabilityRegistrarTest` (core) | 15 | T004: ids/offers, reserved `cap-notify`, uniqueness, immutable staging, dependency validation, tag/slot/command conflicts, bounds, idempotent close |
| `CapabilityDispatchTest` (core) | 28 | T006: every registration kind with valid, throwing, null and invalid results; defaults; gating; no hook under the dispatcher/snapshot monitor |
| `PreparedDeliveryTest` (core) | 10 | T008: exact bytes with tags and CRLF, immutability, whole-sequence admission, overflow without a prefix, release once, no admission after close |
| `CapabilityReplyBudgetTest` (core) | 8 | T010: NAMES/WHO formulas, slot max vs sum, invalid bounds, startup/reload parity |
| `ExtensionRegistryCapabilityTest` (core) | 25 | T007: atomic publish, conflicts, cleanup after every failure kind, dependents, commands, enable-time budget, stop failure |
| `CapabilityTagRendererMigrationTest` (core) | 6 | T013: one path per provider, one evaluation per envelope, no drain-time callback, withdrawal |
| `ServerTimeExtensionTest` (server-time) | 7 | T012: exact tag, purity, bound at the extremes, immutability, containment, independence from message-tags |
| `ConnectionHandlerCommandTableTest` (core) | 1 | removal by handler identity over the real command table |
| `CapabilityNegotiatorTest` (core, changed) | 7 | one fixture changed, see deviations |

`ServerTimeExtensionPurityTest` was deleted: it called `contributeTags`, which server-time no longer implements; `ServerTimeExtensionTest` replaces it.

## T014: unchanged integration suite

`jircd-integration-tests` ran inside the build above: all its tests passed, including `Story2NegotiatedDeliveryTest` (1 test) and the rest of the suite. Fixture proof: `git diff HEAD -- jircd-integration-tests` is empty and `git status --short jircd-integration-tests` lists nothing; hashes are in [integration-fixtures.md](integration-fixtures.md). Wire behaviour for server-time is therefore unchanged by the existing socket tests (the `time` tag still arrives as before, now through the typed hook).

## Load comparison (T015)

Run by the coordinator on main and on this branch; all 8 load tests passed on both and show no meaningful regression. Numbers and the conclusion are in [baseline.md](baseline.md). They predate the round-2 review fixes and are being re-run. irctest was not run for #100.

## Deviations from the spec or tasks

1. **Duplicate offers are rejected at enable** (spec: "duplicate offers cannot silently replace another provider"). The existing unit test `twoExtensionsOfferingTheSameNameDoNotBreakRequests` enabled two providers with one offer name; it is now `aSecondExtensionOfferingTheSameNameIsRejectedAndRequestsStillWork`. No integration test depends on it.
2. **`disable` refuses while another published provider requires it** (`IllegalStateException`, nothing changes) instead of cascading; the transitive withdrawal is #97's scope and no provider declares dependencies yet.
3. **Hooks other than outbound tags have no live caller** in #100. They are registered, validated and dispatched with containment, and tested through `HookDispatcher`, but core call sites arrive with their migrations in #101.
4. Provider commands are installed on enable (never replacing a command already handled) and unregistered on disable.
5. **`PreparedDelivery` carries the captured `RuntimeSnapshot` view** as the data model describes, but nothing reads it yet.
6. Review rounds 1 and 2 (Codex and SonarCloud, run by the coordinator) changed several designs after the first version of this record: command removal is by handler identity (`CommandRegistrar.unregister(command, handler)`, no defaults), frame and group validation are stricter, and typed and legacy tags share one enqueue-time budget. See [review.md](review.md).
