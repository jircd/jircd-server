# Build validation

2026-10-27, branch `015-userhost-in-names`, uncommitted implementation on documentation HEAD `4c0ce89` / code baseline `a2a12ad`, Temurin 25.0.4+7.

## Before review-round edits

| Check | Command | Result |
| --- | --- | --- |
| Behavioral red, no-op provider with service/distribution wiring | `./gradlew :jircd-capabilities:userhost-in-names:test :jircd-integration-tests:test --tests '*UserhostInNames*'` | Exit 1: 2/2 unit and 4/4 socket cases failed. Registration had no identity hook; full multibyte identities and JOIN/NAMES/SAJOIN entries were bare nicknames instead. |
| Primary green | Same command after implementing the identity hook | Exit 0: 2 unit and 4 socket cases passed. |
| Composition/budgets | `./gradlew :jircd-integration-tests:test --tests '*UserhostInNamesTest' --tests '*NamesByteBudgetTest'` | Exit 0: four capability combinations, catalog reservations, maximum UTF-8 identity packing and closing replies passed. |
| Socket/lifecycle | `./gradlew :jircd-integration-tests:test --tests '*UserhostInNames*' --tests '*NamesByteBudgetTest'` | Exit 0: socket walkthrough, 7 lifecycle/config cases and byte-budget tests passed. |
| Scoped review checks | `./gradlew spotlessApply :jircd-integration-tests:pmdTest :jircd-integration-tests:test --tests '*Story4LengthLimitConfigTest' --tests '*CapabilityProviderAbsenceTest' --tests '*UserhostInNames*' :jircd-capabilities:userhost-in-names:test` | Exit 0: formatting, PMD and scoped tests passed; load fixture compiled only. |
| Negative provider boundaries | `./gradlew spotlessApply :jircd-capabilities:userhost-in-names:test` | Exit 0: all 4 provider cases passed, including malformed/over-bound identities and unsafe declarations falling back through the SPI. |
| Full build | `./gradlew build` | Exit 0: full integration-suite build in 1m 25s; final build after negative unit cases in 1s (155 tasks: 6 executed, 149 up-to-date). Spotless AOSP, SpotBugs, PMD and coverage verification passed. |

The initial full build failed one inherited diagnostic assertion: installing the identity descriptor changes the rejected 353 reservation from 515 to 607 bytes. Correcting that expectation preserved the rejection test. PMD findings for list-owned load sockets were addressed with explicit ownership and documented targeted suppressions. No timeout/retry changes were made. Initial test-helper compile errors were corrected before collecting behavioral red evidence.

| Aggregate coverage | Baseline | Final before review edits |
| --- | --- | --- |
| LINE | 96.1676% | 96.2309% |
| BRANCH | 89.3352% | 89.3698% |

Coverage did not decrease; floors of 85% line / 75% branch passed. Baseline coverage is summarized in environment.md. An intermediate report covered two fewer existing branches; the historical per-class report was not retained, so no attribution is claimed. Completing the negative provider contract tests raised final coverage above baseline. The provider is fully line-covered and adds no branches.

Passing suite counts before review edits: provider 4, lifecycle 7, socket 10, NamesByteBudget 13, provider absence 11, length-limit configuration 7. These functional test/build commands exclude the load tag. Coordinator-run load evidence is summarized separately in [performance.md](performance.md).

## Final coordinator validation — 2026-10-27

After the review fixes and latest performance documentation edits, the coordinator verified:

```sh
./gradlew cleanTest build sonar --no-parallel -Dsonar.qualitygate.wait=true -Dsonar.scm.disabled=true -Dsonar.branch.name=015-userhost-in-names
```

**BUILD SUCCESSFUL**. The clean test run, Spotless AOSP, SpotBugs, PMD and aggregate coverage verification passed. SonarCloud’s branch quality gate passed with **0 open issues**; the earlier S6126 YAML concatenation finding is fixed. The coverage percentages above describe the earlier recorded build; the coordinator confirmed the final coverage gate without supplying replacement percentages.

This final result validates the review-round package/docs wording, WHO-driven rejection descriptions, negotiated peer cloak checks, explicit NAMES after SAJOIN, fixture/import cleanup, and the text-block fix. Independent Claude review found no correctness defects and all its requests were addressed; see [review.md](review.md).

Irctest, performance analysis/justification and final acceptance are reconciled in [acceptance.md](acceptance.md). No unresolved blockers. This closeout records coordinator-supplied results only; it ran no Gradle or git commands and added no raw data. Commit/PR awaits user authorization.
