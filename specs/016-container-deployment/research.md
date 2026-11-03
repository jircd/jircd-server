# Research: Container Deployment

**Feature**: 016-container-deployment | **Date**: 2026-11-03

Each section records a decision, why it was made, and what else was considered. Versions were checked on 2026-11-03.

## R1. Logging configuration mechanism

**Decision**: Remove `jircd-server/src/main/resources/logback.xml` and configure logback programmatically:

- A `ch.qos.logback.classic.spi.Configurator` registered through `META-INF/services` installs a bootstrap console appender at startup. It keeps today's pattern and the INFO level.
- After the YAML configuration loads, and after every successful reload, a settings applier attaches or detaches the console appender and a rolling file appender on the root logger. It only replaces appenders whose settings changed.

**Rationale**:

- Logback runs custom `Configurator` providers before its own `DefaultJoranConfigurator`. The bootstrap is therefore in place before any log line, including a "configuration invalid" error.
- Programmatic setup avoids Joran's reflective XML processing, which is the part of logback that most needs native-image metadata.
- Replacing only the changed appenders means a reload that leaves console logging on keeps writing console lines through its own appender, untouched. The swap of a changed file is not atomic: an event logged at the instant of the switch can be lost or reach only one destination.

**Operator override**: Custom providers run *before* logback's own lookup of `-Dlogback.configurationFile`. So when that property is set, the jircd configurator returns `INVOKE_NEXT_IF_ANY` and does nothing, and the applier logs once that the YAML `logging` section is ignored. This keeps the standard escape hatch from the spec's Assumptions.

**Alternatives considered**:

- *Keep `logback.xml` and use `${}` variables or `<if>` conditions fed from system properties*: the YAML would have to be read before logback starts, and reload would need logback's file scanner. Conditionals also need Janino, an extra dependency that is hostile to native image.
- *Ship two XML files (console-only and console+file) and pick one by system property*: still couldn't take a path from the YAML or follow reloads.

## R2. Graceful shutdown and exit status

**Decision**: Handle `TERM` and `INT` with `sun.misc.Signal`, the same mechanism `SighupReloadHandler` already uses for `HUP`:

- The handler is installed before `start()`.
- It calls a new `JircdServerApplication.shutdown(reason)` and then `System.exit(0)`.
- `shutdown` stops accepting connections, closes the listeners, enqueues `ERROR :Closing Link: <host> (Server shutting down)` to every live session, closes each session's writer so its queue drains, then closes the sockets.
- Everything runs inside an 8-second budget, leaving margin under the runtime's 10-second grace period.
- Past the budget, the remaining connections are closed hard, a WARN is logged, and the process still exits 0.
- A second signal during shutdown is ignored.

**Rationale**:

- A JVM shutdown hook would make `docker stop` exit with 143, but FR-009 requires 0.
- A native executable doesn't run shutdown hooks on SIGTERM unless it was built with `--install-exit-handlers`.
- An explicit handler behaves the same on both variants and on host installs (systemd `stop` also sends TERM).
- The `ERROR :Closing Link: … (reason)` shape matches what KILL and SendQ overflow already send.
- The existing `stop()` closes sockets without notifying clients and stays as it is for tests.

**Open check, verified by the native smoke test**: GraalVM CE native images support `sun.misc.Signal.handle`. If the native smoke test shows TERM is not delivered to the handler, add `--install-exit-handlers` to the native build args. Without a shutdown hook installed it has no side effect.

**Alternatives considered**:

- *`Runtime.addShutdownHook`*: gives the wrong exit status, and native needs extra flags.
- *A container `STOPSIGNAL` of QUIT or similar*: non-standard and confusing for operators. It doesn't help host installs either.

## R3. Container base images and image layout

**Decision**: One root `Dockerfile` with four stages:

| Stage | Base | Purpose |
|---|---|---|
| `build` | `eclipse-temurin:25-jdk`, pinned to `--platform=$BUILDPLATFORM` | `./gradlew :jircd-server:installDist`, with a cache mount for `~/.gradle` |
| `jvm` (default target) | `gcr.io/distroless/java25-debian13:nonroot` | copies `lib/*.jar` and runs `java` in exec form |
| `native-build` | `ghcr.io/graalvm/native-image-community:25i5` (latest monthly CE feature release) | `./gradlew :jircd-server:nativeCompile` |
| `native` | `gcr.io/distroless/base-debian13:nonroot` | copies the `jircd` executable |

The tag is noted in comments, but every `FROM` is pinned by digest.

Details:

