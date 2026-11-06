# Test evidence (spec 016)

Summaries only. Commands run from the worktree root with Temurin 25.0.5 (see [environment.md](environment.md)).

## US1: red before implementation (T015)

Each red ran against a minimal stub, so failures are behavioural and not compile errors. The stubs: `ServerConfiguration.Logging` always `DEFAULT`, `ConfigurationLoader.load(Path)` using the old `FileInputStream`, and no-op `BootstrapLogbackConfigurator` and `LoggingSettingsApplier`.

| Test class | Command | Result |
|---|---|---|
| `LoggingConfigurationLoaderTest` (T009) | `./gradlew :jircd-core:test --tests '*LoggingConfigurationLoaderTest'` | 27 run, **24 failed**. The 3 that pass are the default cases, which the stub already satisfies. Failures: no validation messages, file never parsed, file errors raised as `FileNotFoundException` instead of the named messages |
| `LoggingSettingsApplierTest` (T010) | `./gradlew :jircd-server:test --tests 'net.jircd.server.logging.*'` | 11 run, **10 failed**: no bootstrap console, no file appender, no external step-aside. `anUnchangedFileKeepsItsAppender` passes vacuously, because the stub never adds anything |
| `LoggingConfigurationTest` (T011) | `./gradlew :jircd-integration-tests:test --tests '*LoggingConfigurationTest'` | 3 run, **3 failed**. The old `logback.xml` still adds a FILE appender, and the configured file never receives lines |
| `ConfigurationFileErrorTest` (T012) | same task, `--tests '*ConfigurationFileErrorTest'` | 3 run, **3 failed**: `FileNotFoundException` instead of `ConfigurationException`, and the invalid `logging` section is accepted |
| `ContainerContractTest`, `ContainerFailureTest` (T013–T014) | compile only (`testClasses`) | Compile cleanly. They need the image, so they run at T022 |

Dependency verification for Testcontainers 2.0.5: nine artifacts added. Each is trusted by its own signing key, three of which were added to `gradle/verification-keyring.keys`. Running `--write-verification-metadata pgp,sha256 --export-keys` would also have started trusting keys for unrelated build plugins (Spotless, Saxon, dom4j, JSpecify) whose keys can now be downloaded. Those changes were discarded to keep this change's trust surface to its own dependencies.

## US1: green (T016–T022)

| Check | Result |
|---|---|
| `./gradlew test` (all modules, after T019) | **pass**. No `logs/` directory created anywhere, and `LogCapture`-based tests unaffected by removing `logback.xml` |
| `LoggingConfigurationLoaderTest`, `LoggingSettingsApplierTest`, `LoggingConfigurationTest`, `ConfigurationFileErrorTest` | **pass** |
| `docker build -t jircd:jvm .` (arm64, Docker Desktop) | builds; image 318 MB |
| `containerTest -Djircd.image=jircd:jvm` | **9 tests, 0 failed**: `ContainerContractTest` (4), `ContainerFailureTest` (5) |

Fixes made while turning the suite green:

- The applier test's private `LoggerContext` needed an MDC adapter, which the SLF4J provider installs in production. Without it every append failed, and the file stayed empty.
- The "file cannot be opened" case uses a regular file in place of the directory. Logback re-creates a missing parent directory, so a removed directory doesn't fail.
- `docker top` needs the `pid` column (`-o pid,uid`).
- **Docker Desktop doesn't enforce host file permissions on bind mounts**, so a host `chmod 600` config was readable inside the container. The unreadable-config test now copies a root-owned mode-600 `jircd.yaml` onto a fresh volume, which the container user really can't read on every platform. The same limitation means an operator's host permissions behave differently on Docker Desktop and on Linux. The README (T047) should say so.

## US2: red, then green (T027, T033)

Red, against stubs: `addReloadListener` was a no-op, and `shutdown` called the old `stop()`.

| Test class | Red result |
|---|---|
| `ConfigurationReloaderListenerTest` (T023) | 5 run, **4 failed**. The rejected-reload case passes vacuously |
| `LoggingConfigurationTest` reload cases (T024) | **3 of 4 failed**. The rejected-reload case passes, because validation already rejects it |
| `GracefulShutdownTest` (T025) | **2 of 4 failed**: no `ERROR` line, no force-close count. The no-op and before-start cases pass with the stub |
| `ContainerSignalTest` (T026) | **4 of 4 failed** on the old image: exit 143, no `ERROR`, logging not reloaded |

