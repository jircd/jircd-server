# irctest comparison (T063)

Run on 2026-10-26 after the final lifecycle and fault behavior, with the harness frozen in [T002](../pr-100/irctest-environment.md). Before the runs the harness matched the freeze: HEAD `76b91582`, `git diff` sha256 `d78cc79c…9b`. Nothing in the harness was modified.

| Item | Value |
| --- | --- |
| Baseline | main `3b7d66d` (#108), worktree `.worktrees/014-baseline`, `:jircd-server:installDist` |
| Candidate | `014-capability-faults` working tree, `:jircd-server:installDist` |
| JDK | Temurin 25.0.4+7 (`JIRCD_JAVA_HOME`) for both |
| Command | `.venv/bin/python -m pytest -p no:cacheprovider --controller irctest.controllers.jircd -m 'not deprecated and not Ergo and not Sable and not services' --timeout=60 --timeout-method=signal -q -rfEs --junitxml=<per-revision>` |
| Order | Baseline, then candidate, one at a time; nothing else heavy running |

An earlier pair from the same day was discarded: the machine was suspended while the candidate run was in progress, so the comparison was repeated from scratch.

## Results

| Revision | Passed | Skipped | Failed | Deselected | Time |
| --- | --- | --- | --- | --- | --- |
| Baseline (main) | 315 | 155 | 4 | 186 | 3m54s |
| Candidate | 315 | 155 | 4 | 186 | 3m59s |

A case-level comparison of the two JUnit XML reports (classname + test name → pass/fail/skip) finds **no differences**: no new failures, no new skips, no newly passing cases. The per-case lists are byte-identical (sha256 `3f1158da…3de8`): [raw/irctest-baseline-cases.tsv](raw/irctest-baseline-cases.tsv), [raw/irctest-candidate-cases.tsv](raw/irctest-candidate-cases.tsv).

## Baseline failures (unchanged, unrelated)

These are the same four as in #101's [T039](../pr-101/irctest.md), all in `irctest/server_tests/cap.py::CapTestCase`: `testReqOne`, `testReqOneThenOne` and `testReqPostRegistration` (`CAP REQ :multi-prefix` gets `NAK`), and `testReqTwo` (`multi-prefix userhost-in-names`). The frozen controller's config doesn't enable multi-prefix, and userhost-in-names isn't implemented.

## Conformance tradeoff

Output captured before a disable may follow the `DEL`, and a fault's withdrawal is asynchronous. No irctest case exercises a runtime disable or a provider fault, so neither shows up here.

## Leftover servers

Both revisions leave the same thing after pytest exits: six jircd processes, all started within the first two seconds of the session, reparented to init. That is harness behavior at session start, identical on both. All six exited on `SIGTERM` after each run; no server was left running.
