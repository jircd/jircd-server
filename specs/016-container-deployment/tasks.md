# Tasks: Container Deployment

**Input**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [container contract](contracts/container-contract.md), [logging contract](contracts/logging-config.md), [quickstart.md](quickstart.md).

**Execution scope**: MVP (T001–T033) implemented and verified on 2026-11-03; stopped for the user's review. Nothing committed beyond the spec documents (`004f55e`). Pause for the user's review before every commit.

**Validation evidence policy**: Keep only Markdown summaries and reproduction commands in `specs/016-container-deployment/validation/`. Raw logs, XML, `docker stats` dumps and per-run files stay outside the repository.

**Tests**: Required, tests first (constitution Principle II). Write and run each story's tests before implementing the behaviour, and record red/green evidence in `validation/tests.md`. A compile failure from a missing type is setup evidence, not a behavioural red: add a minimal stub first so the assertions fail.

**Format**: `- [ ] Tnnn [P?] [USn?] Description with file path`. `[P]` marks disjoint work once its dependencies are done. One writer per worktree.

**Environment**: Docker Desktop on the maintainer Mac (engine linux/arm64). Run `containerTest` locally against both variants. Local native images are arm64. The amd64 native image and the performance comparison come from CI.

## Phase 1: Setup (Shared Infrastructure)

- [X] T001 Record the base revision (`d5a38b2` or a newer `origin/main`, justified), JDK, Docker version and engine architecture (`docker version`), and the test commands in `specs/016-container-deployment/validation/environment.md`. Never open `jircd-server/jircd.yaml`.
- [X] T002 Add Testcontainers `2.0.5` (`org.testcontainers:testcontainers`, plus the JUnit integration if used) to `gradle/libs.versions.toml` and as a `testImplementation` of `jircd-integration-tests/build.gradle.kts`.
- [X] T003 In root `build.gradle.kts`:
  - Exclude the tags `container` and `container-load` from `test`, and keep `loadTest` on `load` only.
  - Register `containerTest` (includeTags `container`) and `containerLoadTest` (includeTags `container-load`) with the same source-set wiring as `loadTest`.
  - Pass `-Djircd.image` through to both, and fail with "set -Djircd.image=<image>" when it is missing.
  - Check that `./gradlew :jircd-integration-tests:test` still selects no container tests.
- [X] T004 Regenerate `gradle/verification-metadata.xml` for the new artefacts (`./gradlew --write-verification-metadata sha256 help`), then check that `./gradlew build` passes.
- [X] T005 [P] Create `.dockerignore`. It excludes `.git`, `.worktrees`, `**/build`, `.gradle`, `specs`, `.specify`, `.claude`, `.codegraph`, `logs` and **`jircd-server/jircd.yaml`**, so no local credentials can enter the build context.

## Phase 2: Foundational (Blocking Prerequisites)

- [X] T006 Add `jircd-integration-tests/src/test/java/net/jircd/integration/container/JircdContainer.java`, a Testcontainers wrapper that:
  - starts the image from `jircd.image` with a **read-only root filesystem** (`HostConfig.withReadonlyRootfs(true)`);
  - bind-mounts a per-test temporary directory read-only at `/etc/jircd`;
  - maps 6667 and 6697;
  - waits for the log line `jircd started as`;
  - exposes `plaintextPort()`, `tlsPort()`, `signal(String)` (Docker kill with a signal), `stopWithin(Duration)` (returns the exit code), `exitCode()`, `logs()` and `configuredUser()` (from container inspect).

  It must never retry.
- [X] T007 Add a fixture helper that writes `jircd.yaml`, a self-signed PEM certificate and key, and an Argon2 OPER credential into the temporary mount directory. Reuse the certificate and Argon2 helpers already used by `TestServer` instead of adding new ones. Files are created world-readable so uid 65532 can read them.
- [X] T008 Map FR-001–FR-022 and SC-001–SC-007 to test classes, quickstart steps or CI jobs in `specs/016-container-deployment/validation/coverage-map.md`.

**Checkpoint**: Container test plumbing compiles, and `containerTest` runs with zero tests against any image.

## Phase 3: User Story 1 - Run jircd from a container image (Priority: P1) 🎯 MVP

**Goal**: An operator runs the JVM image with a mounted configuration. Logs go to stdout and nothing is written in the container. The image runs as uid 65532, and startup errors are clear.