Green:

| Check | Result |
|---|---|
| `./gradlew build` (tests, Spotless, SpotBugs, PMD, coverage floor) | **pass**. Aggregate coverage is 96.1% line and 89.6% branch (floor 85% / 75%) |
| `containerTest -Djircd.image=jircd:jvm` | **13 tests, 0 failed**, in three consecutive runs |
| SC-003: `docker stop` with 100 registered clients | 451 / 456 / 454 ms, exit 0, and every client's last line is `ERROR :Closing Link: <host> (Server shutting down)` |

Findings while turning US2 green:

- **Docker Desktop host bind mounts can't be relied on for a config change made just after a read.** An in-place rewrite made about 150 ms after the server read the file was read back as the old contents. An atomic rename then made the reload report "Configuration file not found", from a stale directory entry. A manual edit seconds later behaves correctly. Linux bind mounts don't do this. The signal tests therefore keep `/etc/jircd` on a Docker volume and replace files through the Docker API, while the read-only bind-mount layout stays covered by `ContainerContractTest`. The earlier invalid-config HUP test had passed for the wrong reason, the missing file; it now asserts the validation message. The README (T047) should advise Docker Desktop users to wait a moment after saving before sending HUP.
- PMD `CloseResource` on the borrowed session writer in `ConnectionHandler.closeGracefully`, and on test clients closed in `finally`: both suppressed with a comment, following the repo's existing pattern.
- SpotBugs: `CreateContainerCmd.getHostConfig()` may be null. A null-safe `readOnlyRoot` helper fixes it. The nested `VolumeConfigured` container delegates `equals`/`hashCode` to its superclass.
- The container INT test used an 11-character nickname, over the 9-character default limit, so registration never completed. Renamed.

## Review round 1

Fixes for the independent review and the SonarCloud branch analysis (dispositions in `review.md`). Tests were written first; the red column is what ran against the code before the fix.

| Item | Test | Red | Green |
|---|---|---|---|
| 1 | `LoggingConfigurationLoaderTest.aNestedCredentialIsNeverEchoedInAnError` (console, file) | 2 of 2 failed: the credential was in the message | pass |
| 3 | `JircdServerApplicationShutdownTest.aSlowStartDoesNotPushShutdownPastItsBudget` | failed with the old blocking acquisition: shutdown waited for the held lifecycle | pass |
| 4 | `LoggingReloadOrderTest.aReloadThatAppliesLateStillAppliesTheLatestPublishedLogging` | failed with the old listener (applied the older value last) | pass |
| 5 | `GracefulShutdownTest.aConnectionAcceptedButNotYetServedStillGetsTheShutdownError` | failed: no `ERROR` reached the client | pass |
| 6 | `GracefulShutdownTest.aLineSentWhileTheFinalErrorIsStillDrainingDoesNotCutItOff` | failed: the `ERROR` never arrived | pass |
| 7 | `GracefulShutdownTest.aDrainLongerThanTwoSecondsStillDeliversTheErrorWithinTheBudget`, `aWriterCutOffAtTheDeadlineIsCountedEvenWhenItsThreadThenFinishes` | the first failed with the deadline hand-off removed; the second failed (0 forced instead of 1) before the truncation count existed | pass |
| 9 | `LoggingConfigurationLoaderTest.underAnExternalLogbackConfigurationTheSectionIsNotChecked`, `LoggingConfigurationTest.underAnExternalLogbackConfigurationTheServerStartsAndLeavesTheSectionAlone` | both failed: the missing directory was rejected | pass |
| 10 | `ConfigurationExceptionTest` (2), `SighupReloadHandlerTest`, `Story6RehashTest` path assertion, `ContainerFailureTest` and `ContainerSignalTest` path assertions | written against the new message; the helper did not exist, so they did not compile before the change | pass |

Items 2 and 8 are configuration and documentation changes with no behaviour to test. The Sonar items are test and style changes covered by the build.

Findings while writing the tests:

