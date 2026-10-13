# jircd — agent instructions

IRCv3 server in Java 25 (Gradle toolchain, multi-module). The project rules live in `.specify/memory/constitution.md` (code quality, testing, UX consistency, performance, quality gates). Read the relevant section when a change touches it, not by default.

## Modules

`jircd-protocol` (wire format) → `jircd-core` → `jircd-capabilities:*` (message-tags, server-time, echo-message, away-notify, batch) and `jircd-server-extensions:*` (cloak, admin) → `jircd-server` (app, `JircdServerApplication`). `jircd-integration-tests` holds the end-to-end tests over real sockets.

## Commands

- Scoped test run first: `./gradlew :<module>:test --tests '<Class or pattern>'`
- Before you call work done, run `./gradlew build`. It runs the tests plus Spotless, SpotBugs and PMD, the same as CI (`build.yml`).
- Formatting: `./gradlew spotlessApply`. The pre-commit hook runs `spotlessCheck`; enable it with `git config core.hooksPath .githooks`.
- Load tests (tag `load`) are excluded from `test`. Run them only with `./gradlew loadTest` when performance budgets are affected.
- Local server config `jircd-server/jircd.yaml` holds credentials and is gitignored. Never commit it.

## Workflow

- Make code changes in a worktree under `.worktrees/<name>` on its own branch (`fix/issue-N-…`, `triage/issue-N-…`). Other agents work in the main checkout at the same time.
- Write tests first (constitution, Testing Standards). Commit messages and PRs explain why, and reference the issue (`fixes #N`).
- Do not add AI attribution trailers (`Co-Authored-By: Claude…`, `Claude-Session:`) to commits.
- Spec-kit features (`specs/NNN-name/`): generate spec → plan → tasks, then **stop and wait for the user's review** before `/speckit-implement` or writing any implementation code.
- If a mandatory spec-kit hook fails (for example `speckit.git.feature` cannot create the branch), stop before generating any spec artifacts and report the failure. Do not write `specs/` files on the wrong branch.
- Codex's sandbox cannot write a worktree's git metadata, so its `speckit.git.feature` hook fails in a worktree. When delegating spec-kit to Codex, the coordinator creates the worktree on the feature branch first (`git worktree add -b NNN-slug .worktrees/NNN-slug origin/main`, where NNN is the next number in `specs/`) and tells Codex to run the hook with `GIT_BRANCH_NAME=NNN-slug` and `--allow-existing-branch`. The hook then needs no git writes.