**Independent Test**: Build `docker build --target jvm -t jircd:jvm .` and run `containerTest -Djircd.image=jircd:jvm`. Plaintext and TLS clients register and chat. Logs appear only on stdout with a read-only root filesystem. Missing or unreadable configuration produces the exact messages and exit status 1.

### Tests first

- [X] T009 [P] [US1] In `jircd-core/src/test/java/net/jircd/core/config/LoggingConfigurationLoaderTest.java`, test parsing and validation of `logging` exactly as in data-model.md:
  - `console` must be a YAML boolean, default `true`; `file` is optional with no default, and blank strings are rejected.
  - `console: false` with no file is rejected with "logging: console is off and no file is set; the server would produce no log output".
  - A missing directory gives "logging.file directory does not exist: <dir>". A non-writable directory gives "logging.file directory is not writable: <dir>".
  - An existing non-regular or non-writable file is rejected. Unknown keys are rejected. A relative path resolves against the working directory.
- [X] T010 [P] [US1] In `jircd-server/src/test/java/net/jircd/server/logging/LoggingSettingsApplierTest.java`, test against a real `LoggerContext`:
  - The bootstrap console is in place before settings are applied.
  - Each of the three valid combinations is applied correctly.
  - The file appender uses the rotation in data-model.md ("50 MB per file, 30 days, 1 GB total, gzip, archive name `<file>.%d{yyyy-MM-dd}.%i.gz`") and the fixed pattern.
  - When `logback.configurationFile` is set, the applier leaves appenders alone and logs one INFO line.
- [X] T011 [P] [US1] Add `jircd-integration-tests/src/test/java/net/jircd/integration/LoggingConfigurationTest.java`. A server started with `logging.file` in a temporary directory writes connection events to that file and to the console. A server with no `logging` section creates no `logs/` directory in its working directory.
- [X] T012 [P] [US1] Add `jircd-integration-tests/src/test/java/net/jircd/integration/ConfigurationFileErrorTest.java`. It drives `JircdServerApplication` startup the same way `main` does: a missing path gives "Configuration file not found: <path>", and a file with no read permission gives "Configuration file not readable by this user: <path>". Skip the permission case with a recorded reason when running as root.
- [X] T013 [P] [US1] Add `jircd-integration-tests/src/test/java/net/jircd/integration/container/ContainerContractTest.java` (`@Tag("container")`) covering:
  - plaintext and TLS registration, JOIN and PRIVMSG between two clients (Story 1, scenarios 1–2);
  - `logs()` containing the startup, connection and security log lines with no error about writing under a read-only root (scenario 4, SC-007);
  - `configuredUser()` is `65532`, and the running process is not uid 0 (scenario 6);
  - with `logging.file` pointed at an extra writable tmpfs mount, the file receives lines (scenario 5).
- [X] T014 [P] [US1] Add `jircd-integration-tests/src/test/java/net/jircd/integration/container/ContainerFailureTest.java` (`@Tag("container")`). With no mount, an invalid YAML, a mode-600 file owned by another uid, and a missing TLS certificate path, the container exits with status 1 and its single `Refusing to start:` line matches contracts/container-contract.md.
- [X] T015 [US1] Run T009–T012 (behavioural reds after stubs) and record the results in `specs/016-container-deployment/validation/tests.md`. T013–T014 can't run red until the image exists, so record their compile-ready state.

### Implementation

- [X] T016 [US1] Add the `Logging(boolean console, Path file)` record to `jircd-core/src/main/java/net/jircd/core/config/ServerConfiguration.java`, documented at the definition. Parse and validate it in `jircd-core/src/main/java/net/jircd/core/config/ConfigurationLoader.java` per data-model.md, so that T009 passes.
- [X] T017 [US1] Logback bootstrap:
  - Add `jircd-server/src/main/java/net/jircd/server/logging/BootstrapLogbackConfigurator.java`, a logback `Configurator`. When `logback.configurationFile` is set it returns `INVOKE_NEXT_IF_ANY` and records `EXTERNAL`. Otherwise it installs a console appender with the fixed pattern at INFO and returns `DO_NOT_INVOKE_NEXT_IF_ANY`.
  - Register it in `jircd-server/src/main/resources/META-INF/services/ch.qos.logback.classic.spi.Configurator`.
  - Change logback to `implementation` in `jircd-server/build.gradle.kts`.