- Item 6 had a second cause. `ConnectionHandler.readCommands` read through a try-with-resources on the socket's input stream, and closing that stream closes the socket. The read loop ending therefore cut the writer off before `awaitTeardown` ran. The fix for 6 needs both changes, and the test only goes green with both.
- The first version of the flood tests did not block the server's writer at all: loopback buffers swallowed the whole output, so they passed against the old code. They now set a 1 KB receive buffer before connecting (the only point the kernel honours it) and wait until the bytes waiting in the client no longer grow. A line from the same client closes its own backlog with a reset if input is left unread, so the flood is sized to be fully read before shutdown starts.
- In those tests the `ERROR` is not always the last line: replies to commands already received can follow it, because the session is claimed `CLOSING` just after the `ERROR` is queued. The two flood tests therefore assert the `ERROR` is delivered, not that it is last. The idle-client test keeps the stricter assertion.

Green:

| Check | Result |
|---|---|
| `./gradlew build` (tests, Spotless, SpotBugs, PMD, coverage floor) | **pass**, exit 0 |
| `docker build -t jircd:jvm .` | built |
| `./gradlew :jircd-integration-tests:containerTest -Djircd.image=jircd:jvm` | **13 tests, 0 failed** |

The first full build of the round failed SpotBugs on the tests (`RV_RETURN_VALUE_IGNORED` on a latch `await` used as a pause); the polling helpers now use `LockSupport.parkNanos`.

## Review round 2

Fixes for the SonarCloud gate (failed on round 1: new reliability rating B), the coordinator's check and the Codex re-review (dispositions in `review.md`).

### Red, then green

| Item | Test | Red | Green |
|---|---|---|---|
| C7 | `LoggingConfigurationLoaderTest.aComplexKeyIsRejectedWithoutEchoingACredentialInside`, `aNullKeyIsAConfigurationErrorNotACrash` | 2 of 2 failed: the credential was in the message; NPE instead of `ConfigurationException` | pass |
| C8 | `SessionWriterCloseTest.aWriterThatClosesItselfAfterAWriteFailureIsNotReportedAsTruncated` | failed: truncation reported once | pass |
| B5 | `SessionWriterCloseTest.aFinalLineIsTheLastOneWrittenWhateverIsQueuedAfterIt` | failed against a stub `enqueueFinal` that only queued the line | pass |
| C6, C8, C10 | `JircdServerApplicationShutdownTest` (two start-phase tests), `GracefulShutdownTest.aClientResetDuringShutdownIsNotCountedAsForced` and the reworked overlap tests | see the mutation check below | pass |

### Mutation check (C10)

The overlap tests now hold the session's writer inside a write through a test seam on `ConnectionHandler`, and wait for server state (session `CLOSING`, read loop ended, `isDraining()`, a start phase) instead of client-side heuristics. Each test was run with its fix reverted in the source, then the source was restored:

| Fix reverted | Test | Result with the fix reverted |
|---|---|---|
| 5: a connection that attaches its writer after the drain began closes itself | `aConnectionAcceptedButNotYetServedStillGetsTheShutdownError` | **failed**: no line at all reached the client |
| 6: teardown waits for the writer, and the read loop no longer closes the socket | `aLineSentWhileTheFinalErrorIsStillDrainingDoesNotCutItOff` | **failed**: last line was not the `ERROR` |
| 7: shutdown deadline handed to the writer drain | `aDrainLongerThanTwoSecondsStillDeliversTheErrorWithinTheBudget` | **failed**: last line was not the `ERROR` |
| 7: deadline hand-off and truncation count, together | `aWriterCutOffAtTheDeadlineIsCountedEvenWhenItsThreadThenFinishes` | **failed**: 0 forced instead of 1 |
| C8: only a timed-out drain reports truncation | `aClientResetDuringShutdownIsNotCountedAsForced` | **failed**: 1 forced instead of 0 |
| C8 (unit) | `SessionWriterCloseTest.aWriterThatClosesItselfAfterAWriteFailureIsNotReportedAsTruncated` | **failed** |
| B5: the final line seals the writer | `SessionWriterCloseTest.aFinalLineIsTheLastOneWrittenWhateverIsQueuedAfterIt` | **failed** |
| C6: admission closure irreversible, and the start-time rollback | `aStartPausedAfterItsShutdownCheckCannotReopenAdmission` | **failed**: accepting was back on |
| C6: listeners opened after shutdown began are closed by `start()` | `aStartPausedBeforeOpeningListenersClosesThemOnceShutdownHasBegun` | **failed**: 1 listener left open |

Findings from the check:

