# irctest comparison (T039)

Run by the coordinator on 2026-10-23 with the harness frozen in [T002](../pr-100/irctest-environment.md). Before the runs, the harness matched every recorded value: HEAD `76b91582`, sha256 of `irctest/controllers/jircd.py` `a9c9ba8d…94`, `requirements.txt` `ac78ec82…3d`, `pyproject.toml` `c08f9d76…a2`, `git diff` `d78cc79c…9b`. Nothing in the harness was modified.

| Item | Value |
| --- | --- |
| Baseline | main `fc3aeab` (after #104), detached worktree, `:jircd-server:installDist` |
| Candidate | this branch's working tree after review round 2, `:jircd-server:installDist` |
| JDK | Temurin 25.0.4+7 (`JIRCD_JAVA_HOME`) for both |
| Command | `.venv/bin/python -m pytest -p no:cacheprovider --controller irctest.controllers.jircd -m 'not deprecated and not Ergo and not Sable and not services' --timeout=60 --timeout-method=signal -q -rfEs --junitxml=<per-revision>` |

## Results

| Revision | Passed | Skipped | Failed | Deselected | Time |
| --- | --- | --- | --- | --- | --- |
| Baseline (main) | 315 | 155 | 4 | 186 | 3m37s |
| Candidate | 315 | 155 | 4 | 186 | 3m33s |

A case-level comparison of the two JUnit XML reports (classname + test name → pass/fail/skip) finds **no differences**: no new failures, no new skips, no newly passing cases. The candidate was also run once on the round-1 code, with the same result.

## Baseline failures (unchanged, unrelated)

All four are in `irctest/server_tests/cap.py::CapTestCase` and fail identically on both revisions:

- `testReqOne`, `testReqOneThenOne`, `testReqPostRegistration`: `CAP REQ :multi-prefix` gets `NAK`.
- `testReqTwo`: `CAP REQ :multi-prefix userhost-in-names` gets `NAK`.

multi-prefix was added in #99, after the frozen controller patch. The controller's server config doesn't enable it, so the server doesn't offer it. userhost-in-names isn't implemented. Changing the frozen harness is out of scope for this comparison.

## Integration fixture proof

`git diff --name-status origin/main -- jircd-integration-tests` lists no modified or deleted files. The only additions are `CapabilityGroupTest` and `CapabilityProviderAbsenceTest` (see [migrations.md](migrations.md)).