- [X] T018 [US1] Add `jircd-server/src/main/java/net/jircd/server/logging/LoggingSettingsApplier.java`, implementing the data-model.md transitions:
  - It replaces only the changed appenders; a new file appender is started before it is attached, and the old one is detached and stopped afterwards.
  - If starting the new appender fails, the previous appenders stay and a WARN is logged.
  - In `EXTERNAL` mode it does nothing and logs one INFO line.

  This makes T010 pass.
- [X] T019 [US1] Startup wiring:
  - In `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`, apply the logging settings right after the configuration loads.
  - Open the configuration with `Files.newInputStream`, and map `NoSuchFileException` and `AccessDeniedException` to the R8 messages, keeping the `Refusing to start:` prefix and exit status 1.
  - Delete `jircd-server/src/main/resources/logback.xml`.

  This makes T011 and T012 pass. Check that `LogCapture`-based tests still pass.
- [X] T020 [US1] Create the root `Dockerfile` stages:
  - **`build`**: `FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:…`, running `./gradlew :jircd-server:installDist --no-daemon` with a `~/.gradle` cache mount.
  - **`jvm`**: `gcr.io/distroless/java25-debian13:nonroot@sha256:…`:
    - copy `lib/` to `/opt/jircd/lib`, with `WORKDIR /opt/jircd` and `USER 65532:65532`;
    - `EXPOSE 6667 6697`;
    - `ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-XX:-UsePerfData","-cp","/opt/jircd/lib/*","net.jircd.server.JircdServerApplication"]` and `CMD ["/etc/jircd/jircd.yaml"]`.

  Put a tag comment next to every digest. Make `jvm` the default target, the last stage or one documented with `--target`.
- [X] T021 [P] [US1] Add `deploy/container/jircd.yaml`, a sample configuration: a plaintext listener on 6667, `logging` left at its defaults with the file option commented out, a commented TLS listener using `/etc/jircd/cert.pem` and `/etc/jircd/key.pem`, and a commented OPER block pointing to the Argon2 hash instructions, with `operRequireTls: true` set so OPER passwords never cross the plaintext listener (OPER credentials are enabled only together with a TLS listener, and the sample says so). It must contain no secrets.
- [X] T022 [US1] Build `jircd:jvm` locally and run `containerTest -Djircd.image=jircd:jvm`, so that T013 and T014 pass. Record the results and the image size in `specs/016-container-deployment/validation/tests.md`.

**Checkpoint**: US1 is demonstrable. The JVM image runs per the container contract for startup, logging, user and errors. This is an MVP increment, not permission to merge before the remaining P1 story is done.

## Phase 4: User Story 2 - Reload and stop a containerised server cleanly (Priority: P1)

**Goal**: HUP reloads the configuration, including the logging settings, without disconnecting clients. TERM and INT send `ERROR` to every client that can still receive it before the deadline (forced-close and prior-disconnect exceptions per FR-009) and exit 0 within 10 s.

**Independent Test**: `containerTest -Djircd.image=jircd:jvm` with `ContainerSignalTest`. HUP applies a changed capability and logging setting while clients stay connected. An invalid reload keeps the old configuration. `docker stop` with 100 clients exits 0 in under 10 s, and every client that can still receive output before the deadline receives the shutdown `ERROR`.

### Tests first

- [X] T023 [P] [US2] In `jircd-core/src/test/java/net/jircd/core/config/ConfigurationReloaderListenerTest.java`, test that:
  - listeners are called once after a successful publication, with the new configuration;
  - they are not called for a rejected reload;
  - a listener that throws is logged and doesn't undo the publication;
  - this holds for reloads from SIGHUP (`SighupReloadHandler.reload`) and from `REHASH` (`ConfigurationReloader.reloadWithOutcome`);
  - two overlapping reloads, with the first one's notification forced to run after the second's, leave listeners on the newer configuration;
  - a withdrawn provider whose `stop()` throws an `AssertionError` still gets listeners notified, and the error still propagates.
- [X] T024 [P] [US2] Extend `jircd-integration-tests/src/test/java/net/jircd/integration/LoggingConfigurationTest.java` with reloads that:
  - add a file (it starts receiving lines);
  - change the file path (the new file gets lines after the reload, and the old one gets none);
  - turn console logging off while a file is set;
  - are rejected because of `logging` (both the previous destinations and the previous non-logging settings stay in force).

  Each destination's lines must have no gaps or duplicates outside the switch window: a few lines logged at the instant of the switch may be lost or duplicated (132.3), so assert on lines logged well before and well after it.
