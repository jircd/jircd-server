# jircd — agent instructions

IRCv3 server in Java 25 (Gradle toolchain, multi-module). The project rules live in `.specify/memory/constitution.md` (code quality, testing, UX consistency, performance, quality gates). Read the relevant section when a change touches it, not by default.

## Modules

`jircd-protocol` (wire format) → `jircd-core` → `jircd-capabilities:*` (message-tags, server-time, echo-message, away-notify, batch) and `jircd-server-extensions:*` (cloak, admin) → `jircd-server` (app, `JircdServerApplication`). `jircd-integration-tests` holds the end-to-end tests over real sockets.

## Commands

- Scoped test run first: `./gradlew :<module>:test --tests '<Class or pattern>'`
- Before you call work done, run `./gradlew build`. It runs the tests plus Spotless, SpotBugs and PMD, the same as CI (`build.yml`).
- Formatting: `./gradlew spotlessApply`. The pre-commit hook runs `spotlessCheck`; enable it with `git config core.hooksPath .githooks`.
- Load tests (tag `load`) are excluded from `test`. Run them only with `./gradlew loadTest` when performance budgets are affected.
- Coverage floor: `build` also runs `testCodeCoverageVerification` (aggregate over every module with `src/main/java`), which fails below 85% line / 75% branch. Report: `./gradlew testCodeCoverageReport`. Weekly CI (`load-test.yml`) runs `loadTest`.
- Local server config `jircd-server/jircd.yaml` holds credentials and is gitignored. Never commit it.

## Workflow

- Make code changes in a worktree under `.worktrees/<name>` on its own branch (`fix/issue-N-…`, `triage/issue-N-…`). Other agents work in the main checkout at the same time.
- Write tests first (constitution, Testing Standards). Commit messages and PRs explain why, and reference the issue (`fixes #N`).
- Do not add session links (`Claude-Session:` trailers or claude.ai session URLs) to commits. `Co-Authored-By` attribution is fine.
- Spec-kit features (`specs/NNN-name/`): generate spec → plan → tasks, then **stop and wait for the user's review** before `/speckit-implement` or writing any implementation code.
- If a mandatory spec-kit hook fails (for example `speckit.git.feature` cannot create the branch), stop before generating any spec artifacts and report the failure. Do not write `specs/` files on the wrong branch.
- Codex's sandbox cannot write a worktree's git metadata, so its `speckit.git.feature` hook fails in a worktree. When delegating spec-kit to Codex, the coordinator creates the worktree on the feature branch first (`git worktree add -b NNN-slug .worktrees/NNN-slug origin/main`, where NNN is the next number in `specs/`) and tells Codex to run the hook with `GIT_BRANCH_NAME=NNN-slug` and `--allow-existing-branch`. The hook then needs no git writes.

## Before opening a PR

PRs get reviewed by SonarCloud (quality gate plus non-gating issues, all of which get fixed) and Sourcery. Catch what they would flag before the PR exists, so each PR needs at most one follow-up round.

- Check the diff against [`.github/REVIEW_CHECKLIST.md`](.github/REVIEW_CHECKLIST.md): the Sonar rules this repo keeps hitting, and what Sourcery keeps finding (failure and cleanup paths, concurrency and reload, protocol size limits, hostile input, extension boundaries, validation edge cases, rejected requests, tests that pass while the code is wrong, and docs claims).
- Local Sonar analysis: `./gradlew build sonar --no-parallel -Dsonar.branch.name=<branch>` (`--no-parallel` as in CI). It uploads a short-lived branch analysis to SonarCloud with the PR gate's rules and needs a token in `systemProp.sonar.token` in `~/.gradle/gradle.properties` (never read or print that file). Then read the branch's open issues (`https://sonarcloud.io/api/issues/search?componentKeys=jircd_jircd-server&branch=<branch>&resolved=false`) and fix them before opening the PR. The SonarQube CLI's `sonar analyze` only scans secrets on this plan; its code analysis returns 403.
- Independent review: an agent from the other vendor (Claude for Codex-written changes, Codex for Claude-written ones) reviews the diff against the same checklist. Fix the real findings before opening the PR.
- Push once per review round: wait for both SonarCloud and Sourcery, batch every fix into one change, then push.
