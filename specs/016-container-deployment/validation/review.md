# Review dispositions

Findings from the independent review and the SonarCloud branch analysis of the spec 016 MVP, with what was done. Summaries only; the red/green evidence is in `tests.md` under "Review round 1".

## Review round 1

| # | Finding | Disposition |
|---|---|---|
| 1 | `LoggingSection` echoed raw YAML values, bypassing the loader's secret redaction | **Fixed.** Errors now go through `ConfigurationLoader.redactSecrets`. Test: a nested `hashedPassword`/`keystorePassword` under `logging.console` and `logging.file` never appears in the message |
| 2 | `COPY . .` could copy `deploy/` and private keys into the build context | **Fixed.** `.dockerignore` excludes `deploy/` and `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.jks`, except under `src/test`. The sample `deploy/container/jircd.yaml` now tells operators to keep their configuration and keys in their own directory (for example `/srv/jircd`) |
| 3 | The 8 s shutdown deadline started only after the lifecycle monitor was acquired | **Fixed.** The deadline starts when `shutdown` is entered. The monitor is a `ReentrantLock`; shutdown tries it without waiting, drains connections first if a slow `start()` holds it, then waits only for the remaining budget to close the listeners. `start()` returns without binding if shutdown already began |
| 4 | Reload listeners run outside the reload lock, so overlapping reloads could apply logging out of order | **Fixed.** The logging listener applies `reloader.current().logging()` inside its own lock (reason documented on `applyCurrentLogging`), so whichever apply runs last applies the latest published value. Test with latches holds the first apply while a second reload publishes |
| 5 | `drain` snapshotted `liveSessions` once: connections not yet published, or without a writer, got no `ERROR` | **Fixed.** `drain` records the shutdown reason and deadline before it snapshots; a connection that attaches its writer afterwards closes itself the same way (once, via a `gracefullyClosed` set). `drain` waits for the connection threads, not only the closers. Deterministic test: a connection held in the accepted-socket customizer while shutdown starts |
| 6 | `awaitTeardown` closed the socket before the writer finished | **Fixed.** It waits (bounded) for the writer first. A second cause was found while doing this: `readCommands` closed the socket through try-with-resources on its input stream when the read loop ended. That close is removed. Test: a line sent while the final `ERROR` is blocked behind a backlog |
| 7 | `SessionWriter.close` interrupted after 2 s, so a longer shutdown truncated and still reported 0 forced | **Fixed.** `extendDrainUntil(deadline)` carries the shutdown deadline into the drain wait; a writer that is interrupted while still alive reports it (`setOnDrainTruncated`), and `drain` counts those connections together with the ones still running at the deadline. Tests: a 2.5 s drain delivers the `ERROR`; a 3 s budget with a client that never reads reports 1 forced |
| 8 | Claims that the appender switch never loses or doubles a line | **Corrected, no redesign.** `LoggingSettingsApplier` javadoc, `contracts/logging-config.md`, `data-model.md`, `research.md` and the spec edge case now say that one event in flight at the instant of the switch may reach only the old destination, and that events during the overlap reach both files |
| 9 | Under `-Dlogback.configurationFile` the YAML `logging` section was still validated | **Fixed.** The section is ignored and not validated (a non-mapping is not rejected either, since nothing reads it); the contract says so. Tests through the loader and through a running application |
| 10 | FR-010: invalid YAML and similar errors did not name the configuration file | **Fixed.** `ConfigurationException.messageNaming(path)` appends `(configuration file: <path>)` unless the message already carries the path; used by `main`'s `Refusing to start:` line, the SIGHUP failure and `REHASH`. `ContainerFailureTest` and `ContainerSignalTest` assertions updated |

## Sonar (branch analysis)

| Rule | Where | Disposition |
|---|---|---|
| S2925 | `GracefulShutdownTest`, `LoggingConfigurationTest`, `ContainerContractTest`, `ContainerSignalTest` | **Fixed.** A `Polling.until` helper waits on a timed latch against a deadline; the fixed 500 ms pause now waits for the server's output to back up behind the client |
| S6126 | `GracefulShutdownTest`, `ContainerFailureTest`, `ContainerSignalTest` | **Fixed.** Text blocks |
| S3415 | `LoggingSettingsApplierTest` | **Fixed.** The computed size is in `assertThat`, the expected number in the matcher |
| S7467 | `ConnectionHandler`, `ConfigurationReloaderListenerTest` | **Fixed.** Unused catch parameters are `_` |
| S1191 | `ShutdownSignalHandler` | **Kept with `// NOSONAR S1191`.** The JDK has no public signal API; `SighupReloadHandler` uses the same class |

## Review round 2

Inputs: the SonarCloud quality gate on the round-1 code (failed, new reliability rating B), the coordinator's check, and a Codex re-review. All accepted.