- [X] T025 [P] [US2] Add `jircd-integration-tests/src/test/java/net/jircd/integration/GracefulShutdownTest.java`, run in-process against `TestServer`:
  - `shutdown("Server shutting down")` with 100 registered clients and some unregistered ones closes the listeners first (new connections are refused);
  - every client that can still receive output before the deadline receives exactly `ERROR :Closing Link: <host> (Server shutting down)` before EOF;
  - everything finishes within 8 s;
  - a client that doesn't read (full receive buffer) is force-closed at the budget, without the `ERROR`, with a WARN naming the count;
  - a connection whose session is not created before the deadline is force-closed without the `ERROR` and counted the same way;
  - a session already disconnecting for another reason (QUIT, EOF, KILL, timeout) keeps that disconnect's outcome and gets no shutdown `ERROR`;
  - a second `shutdown` call during draining is a no-op;
  - shutdown before `start()` returns at once.
- [X] T026 [P] [US2] Add `jircd-integration-tests/src/test/java/net/jircd/integration/container/ContainerSignalTest.java` (`@Tag("container")`) covering:
  - HUP after editing the mounted config (toggle a capability, add a `logging.file` on a writable tmpfs mount) logs "Configuration reloaded via SIGHUP", and the change takes effect while connected clients stay connected (scenario 1);
  - HUP with an invalid config keeps the old one and logs the WARN (scenario 3);
  - `stopWithin(10 s)` with 100 connected clients returns exit code 0, and every client that can still receive output before the deadline read `ERROR … (Server shutting down)` before EOF (scenario 2, SC-003; forced-close exceptions per FR-009);
  - INT behaves like TERM;
  - TERM sent immediately after container start, before the listeners bind, exits 0.
- [X] T027 [US2] Run T023–T026 to behavioural red after stubs, and record the results in `specs/016-container-deployment/validation/tests.md`.

### Implementation

- [X] T028 [US2] Add `addReloadListener(Consumer<ServerConfiguration>)` to `jircd-core/src/main/java/net/jircd/core/config/ConfigurationReloader.java`. Listeners are called after a successful publication, outside the reload monitor, each isolated by try/catch with a WARN. Delivery is ordered: it runs under a dedicated notification lock and delivers the latest published configuration, and a notification for a configuration older than the last one delivered is skipped, so listeners never end on an older configuration than `current()`. A listener-initiated (reentrant) reload still works, and the outer notification does not then deliver its older configuration. Listeners are still notified when a configuration was published but cleanup of withdrawn providers failed. This makes T023 pass.
- [X] T029 [US2] In `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java`, register the logging applier as a reload listener, so SIGHUP and `REHASH` both apply the logging changes. Validation stays in the loader, so a rejected `logging` section rejects the whole reload. This makes T024 pass.
- [X] T030 [US2] Add `drain(String reason, Duration timeout)` to `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`:
  - stop accepting;
  - for every live session, enqueue `ERROR :Closing Link: <realHostname> (<reason>)` using the same format as the SendQ overflow and KILL paths;
  - close each writer so it drains, bounded by the deadline, then close its read side so idle clients see EOF without waiting for the liveness tick;
  - then close the sockets, force-closing any left at the deadline and returning the count. Forced-close waiting fits inside the 8 s budget, and the count survives a failure while closing.

  Keep `closeConnections` unchanged for `stop()`. While a graceful shutdown is draining, `stop()` waits for it (bounded by the deadline) instead of closing sockets under it.
- [X] T031 [US2] Add `shutdown(String reason)` to `JircdServerApplication`. It is idempotent: the first call stops accepting, closes the listeners and drains within 8 s, then logs a WARN if anything was force-closed; later calls return at once. If the application never started, it returns at once. This makes T025 pass.
- [X] T032 [US2] Add `jircd-server/src/main/java/net/jircd/server/ShutdownSignalHandler.java`. It handles `TERM` and `INT` with `sun.misc.Signal`, calls `shutdown("Server shutting down")`, then `System.exit(0)`, and ignores repeated signals. Install it in `main` before `start()`, keeping `SighupReloadHandler` after it. Document both at the point of definition.
- [X] T033 [US2] Rebuild `jircd:jvm` and run `containerTest -Djircd.image=jircd:jvm`, so that T026 passes. Record the results, the measured shutdown time with 100 clients (SC-003) and the full `containerTest` run in `specs/016-container-deployment/validation/tests.md`.