- `nonroot` distroless images run as uid/gid 65532, so the user id is fixed (FR-004).
- They have no shell, so the `installDist` start script can't be used. The JVM entrypoint is `java -XX:MaxRAMPercentage=75 -XX:-UsePerfData -cp "/opt/jircd/lib/*" net.jircd.server.JircdServerApplication`. The class-path wildcard is expanded by the `java` launcher, not by a shell.
- `-XX:-UsePerfData` stops the JVM writing `/tmp/hsperfdata_*`, so a read-only root filesystem works (FR-005).
- `MaxRAMPercentage=75` sizes the heap from the container's memory limit (FR-011). The default of 25% is too small for a dedicated server process.
- `CMD ["/etc/jircd/jircd.yaml"]` is the configuration path. Passing a different argument overrides it (FR-002).
- The JVM image is multi-arch without emulation: only `build` runs any commands, and it runs on the build platform because jars are platform-independent. The per-architecture `jvm` stage only copies files onto the matching distroless base.
- The native executable links against glibc dynamically, so `base-debian13` (glibc, CA certificates, tzdata) is the smallest base that works. `static-debian13` would need a musl static build.

**Rationale**:

- Distroless images are small, have no shell or package manager to attack, ship a nonroot variant, and are published for both amd64 and arm64.
- The Java 25 distroless image is built on Temurin, the same distribution CI uses.
- GraalVM CE is updated only through its monthly feature releases (25.1, 25.2, …). The CPU/CSPU patch lines are Oracle GraalVM only. Pinning the latest `25iN` tag by digest, and letting Dependabot's `docker` ecosystem raise digest PRs, is how CE builds pick up security fixes.

**Alternatives considered**:

- *`eclipse-temurin:25-jre` as the runtime*: has a shell (useful for debugging) but is larger, runs as root by default, and has more CVE noise. The `:debug-nonroot` distroless tag gives a busybox shell when one is needed.
- *Jib*: builds without a Docker daemon, but its 3.5.x releases don't say whether they support Java 25 (class file 69) for main-class inference. It also can't build the native variant, which would mean two build systems for one contract.
- *Paketo buildpacks*: opaque layering and a different user id, and native support comes through a different GraalVM distribution (Liberica NIK).
- *musl static native binary on `distroless/static`*: smallest image, but musl's allocator is known to hurt multi-threaded throughput. Revisit after measurement.

## R4. Native-image build tooling and metadata

**Decision**:

- Apply `org.graalvm.buildtools.native` 1.1.18 to `jircd-server` only, with `imageName = "jircd"`. `toolchainDetection` is off, so it uses the GraalVM JDK of the `native-build` stage.
- Build args: `--no-fallback`, `-march=compatibility` (the binary runs on any x86-64-v1 host, not just the build CPU), `-H:+ReportExceptionStackTraces`, and `-R:MaxHeapSize` left at its default; see R6.
- Enable the reachability-metadata repository, which covers logback-classic 1.6.6 (tested-versions include it) and slf4j-api.
- SnakeYAML 2.7 isn't in the repository's tested versions (it stops at 2.1), and password4j isn't in it at all. Metadata for both, plus the project's own resources (`net/jircd/server/version.properties`), comes from the GraalVM tracing agent.
- The agent runs on the JVM distribution while the container contract scenarios run against it. Its output is filtered to the `org.yaml`, `com.password4j`, `ch.qos.logback` and `net.jircd` packages and committed under `jircd-server/src/main/resources/META-INF/native-image/net.jircd/jircd-server/`.
- Regenerating it is a documented Gradle task (quickstart §5). A dependency bump that breaks it shows up as a failed native smoke test (FR-018).

**Rationale**:

- The project has no reflection, proxies or `Class.forName` in main code.
- `ServiceLoader` providers in `META-INF/services` on the build class path are registered automatically. All nine capability and server extensions are on the build class path, so the native variant offers exactly what the JVM variant offers (FR-013).
- `ExtensionClassLoader` (a `URLClassLoader`) is unused in main code. Runtime-loaded extensions stay out of scope, as the spec states.

**Alternatives considered**:

- *Hand-written metadata*: brittle, and nothing checks it is complete.
- *Map SnakeYAML to the repository's 2.1 metadata (`moduleToConfigVersion`)*: may be enough on its own, but the 2.1→2.7 differences are untested. The agent output is the evidence, and the repository metadata is a fallback.

## R5. Testing against built images

**Decision**:

- Add a `container` JUnit tag in `jircd-integration-tests`, run by a new `containerTest` Gradle task. The task is excluded from `test` and `loadTest`, like `load`.
- Its tests use Testcontainers 2.0.5 to start the image named by the `jircd.image` system property.
- They reuse `RawIrcClient` for the protocol side.
- They send signals through the Docker API (`killContainerCmd(id).withSignal("HUP")`), stop with `stopContainerCmd` and a 10-second timeout, and read exit status and logs from the container.
- The same test classes run against both variants (FR-015).
- Every test starts the container with a read-only root filesystem, so any default write inside the container fails the run. That proves SC-007 directly.