- The first version of the cut-off test still passed with fix 7 reverted. Its connection thread stayed blocked in `read` until the liveness tick, so it was counted as unfinished whether or not truncation was reported. The test now makes the read loop end (a line sent after `CLOSING`), so the thread lives only as long as the writer's drain, and the mutation fails.
- The write gate must be installed before the client connects. It wraps sessions created after it, so installing it later raced the server creating the session (one run saw no blocked writer).
- The flood tests and their client-side helpers (a 1 KB receive buffer, waiting for unread bytes to stop growing) are gone. Every overlap test now asserts again that the `ERROR` is the client's last line.

### Green

| Check | Result |
|---|---|
| `./gradlew build` (tests, Spotless, SpotBugs, PMD, coverage floor) | **pass**, exit 0 |
| `docker build -t jircd:jvm .` then `containerTest -Djircd.image=jircd:jvm` | **13 tests, 0 failed**, on the final code |
| Local Sonar branch analysis (`build sonar --no-parallel`, gate wait, SCM disabled, daemon off) | **gate OK**: new reliability, security and maintainability A, new coverage 91.6%, duplication 0.0%, hotspots reviewed 100% |
| Open SonarCloud issues on branch `016-container-deployment` | **0** |

The first Sonar run of this round failed the gate again: the test-seam fields added this round were `volatile` lambdas (S3077 ×4), and one catch parameter was unused (S7467). They are now `AtomicReference`s and `_`, and the second run passed.

## Review round 3

Test-only round; the Codex re-review found no production issues (dispositions in `review.md`).

| Test | What it checks | Mutation | Result with the mutation |
|---|---|---|---|
| `SessionWriterCloseTest.aDrainCutOffAtItsDeadlineIsReportedExactlyOnce` (new) | A writer held inside a write, closed with a 200 ms drain deadline: `onDrainTruncated` fires exactly once, and the later second `close()` does not report it again | Only the `onDrainTruncated` invocation in `SessionWriter.close()` removed; deadline hand-off and drain counting left intact | **failed**: 0 reports instead of 1. The other four tests in the class passed |
| `SessionWriterCloseTest.aWriterThatClosesItselfAfterAWriteFailureIsNotReportedAsTruncated` (round 2) | No report when the writer closes itself after a write failure | (round 2: the callback made unconditional) | failed in round 2 |

This closes a gap in round 2's mutation table: there, `GracefulShutdownTest.aWriterCutOffAtTheDeadlineIsCountedEvenWhenItsThreadThenFinishes` was checked with the deadline hand-off and the callback removed together. `drain()` can count the same connection as unfinished on its own, so that test does not isolate the callback. The unit test above does.

The first-write waits in `SessionWriterCloseTest` are now timed (10 s) and asserted, so a regression fails instead of hanging.

## PR #129 CI round

CI run 41699949596 (Linux only) failed `GracefulShutdownTest.everyClientIsToldWhyBeforeItsConnectionEnds` at the assertion that a client's read-to-end list is not empty. The CI report shows the server logged 100 `connection-event=connected` lines (all 100 later `disconnected reason=Server shutting down`), but the test opens 103 connections. The 3 unregistered clients (they send only `NICK`, then shutdown starts at once) were never accepted: they sat in the kernel accept queue and the listener close reset them. This is a test ordering bug plus a documented edge, not a teardown bug, so no lingering close was added.

Reproduction attempts in a Linux JDK 25 container (`eclipse-temurin:25-jdk`, linux/arm64 under Docker Desktop): 10 runs unlimited and 15 runs with `--cpus=2`, all 25 passed (9 tests each). The race did not show there, and no reliable deterministic reproduction exists (the accept loop is not held by any test seam), so the CI evidence above is the record.

Fix: the test now waits, with `Polling.until` and a 10 s bound, until `ConnectionHandler.unregisteredConnectionCount()` is 3 before it triggers shutdown. The 100 registered clients were already proven accepted by their welcome. The other shutdown tests (in-process and container) wait for a welcome or for the accept hook before they stop the server.

## Review round 4

Early TERM is covered in-process only. `main` installs the signal handlers before it loads the configuration, and the server logs nothing between that point and the "Plaintext listener started" line after the bind, so there is no observable marker a container test could wait for before sending TERM. Sending it right after container start would race the JVM's startup and could land before or after the handlers exist (or after the bind), so it would not pin the behaviour. `ShutdownSignalHandlerTest.aSignalBeforeTheServerExistsStillExitsZero` covers the signal-before-attach case deterministically. T026 was reworded to say so; no container test was added.