**Checkpoint**: Stories 1 and 2 are complete on the JVM variant. The image is deployable.

## Phase 5: User Story 3 - Run a native-executable variant (Priority: P2)

**Goal**: A native image passes the same container suite and offers every capability and extension. Its load figures are compared with the JVM variant and recorded. It is documented as experimental.

**Independent Test**: Build `docker build --target native -t jircd:native .` (arm64 locally, amd64 in CI) and run `containerTest -Djircd.image=jircd:native`. Every Story 1 and 2 scenario passes unchanged, plus `OPER` with Argon2 and a CAP LS listing equal to the JVM variant's.

### Tests first

- [ ] T034 [P] [US3] Extend `ContainerContractTest` with three tests. First, the fixture gains an Argon2 OPER credential (reusing the Argon2 hashing `TestServer` uses), then `OPER` with it succeeds and a wrong password fails exactly as on the JVM image. Second, TLS registration works with a PKCS12 keystore fixture as well as PEM (FR-014, FR-015). Third, the `CAP LS 302` capability list and the `005` ISUPPORT tokens are identical to a recorded JVM-image baseline (FR-013, FR-014).
- [ ] T035 [P] [US3] Add `jircd-integration-tests/src/test/java/net/jircd/integration/ContainerDeliveryLoadTest.java` (`@Tag("container-load")`), which:
  - starts the container with a configuration that raises `maxConnectionsPerIp` and `maxConnections` above 1,000 (the default is 20 per source address, and every test client shares one), opens 1,000 connections to it, fanning out like `Story1DeliveryLatencyLoadTest`, and asserts that all 1,000 register;
  - asserts every delivery within 1 s (SC-004);
  - prints p50/p99/max latency, throughput, time from container start to the first accepted connection (SC-005), and resident memory with 100 idle clients from the Docker stats API (SC-006).

  Every client must answer the server's `PING`, so keep-alive never decides the run (lesson of #128).

### Implementation

- [ ] T036 [US3] Add the `org.graalvm.buildtools.native` `1.1.18` plugin to `gradle/libs.versions.toml` and apply it in `jircd-server/build.gradle.kts`:
  - `imageName = "jircd"`, `mainClass = net.jircd.server.JircdServerApplication`, `toolchainDetection = false`;
  - build args `--no-fallback`, `-march=compatibility`, `-H:+ReportExceptionStackTraces`;
  - `metadataRepository { enabled = true }`.

  Refresh the verification metadata. `./gradlew build` must not require GraalVM.
- [ ] T037 [US3] Add a `collectNativeMetadata` task in `jircd-server/build.gradle.kts`. It runs the `installDist` server under `-agentlib:native-image-agent=config-merge-dir=…` while `containerTest`-equivalent scenarios run against it in-process over sockets: registration, TLS, OPER with Argon2, reload, shutdown. It then keeps only entries for `org.yaml`, `com.password4j`, `ch.qos.logback` and `net.jircd`, and writes `jircd-server/src/main/resources/META-INF/native-image/net.jircd/jircd-server/reachability-metadata.json`. Commit the generated file once it has been reviewed.
- [ ] T038 [US3] Add the Dockerfile stages:
  - **`native-build`**: `ghcr.io/graalvm/native-image-community:25i5@sha256:…`, running `./gradlew :jircd-server:nativeCompile --no-daemon` with the cache mount.
  - **`native`**: `gcr.io/distroless/base-debian13:nonroot@sha256:…`. Copy the binary to `/opt/jircd/jircd`, with the same `WORKDIR`, `USER`, `EXPOSE` and `CMD` as `jvm`, and `ENTRYPOINT ["/opt/jircd/jircd"]`.
- [ ] T039 [US3] Build `jircd:native` locally (arm64) and run the full `containerTest` suite against it.
  - If TERM doesn't reach `ShutdownSignalHandler`, add `--install-exit-handlers` to the build args (research R2) and rerun.
  - Fix any missing-metadata failure by regenerating with T037, not by hand-editing.
  - Record the results, the image sizes for both variants, and native start-up time in `specs/016-container-deployment/validation/native.md`.
