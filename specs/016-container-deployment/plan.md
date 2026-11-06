# Implementation Plan: Container Deployment

**Branch**: `016-container-deployment` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/016-container-deployment/spec.md`

## Summary

Ship jircd as an official container image in two variants built from one root `Dockerfile`:

- a **JVM** image (distroless Java 25, multi-arch amd64 and arm64), which is supported;
- a **native** image (GraalVM CE 25 native executable on distroless base, amd64), which is experimental.

Both share one container contract: config at `/etc/jircd/jircd.yaml`, uid 65532, read-only root filesystem, logs on stdout, HUP to reload, and TERM for a graceful shutdown with exit status 0.

Server changes this needs:

1. A reloadable `logging` YAML section (console on/off and an optional rotating file), applied programmatically to logback in place of `logback.xml`.
2. A graceful shutdown on TERM/INT that sends `ERROR` to every client.
3. Distinct startup messages for a missing configuration and an unreadable one.
4. Native-image build configuration with agent-generated reachability metadata.

Verification:

- A Testcontainers `containerTest` suite runs against each built image.
- A weekly native-versus-JVM container load comparison runs.
- A new `container.yml` workflow builds, tests and publishes to GHCR.

The technical decisions are in [research.md](research.md).

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain); GraalVM Community Edition 25 (monthly feature release `25i5`) for the native build

**Primary Dependencies**:

- Existing: SnakeYAML 2.7, SLF4J 2.0.20, logback-classic 1.6.6, password4j 1.8.4
- New build plugin: `org.graalvm.buildtools.native` 1.1.18
- New test dependency: Testcontainers 2.0.5
- Container base images: `eclipse-temurin:25-jdk` (build), `gcr.io/distroless/java25-debian13:nonroot`, `ghcr.io/graalvm/native-image-community:25i5`, `gcr.io/distroless/base-debian13:nonroot`, all pinned by digest

**Storage**: N/A. Optional log files go on an operator-mounted volume

**Testing**: JUnit 6 and AssertJ:

- in-process unit and integration tests in the existing `test` suite
- a new `container` tag (`containerTest` task, Testcontainers) against built images
- a new `container-load` tag (`containerLoadTest`) for the native/JVM comparison

**Development environment**: Docker Desktop on Apple Silicon (engine linux/arm64), so `containerTest` runs locally against both variants. A local native build is arm64. Published native images and the performance comparison come from amd64 CI.

**Target Platform**: Linux containers (OCI). JVM image on linux/amd64 and linux/arm64; native image on linux/amd64. Host installs are unchanged apart from logging defaults

**Project Type**: Network server (IRC daemon), multi-module Gradle build

**Performance Goals**:

- The native variant meets the 1 s delivery budget at 1,000 connections (SC-004).
- The native variant starts accepting within 1 s (SC-005).
- Shutdown with 100 clients takes under 10 s (SC-003).
- The JVM variant's existing load budgets are unchanged.

**Constraints**:

- Read-only root filesystem.
- No shell in the runtime images.
- Exit status 0 on TERM.
- Native: closed-world extensions, Serial GC only.
- Native build needs about 8 GB RAM, which fits a GitHub-hosted runner.

**Scale/Scope**:

- One Dockerfile, four stages.
- About 6 new or changed main classes.
- One new CI workflow, plus extensions to `load-test.yml`, `release.yml` and Dependabot.
- A README section and a sample config.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Assessment |
|---|---|
| **I. Code Quality** | Each new class has one job: settings parse/validate, logback applier, bootstrap configurator, shutdown signal handler, graceful drain. Spotless, SpotBugs and PMD apply to all new Java. The change replaces the packaged `logback.xml` with the YAML `logging` section ([contracts/logging-config.md](contracts/logging-config.md)). New dependencies (native build plugin, Testcontainers) are justified by FR-012 and FR-015 and are confined to the build and tests respectively. **PASS** |
| **II. Testing Standards** | Tests come first for each server change: logging validation and reload (unit), applier swaps (unit with a real `LoggerContext`), graceful shutdown with `ERROR` delivery (in-process integration), config-file errors (integration). The container suite proves the built artefacts. Coverage holds because the logic is also covered in-process (R5). Container tests use bounded waits and fresh containers per class, with no retries. **PASS** |
| **III. UX Consistency** | Startup errors keep the `Refusing to start:` form. Shutdown uses the existing `ERROR :Closing Link: <host> (reason)` shape. The `logging` section follows the existing YAML validation style. **Breaking change**: no default `logs/jircd.log` on host installs. The project is unreleased (0.1.0-SNAPSHOT, nothing published), so there is no released interface to version. The migration note goes in the README and [contracts/logging-config.md](contracts/logging-config.md). This justification goes in the PR. A manual walkthrough of [quickstart.md](quickstart.md) steps 1–4 is part of done. **PASS (with recorded justification)** |
| **IV. Performance** | Budgets are declared before implementation: SC-003 (shutdown), SC-004 and SC-005 (native), SC-006 (memory recorded). The hot path is unchanged: the logging applier runs only at startup and reload, and shutdown is not a hot path. The container load scenario is re-runnable weekly. Native regressions beyond 10% are reported (not gating while experimental, FR-016). **PASS** |
| **Quality Gates / Workflow** | Work is tracked by this spec. A cross-vendor review, and a local Sonar run when a SonarCloud token is available (AGENTS.md), before the PR. Actions pinned by SHA. **PASS** |

**Post-design re-check (after Phase 1)**: No new violations. The design adds no runtime abstraction beyond what FR-006 and FR-009 require, and the native build is opt-in per Dockerfile target. **PASS**

## Project Structure

### Documentation (this feature)

```text
specs/016-container-deployment/
├── spec.md
├── plan.md                    # this file
├── research.md                # Phase 0: decisions R1–R8
├── data-model.md              # LoggingSettings, logging and shutdown states, image variants
├── quickstart.md              # build, run and validation scenarios
├── contracts/
│   ├── container-contract.md  # tags, user, paths, signals, exit codes, messages
│   └── logging-config.md      # `logging` YAML section
├── checklists/requirements.md
├── validation/                # summary-only evidence (environment, tests, coverage, native, CI, walkthrough); no raw logs or per-run files
└── tasks.md                   # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
Dockerfile                                   # NEW: stages build → jvm (default), native-build → native
.dockerignore                                # NEW: excludes .git, .worktrees, **/build, jircd-server/jircd.yaml, specs
deploy/container/jircd.yaml                  # NEW: sample config (plaintext listener, logging defaults, commented TLS/OPER)

