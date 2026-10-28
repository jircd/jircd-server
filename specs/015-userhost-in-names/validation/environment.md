# Implementation environment

2026-10-27. Feature worktree `015-userhost-in-names`, initial clean HEAD `4c0ce89` (documentation only), code baseline `a2a12ad`. Temurin 25.0.4+7-LTS, default Gradle JVM settings; no performance runs in this round. Existing Java/Gradle ignore patterns verified. No CodeGraph index present.

Baseline aggregate coverage from the prior successful full build: **96.1676% line / 89.3352% branch**. Coverage comparison results are summarized in [build.md](build.md). Only the coverage summary is retained here.

Commands: scoped provider/socket tests, `spotlessApply`, `compileTestJava`, `build`. Load tests are compiled only, per coordinator handoff; irctest/Sonar/other-vendor review are coordinator-owned. No commits.