- [ ] T040 [US3] Run `containerLoadTest` locally against both images as a sanity check, recorded as arm64 in `validation/native.md`. The authoritative amd64 comparison comes from T044.

**Checkpoint**: The native variant passes the same contract as the JVM variant.

## Phase 6: User Story 4 - Images are built and verified automatically (Priority: P2)

**Goal**: CI builds and tests both variants on every PR and on `main`, and publishes to GHCR from `main` and from releases. A native build or smoke-test failure fails CI, while native performance shortfalls are only reported.

**Independent Test**: A PR runs `container.yml`, building and testing both variants. A deliberately broken native build in a throwaway branch fails the `native` job and names it. A push to `main` publishes `jvm`, `jvm-sha-<7>`, `latest`, `native` and `native-sha-<7>`.

- [ ] T041 [US4] Add OCI labels to both runtime stages of `Dockerfile`, filled from build args: `org.opencontainers.image.source`, `.revision` and `.version`, `.licenses=Apache-2.0`, `.title=jircd`, and a `.description` that names the variant (FR-021).
- [ ] T042 [US4] Create `.github/workflows/container.yml`, triggered on `pull_request` and on `push` to `main`, with workflow permissions `contents: read` only. GitHub Actions scopes permissions to a workflow or a job, never a step, so pull-request builds and tests must not share a job with `packages: write`:
  - **`jvm` and `native` jobs** (read-only, run on pull requests and on `main`): set up buildx, `build --target <variant> --load` (native on amd64 only), set up JDK 25 and Gradle, then `./gradlew :jircd-integration-tests:containerTest -Djircd.image=…`. Use `persist-credentials: false` on checkout.
  - **`publish-jvm` and `publish-native` jobs** (`needs` the matching test job, `if: github.ref == 'refs/heads/main'`, `permissions: contents: read, packages: write`): log in to GHCR with `GITHUB_TOKEN`, then build and push `linux/amd64,linux/arm64` tagged only `jvm-sha-<7>` (native: amd64, only `native-sha-<7>`); these jobs never push a moving tag. Pushing the immutable `*-sha-<7>` tags happens in the publish job **outside any concurrency group**, so every `main` commit gets its tag: GitHub Actions keeps only one pending run per concurrency group and cancels an older pending one even with `cancel-in-progress: false`, so a burst of commits could otherwise cancel a publish before it pushed its tag. A separate **retag job per variant** (`retag-jvm`, `retag-native`: `needs` the matching publish job, same `if` and `packages: write`) moves `jvm`, `native` and `latest` from the already pushed SHA tag (no rebuild) and runs in a `concurrency` group per variant with `cancel-in-progress: false`; concurrency is scoped to a job or workflow, not a step, so it has to be its own job. There, skipping an intermediate commit is harmless, because the newest pending run moves the tag. Serialization alone does not stop a slow older run from retagging after a newer one, so before retagging it checks that its commit is still the tip of `main` (`git ls-remote origin refs/heads/main` equals `github.sha`) and otherwise skips the moving tags. The check is best-effort: it is not atomic with the retag, so a newer commit can land just after it. The transient outcome is a moving tag one commit behind until the next publish moves it.

  Pin every third-party action by commit SHA with a version comment. Upload test reports on failure.
- [ ] T043 [P] [US4] Extend `.github/workflows/release.yml` with a separate publish job (job-level `packages: write`; the existing job keeps only `contents: write`) that builds and pushes `X.Y.Z-jvm` and `X.Y.Z` (multi-arch), plus `X.Y.Z-native` (amd64), after the release steps. Release tags are immutable, so the publish job `needs` per-variant smoke-test jobs that build each image from the release commit and run `containerTest` against it (as the `jvm` and `native` jobs of T042 do), and pushes only after they pass. A variant that fails its smoke test is not published (US4). Pin actions by SHA.
- [ ] T044 [P] [US4] Add a `container-load` job to `.github/workflows/load-test.yml`. It builds both images on amd64, runs `containerLoadTest` against each, and writes a side-by-side table to `$GITHUB_STEP_SUMMARY`: p50/p99/max, throughput, start-up and memory, plus the native-versus-JVM percentage, flagging anything over 10%. The JVM run gates the job. The native run is `continue-on-error` while the variant is experimental (FR-016).
- [ ] T045 [P] [US4] Add `package-ecosystem: docker` (directory `/`, weekly, grouped) to `.github/dependabot.yml`, so the digest-pinned base images get update PRs.
- [ ] T046 [US4] Before opening the PR, check the workflows with `actionlint` if it is available. Otherwise review them by hand against `.github/REVIEW_CHECKLIST.md`. Record what was checked in `specs/016-container-deployment/validation/ci.md`. CI evidence from the PR run is added after it completes.