| # | Finding | Disposition |
|---|---|---|
| A1 | S3077 (bug, gate-failing): `volatile DrainState` in `ConnectionHandler` | **Fixed.** `AtomicReference<DrainState>` |
| A2 | S6126: YAML string concatenation in `JircdServerApplicationShutdownTest`, `SighupReloadHandlerTest` | **Fixed.** Text blocks |
| A3 | S5838: `toString()` then `isEqualTo` in `LoggingReloadOrderTest` | **Fixed.** `hasToString` |
| A4 | `ShutdownSignalHandler` NOSONAR comment wrapped onto a stray line by the formatter | **Fixed.** One line: `// NOSONAR S1191: the JDK has no public signal API` |
| B5 | The shutdown `ERROR` was not always the last line: a reply from a command already being dispatched could be queued after it | **Fixed.** `SessionWriter.enqueueFinal` queues the line and seals the writer under the enqueue lock, so later enqueues are dropped. Only the shutdown closer uses it; KILL, QUIT and SendQ keep their own paths. The flood tests' strict "ERROR is the last line" assertions are back |
| C6 | `start()` could reopen admission after shutdown closed it | **Fixed.** `ConnectionHandler.closeAdmission()` is irreversible (`startAccepting()` then returns false); shutdown and `drain` use it. `start()` stops if `startAccepting()` refuses, and closes the listeners it opened if shutdown began while they came up. Two deterministic tests pause `start()` at each point through a start-phase seam |
| C7 | `LoggingSection` interpolated rejected keys unredacted, and a null key threw NPE | **Fixed.** Key type is checked first; a non-string key (null, or a YAML complex key) is rejected as a `ConfigurationException` with the key redacted. Tests for a complex key holding `hashedPassword` and for a null key |
| C8 | A writer that closed itself after a write failure (client reset) was counted as forced | **Fixed.** `close()` reports truncation only when its own wait timed out; the writer thread closing itself never waits. Unit test, plus an integration test that resets the connection while shutdown is draining |
| C9 | Docs still promised no dropped events across a logging switch | **Corrected, docs only.** `LoggingSettingsApplier` javadoc, `spec.md`, `contracts/logging-config.md`, `data-model.md` and `research.md` now say an event logged at the instant of a switch can be lost or reach only one destination |
| C10 | The overlap tests relied on client-side heuristics (unread bytes stopped growing, `isAccepting()`), so they could pass without the intended overlap | **Fixed.** Test seams on `ConnectionHandler` (session output wrapper, read-loop-end hook, graceful-close hook, `isDraining()`) let each test hold the writer inside a write and wait for server state (`CLOSING`, read loop ended, drain started). Each test was checked to fail with its fix reverted (`tests.md`, "Review round 2") |
| C11 | `Polling.pause` used one `parkNanos`, which may return early | **Fixed.** Parks in a loop against a monotonic deadline (same in `LoggingReloadOrderTest`) |

## Review round 3

The Codex re-review of round 2 found no production issues. Two low test findings, both accepted:

| # | Finding | Disposition |
|---|---|---|
| 1 | The integration cut-off test can pass with the truncation callback removed, because `drain()` may count the thread as unfinished on its own; round 2's mutation removed two things at once | **Fixed.** New `SessionWriter`-level unit test: one report after a timed-out drain, none on the second close. The existing unit test covers a writer that closes itself after a write failure. With only the callback invocation removed, the new test fails |
| 2 | `SessionWriterCloseTest` final-line test waited indefinitely for the first write | **Fixed.** Timed, asserted latch waits (also in the older test in the same class) |

## Not done

Nothing is left unfixed. A session whose writer is attached after `drain` has taken its snapshot is covered by the same code path as the accepted-but-not-serving test, not by a dedicated test: there is no seam between publishing a session and attaching its writer.

## PR #129 CI round

The CI failure was a test ordering problem, not a production defect: `everyClientIsToldWhyBeforeItsConnectionEnds` shut down while 3 connections were still in the kernel accept queue, and the OS reset them. Decision: do not add a lingering close to the teardown paths (it would change every disconnect path for a case the server never saw). The test waits for server-side acceptance, and the edge is documented in `contracts/container-contract.md` (Signals) and `spec.md` (Edge Cases).

## US4 JVM-only slice (2026-11-07)

Codex review of the JVM CI slice against the checklist: 1 finding.

| # | Finding | Disposition |
|---|---|---|
| 1 | Medium: the existing `release` job in `release.yml` used tag-pinned actions (`actions/checkout@v7`, `actions/setup-java@v6`) | **Fixed.** Pinned by full commit SHA with version comments, the same SHAs as the new jobs |

## PR #139 review round 1

| # | Finding | Disposition |
|---|---|---|
| 1 | CodeRabbit: the retag step treated an empty or failed `git ls-remote` tip as "main moved on" and skipped silently | **Fixed.** The step fails with an `::error::` message when the lookup fails or returns an empty tip; a non-empty tip that differs still skips with a notice |
| 2 | CodeRabbit: the `release` job's checkout kept credentials | **Fixed.** `persist-credentials: false`; the job only uses `GH_TOKEN` for `gh release create` |
| 3 | CodeRabbit: Dependabot ignores tags in comments and treats digest-only references as latest | **Fixed.** Both `FROM` lines carry the tag before the digest (`eclipse-temurin:25-jdk@sha256:…`, `gcr.io/distroless/java25-debian13:nonroot@sha256:…`); digests unchanged and still equal to the current tag digests |
| 4 | CodeRabbit: README does not say `jvm` and `latest` can lag | **Fixed.** One sentence under the tag table |
| 5 | CodeRabbit: `packages: write` and `contents: write` lack a reason | **Fixed.** Trailing comments in `container.yml` and `release.yml` |
| 6 | CodeRabbit: a release can exist without an image when `smoke-jvm` fails | **Declined.** A release must not depend on an image variant's smoke test, and US4 says a failing variant is simply not published |
| 7 | Sourcery | **Skipped.** Weekly review budget used |