jircd-core/src/main/java/net/jircd/core/config/
├── ServerConfiguration.java                 # + Logging record (console, file)
├── ConfigurationLoader.java                 # + parse/validate `logging` (data-model.md rules)
└── ConfigurationReloader.java               # + reload listeners notified after successful publication

jircd-core/src/main/java/net/jircd/core/session/
└── ConnectionHandler.java                   # + drain(reason, timeout): ERROR to live sessions, writer drain, close

jircd-server/
├── build.gradle.kts                         # logback → implementation; + org.graalvm.buildtools.native config;
│                                            #   + collectNativeMetadata task
├── src/main/java/net/jircd/server/
│   ├── JircdServerApplication.java          # + shutdown(reason); config-file error mapping (R8); applies logging
│   ├── ShutdownSignalHandler.java           # NEW: TERM/INT → shutdown → exit 0, installed before start
│   └── logging/
│       ├── BootstrapLogbackConfigurator.java    # NEW: logback Configurator SPI (console bootstrap / defer to external)
│       └── LoggingSettingsApplier.java          # NEW: applies Logging settings to root appenders
├── src/main/resources/
│   ├── logback.xml                          # REMOVED
│   ├── META-INF/services/ch.qos.logback.classic.spi.Configurator   # NEW
│   └── META-INF/native-image/net.jircd/jircd-server/reachability-metadata.json  # NEW (agent-generated)

jircd-integration-tests/
├── build.gradle.kts                         # + Testcontainers; containerTest / containerLoadTest tasks
└── src/test/java/net/jircd/integration/
    ├── container/                           # NEW: @Tag("container") ContainerContractTest, ContainerSignalTest,
    │                                        #   ContainerFailureTest; JircdContainer helper (Testcontainers)
    ├── ContainerDeliveryLoadTest.java       # NEW: @Tag("container-load") native-vs-JVM comparison
    ├── GracefulShutdownTest.java            # NEW: in-process shutdown + ERROR delivery
    ├── LoggingConfigurationTest.java        # NEW: file/console destinations, reload swaps
    └── ConfigurationFileErrorTest.java      # NEW: missing vs unreadable messages

build.gradle.kts                             # exclude "container", "container-load" tags from test/loadTest
gradle/libs.versions.toml                    # + graalvm-native plugin, testcontainers
gradle/verification-metadata.xml             # regenerated for the new artefacts

.github/workflows/container.yml              # NEW: build + containerTest both variants; push to GHCR on main
.github/workflows/load-test.yml              # + container-load job (both images, summary comparison)
.github/workflows/release.yml                # + versioned image tags
.github/dependabot.yml                       # + docker ecosystem
README.md                                    # + "Running in a container" section; logging migration note;
                                             #   fix "6697 (TLS)" claim for the zero-config default
```

**Structure Decision**: The existing multi-module layout stays as it is.

- Configuration model changes stay in `jircd-core/config`, where every other section lives.
- Logback-specific code goes in `jircd-server`, the only module that ships a logging backend, so `jircd-core` keeps depending on SLF4J only.
- Container tests go in `jircd-integration-tests` to reuse `RawIrcClient`, behind their own tags and tasks so `./gradlew build` never needs Docker.
- Packaging files sit at the repository root, as container tooling expects.

## Implementation Phases (for /speckit-tasks)

1. **Server: logging settings** (FR-006, FR-006a, FR-006b): model and validation, then the bootstrap configurator, the applier, and the reload listener. Tests first. Remove `logback.xml`.
2. **Server: graceful shutdown and config errors** (FR-009, FR-010): `ConnectionHandler.drain`, `JircdServerApplication.shutdown`, `ShutdownSignalHandler`, config error mapping. Tests first.
3. **JVM image and container contract tests** (FR-001–FR-005, FR-007, FR-008, FR-011): `Dockerfile` `build`/`jvm` stages, `.dockerignore`, sample config, Testcontainers suite, `containerTest` task. This is the MVP: Stories 1 and 2 on the JVM variant.
4. **Native variant** (FR-012–FR-016): native build plugin, `native-build` and `native` stages, metadata collection task and committed metadata, the same suite against the native image.
5. **CI and publishing** (FR-017–FR-021): `container.yml`, release tags, Dependabot docker ecosystem, and the weekly container load comparison (SC-004–SC-006).
6. **Docs** (FR-022): README text ships with the layer it describes (logging, shutdown, container); the last step checks it for consistency, adds the native caveats and records the quickstart walkthrough in `validation/` as a summary.

## Complexity Tracking

No constitution violations need justifying. The one user-facing break, the default log file, is justified under Principle III above.