**Checkpoint**: Both variants are built, tested and published automatically.

## Phase 7: Polish & Cross-Cutting Concerns

- [ ] T047 Ship the README text with the code layer it describes, not here: the `logging` section in the logging layer (US1), stopping and shutdown in the shutdown layer (US2), and the "Running in a container" section in the image layer. This task is the final consistency check of those sections. The container section covers pulling the image, building locally (`--target jvm|native`), mounts, ports, the read-only root, uid 65532 and file ownership, reloading and stopping, viewing logs, enabling `logging.file` on a volume, which paths are writable, and the native variant's differences (extensions fixed at build time, Serial GC, experimental status). Add a minimal `docker run` example and an optional Compose snippet (FR-022).
- [ ] T048 [P] The logging migration note from contracts/logging-config.md and the `logging` section ship in the logging layer's README change; here, check they are still correct, and document the `logging` section wherever the configuration schema is documented (`specs/001-ircv3-server/contracts/server-configuration.md`, if that is the maintained schema reference). Fix the README claim that the zero-config `run` starts TLS on 6697: it starts plaintext 6667 only.
- [ ] T049 Walk through quickstart.md steps 1–4 by hand with a real IRC client against `jircd:jvm` and `jircd:native` (constitution Principle III). Record a summary in `specs/016-container-deployment/validation/walkthrough.md`.
- [ ] T050 Run `./gradlew build` (tests, Spotless, SpotBugs, PMD, coverage floor 85% line / 75% branch) and the full `containerTest` against both local images. Record the results in `validation/tests.md`.
- [ ] T051 Check the diff against `.github/REVIEW_CHECKLIST.md`: failure and cleanup paths in drain and the applier, concurrency of reload against shutdown, and hostile configuration input. Then run local Sonar per AGENTS.md and fix every open issue on the branch.
- [ ] T052 Get an independent cross-vendor review (Codex) of the diff against the checklist. Fix the real findings, and record dispositions in `specs/016-container-deployment/validation/review.md`.
- [ ] T053 Update `spec.md` Status, mark the completed tasks here, and stop for the user's review before committing or opening the PR.

## Dependencies and Execution Order

- **Setup** (T001–T005) → **Foundational** (T006–T008) → **US1** (T009–T022) → **US2** (T023–T033) → **US3** (T034–T040) → **US4** (T041–T046) → **Polish** (T047–T053).
- US2 depends on US1: its container tests need the JVM image, and its logging reload needs the applier.
- US3 depends on US1 and US2, because it reruns their suite against the native image.
- US4's `jvm` job only needs US1 and US2; its `native` job and T044 need US3.
- T047 and T048 can start once US2 is done.

## Parallel Examples per Story

- **US1**: T009, T010, T011, T012, T013 and T014 are separate test files, so they can be written in parallel. T021 can be done alongside T016–T020.
- **US2**: T023, T024, T025 and T026 can be written in parallel. T028–T029 (reload) and T030–T032 (shutdown) are independent implementation tracks.
- **US3**: T034 and T035 in parallel with T036.
- **US4**: T043, T044 and T045 in parallel after T042.

## Implementation Strategy

1. **MVP**: US1 + US2 on the JVM variant (Phases 1–4). This alone is a deployable, contract-complete image.
2. **Native**: US3 adds the experimental native variant against the same suite.
3. **Automation**: US4 publishes both from CI, and the weekly job collects the native-versus-JVM evidence needed to promote native later (FR-016: four consecutive weekly passes).

## Coverage and Task Summary

- **53 tasks**: Setup 5, Foundational 3, US1 14, US2 11, US3 7, US4 6, Polish 7.
- **Coverage of every FR and SC**: T008 maps each requirement to its tasks, and T050 shows the coverage floor holds. The in-process tests (T009–T012, T023–T025) cover the server logic that the container tests exercise only from outside.