**Rationale**:

- The smoke test has to exercise the built artefact, including the entrypoint, user, signals and native binary, not the in-process `TestServer`.
- Testcontainers handles lifecycle, port mapping and log capture, and keeps assertions in the same JUnit and AssertJ style as the rest of the suite.

**Coverage**: Container tests don't add JaCoCo data. The behaviour they exercise (logging settings, graceful shutdown, config errors) also gets in-process tests in the normal `test` suite, so the 85%/75% floor holds.

**Local runs**: Running `containerTest` needs a container runtime. The maintainer machine has Docker Desktop (engine linux/arm64), so the suite runs locally against both variants before each PR. Testcontainers finds the engine through `DOCKER_HOST`, or through Docker Desktop's `/var/run/docker.sock` link. A locally built native image is arm64. CI is still the authoritative runner for the published amd64 native image and for the performance comparison. Contributors without Docker or Podman rely on the CI job.

**Alternatives considered**:

- *A shell script driving `docker run` plus an IRC client like `nc`*: weak assertions, no TLS client, and duplicates `RawIrcClient`.
- *Pointing the existing integration tests at an external server*: most use in-process hooks such as `server.application.nicknameRegistry()`, so it's not a drop-in change.

## R6. Native performance comparison and memory

**Decision**:

- Add one container load scenario, `ContainerDeliveryLoadTest` (tag `container-load`): 1,000 connections, a fan-out like `Story1DeliveryLatencyLoadTest`, and the same 1-second delivery budget.
- It runs weekly in `load-test.yml` against both images on the same runner. It records p50/p99/max latency, throughput, time from container start to the first accepted connection (SC-005), and resident memory with 100 idle clients (SC-006, from the Docker stats API).
- The native-versus-JVM comparison goes into the job summary. While the native variant is experimental, a native shortfall is reported and does not fail the workflow (FR-016).
- The native heap keeps GraalVM's default maximum. The comparison shows whether an explicit `-R:MaxHeapSize` or `-XX:MaximumHeapSizePercent` is needed under container limits.

**Rationale**:

- Both variants run in containers on the same host, so the comparison is like for like.
- The in-process load tests remain the JVM regression suite.
- CE native images only have the Serial GC (G1 is Oracle GraalVM only), so latency under 1,000 connections is the real risk, and this scenario measures exactly that.

**Alternatives considered**:

- *Port every load test to run against an external server*: large and invasive, for little gain over one representative scenario.

## R7. CI workflow and publishing

**Decision**:

- Add a new `.github/workflows/container.yml`, triggered on pull requests and on pushes to `main`.
  - **`jvm` job** (ubuntu-latest): `docker buildx build --target jvm --load` for amd64, then `./gradlew :jircd-integration-tests:containerTest -Djircd.image=…`. On `main` it then builds amd64 and arm64 and pushes.
  - **`native` job** (ubuntu-latest, amd64 only): the same steps with `--target native`, pushed on `main`.
- Images go to `ghcr.io/jircd/jircd-server` using `GITHUB_TOKEN`. `packages: write` is granted only to the push steps on `main`.
- Tags:
  - `jvm-sha-<7>` and `native-sha-<7>` (immutable)
  - `jvm` and `native` (latest passing `main`)
  - `latest` (equals `jvm`)
- `release.yml` also pushes `X.Y.Z-jvm`, `X.Y.Z-native` and `X.Y.Z` (equal to jvm) when a version tag is created.
- OCI labels: `org.opencontainers.image.source`, `.revision`, `.licenses=Apache-2.0`, `.version` (FR-021).
- Every third-party action is pinned by commit SHA (repo rule; unpinned actions fail main's Sonar gate).
- Add `package-ecosystem: docker` to Dependabot for the `Dockerfile`.

**Rationale**:

- GHCR needs no extra secret and links the package to the repository.
- Only the immutable sha tags are needed to trace an image back to its source. The moving tags exist for convenience.

**Alternatives considered**:

- *Building images inside `build.yml`*: would slow every PR's main signal by several minutes for the native compile. A separate workflow keeps failures easy to tell apart, and branch protection can still require it.

## R8. Configuration file errors at startup

**Decision**: Open the configuration with `Files.newInputStream` instead of `FileInputStream`, and map the failures to specific messages:

- `NoSuchFileException` becomes "Configuration file not found: <path>".
- `AccessDeniedException` becomes "Configuration file not readable by this user: <path>". In a container this is uid 65532, and the docs say so.

The existing "Refusing to start: …" prefix and exit status 1 stay as they are.

**Rationale**: FR-010 and the edge case for files only root can read require telling "missing" and "unreadable" apart. `FileInputStream` reports both as a `FileNotFoundException` with an OS-specific suffix.
