# irctest results: issue #117 (T020/T021)

Run by the coordinator on 2026-10-27; this report records supplied artifacts without rerunning Gradle or irctest. Candidate: `015-userhost-in-names`, uncommitted implementation on documentation HEAD `4c0ce89`. Baseline server: main `a2a12ad`.

## Harness and command

The coordinator provisioned detached harness worktree `/Users/gcastro/Projects/jircd/irctest/.worktrees/uhnames` at irctest HEAD `76b91582`. The shared frozen checkout and its user modifications were left untouched. The [spec-014 freeze](../../014-capability-plugin-spi/validation/pr-100/irctest-environment.md) remains historical evidence, not the revised harness identity.

The revised harness patch is the old frozen diff (`d78cc79c…`) plus `multi-prefix: enabled` and `userhost-in-names: enabled` in TEMPLATE_CONFIG and `Capabilities.MULTI_PREFIX` in the controller's declarations. Revised patch SHA-256: `3bf01a924d0e9efb29566486a6153dd9454599b637b3be490f0bce54a93d7a29`. irctest has no `Capabilities` enum entry for userhost-in-names; it cannot be declared through that enum. The CAP cases request it by its wire name.

The coordinator retained the original wrapper and reports outside the repository. The wrapper selects `irctest/server_tests/` by default, sets `JIRCD_LAUNCHER=<server-worktree>/jircd-server/build/install/jircd-server/bin/jircd-server`, and uses `JIRCD_JAVA_HOME=/opt/homebrew/Cellar/sdkman-cli/5.19.0/libexec/candidates/java/25.0.4-tem` (Temurin JDK 25.0.4).

Run from the detached harness worktree after setting `JIRCD_LAUNCHER` to the selected server's installDist launcher and `JIRCD_JAVA_HOME` as above. Store generated evidence outside the repository:

```sh
RESULTS_DIR=/private/tmp/jircd-117-irctest
mkdir -p "$RESULTS_DIR"
.venv/bin/python -m pytest -p no:cacheprovider --controller irctest.controllers.jircd \
  -m 'not deprecated and not Ergo and not Sable and not services' --timeout=60 --timeout-method=signal \
  -q -rfEs --junitxml="$RESULTS_DIR/server.xml" irctest/server_tests/ \
  > "$RESULTS_DIR/server.log" 2>&1
```

For the targeted run, replace `irctest/server_tests/` with these three selectors:

```text
irctest/server_tests/cap.py::CapTestCase::testReqTwo
irctest/server_tests/cap.py::CapTestCase::testReqOneThenOne
irctest/server_tests/cap.py::CapTestCase::testReqPostRegistration
```

Use separate external output names for baseline/candidate and targeted/full runs. No reproduction command was executed while recording these results.

## Baseline compatibility exception

An attempted targeted run of main against the identical revised harness cannot reach the tests: the server exits at startup with return code 1 because main rejects the unknown `userhost-in-names` configuration key. All three cases report `ProcessStopped`. These startup failures are separate from the baseline's three capability-negotiation failures below.

The usable comparison therefore takes the coordinator's earlier same-day main run with multi-prefix enabled and declared, omitting only the `userhost-in-names: enabled` config line. Its controller diff otherwise matches the candidate harness. This is a documented one-line harness difference, **not an identical-harness comparison**. The baseline comparison filters the broader earlier run to the 352 server cases; the original case tables remain outside the repository. The earlier broader pass count must not be compared directly with this report's server-only totals.

## Results

| Run | Passed | Skipped | Failed | Notes |
| --- | --- | --- | --- | --- |
| Main, identical revised harness, three targets | 0 | 0 | 3 | Startup failure; unknown capability configuration key |
| Main, multi-prefix-only harness, server cases | 196 | 153 | 3 | Usable baseline; three CAP negotiation failures |
| Candidate, revised harness, three targets | 3 | 0 | 0 | All three named targets passed |
| Candidate, revised harness, full server selection | 199 | 153 | 0 | 352 cases; 186 deselected; JUnit time 230.013 s |

Case-level comparison (classname + test name → status) verifies the same 352 server cases on both sides. Exactly these three change from fail to pass:

- `irctest.server_tests.cap.CapTestCase::testReqTwo`
- `irctest.server_tests.cap.CapTestCase::testReqOneThenOne`
- `irctest.server_tests.cap.CapTestCase::testReqPostRegistration`

There are no other pass/fail/skip changes, no new failures and no new skips. Recording-time checks independently verified both case-table counts, the three-case delta, the baseline server subset and candidate statuses against the supplied full JUnit XML. The source evidence remains outside the repository.

## Evidence policy and cleanup

The coordinator retained the original harness patch, command wrapper, case tables, XML and logs outside the repository in session scratch storage. Only summarized results, method and reproduction commands are recorded here; no raw validation files from this feature are added to the project.

The coordinator reports stopping six leftover run-owned servers by explicit PID, with none remaining. No process cleanup or new test execution was performed while writing this report. No commits.
