# Feature Specification: Container Deployment

**Feature Branch**: `016-container-deployment`

**Created**: 2026-11-03

**Status**: Draft — awaiting review before planning

**Input**: User description: "Server deployment packaging: provide an official container image for jircd in two variants built from one build definition — a JVM image and a GraalVM native-image variant, possibly combined. Container-ready runtime: console-only logging, a fixed config location with read-only config and TLS files, the standard IRC ports, a non-root user, and correct handling of reload and stop signals. Extensions are fixed when the native executable is built. Native acceptance needs a smoke test (registration, TLS, OPER, reload) and a load comparison against the JVM variant. CI builds the images."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Run jircd from a container image (Priority: P1)

An operator pulls the official jircd image, mounts a configuration file (and, when TLS is used, a certificate and key), publishes the plaintext and TLS ports, and has a working IRC server without installing Java or building from source.

**Why this priority**: This is the core value of the feature. Today the only supported ways to run jircd are `./gradlew :jircd-server:run` (development) or an `installDist` tree on a host with Java 25. A container image removes that setup for every operator.

**Independent Test**: Build the JVM image, start it with a minimal configuration mounted read-only, connect a real IRC client over plaintext and TLS, register, join a channel and exchange a message.

**Acceptance Scenarios**:

1. **Given** a valid configuration mounted at the documented location, **When** the operator starts the image with the IRC ports published, **Then** clients can connect, register and chat over plaintext and TLS.
2. **Given** a configuration that refers to TLS certificate and key files mounted read-only, **When** the server starts, **Then** it serves TLS with that certificate.
3. **Given** no configuration is mounted, or the mounted one is invalid, **When** the container starts, **Then** it exits with a non-zero status and a log line that names the problem and the expected configuration location.
4. **Given** the container runs with a configuration that sets no log file, **When** the operator inspects its logs through the container runtime, **Then** every server log line (startup, connections, security events) appears there and nothing is written inside the container's filesystem.
5. **Given** the configuration names a log file on a mounted writable volume, **When** the server runs, **Then** log lines go to that file, and to the console as well unless console logging is turned off.
6. **Given** the image, **When** it starts, **Then** the server process runs as an unprivileged user, and its configuration and certificate files only need to be readable.

---

### User Story 2 - Reload and stop a containerised server cleanly (Priority: P1)

An operator edits the mounted configuration and tells the running container to reload, without dropping connections. Later the operator stops or restarts the container, and connected clients are told the server is going away instead of being cut off.

**Why this priority**: Configuration reload already exists through the hang-up signal, and operators of a long-running IRC server rely on it. A container that ignores signals, or that kills clients without notice on every restart or upgrade, is not fit for production.

**Independent Test**: Start a container, connect clients, change a reloadable setting in the mounted file, send the reload signal through the container runtime, and confirm the change takes effect while the clients stay connected. Then stop the container and confirm each client receives a closing notice before its connection ends, within the runtime's stop grace period.

**Acceptance Scenarios**:

1. **Given** a running container with connected clients, **When** the operator sends the reload signal through the container runtime, **Then** the server re-reads its configuration exactly as a host install does on the hang-up signal, and no client is disconnected.
2. **Given** a running container with connected clients, **When** the operator stops the container, **Then** every connected client receives an `ERROR` line saying the server is shutting down, and the process exits with status 0 before the runtime's default stop grace period (10 seconds) ends.
3. **Given** a reload with an invalid configuration, **When** the reload signal arrives, **Then** the server keeps its previous configuration and logs why the new one was rejected, the same as on a host install.

---

### User Story 3 - Run a native-executable variant (Priority: P2)

An operator who wants a smaller image and a lower memory footprint (for example on a small virtual server) chooses the native variant of the image. It behaves the same as the JVM variant for every operator- and client-visible behaviour, uses the same configuration file and mount points, and starts faster.

**Why this priority**: It's a valuable option but not required for a first deployable release. It carries real risk: some libraries need extra build-time metadata, the set of extensions is fixed when the executable is built, and garbage-collection behaviour differs. It therefore ships after the JVM variant has proven the container contract.

**Independent Test**: Build the native variant and run the same container acceptance scenarios as Stories 1 and 2 against it, plus a smoke test of registration, TLS, operator login (`OPER` with an Argon2 hash) and configuration reload.

**Acceptance Scenarios**:

1. **Given** the native image and the same mounted configuration used for the JVM image, **When** it starts, **Then** every scenario in Stories 1 and 2 passes unchanged.
2. **Given** the native image, **When** an operator logs in with `OPER` using an Argon2-hashed credential, **Then** login succeeds or fails exactly as on the JVM variant.
3. **Given** the native image, **When** it starts, **Then** it offers every capability and server extension the JVM variant offers.
4. **Given** the native image, **When** it runs the project's standard load scenarios, **Then** it meets the same delivery budgets as the JVM variant (see SC-004).

---

### User Story 4 - Images are built and verified automatically (Priority: P2)

A maintainer merges a change and the project's CI builds both image variants and runs their container smoke tests. A variant that fails to build or fails its smoke test is reported, so a broken image is never published.

**Why this priority**: Without automation the images drift out of date and silently break, especially the native variant, which can fail when a dependency changes.

**Independent Test**: Open a pull request that breaks native compatibility (for example by adding a reflective lookup without metadata) and confirm CI reports the native smoke test as failed.

**Acceptance Scenarios**:

1. **Given** a change on the main branch, **When** CI runs, **Then** both image variants are built and their smoke tests run.
2. **Given** a native smoke-test failure, **When** CI reports, **Then** the failure names the variant and the failing scenario.
3. **Given** a successful build of main, **When** images are published, **Then** they go to the GitHub Container Registry (`ghcr.io/jircd/jircd-server`) and are tagged so the operator can tell the JVM and native variants and the source revision apart.

---

### Edge Cases

- The mounted configuration names a TLS certificate path that does not exist inside the container: startup fails with a message naming the path, as on a host install.
- The mounted configuration or certificate is readable only by root on the host: the server, running unprivileged, cannot read it. The error must say the file is unreadable, not that it is missing or invalid.
- The configuration sets a listener port other than the defaults: the image works, and the docs say the published ports must match the configuration.
- The configuration names a log file whose directory does not exist or is not writable, at startup: startup fails with a message naming the path and the reason. A reload with such a path is rejected, and the previous logging setup stays in effect.
- The configuration turns console logging off and sets no log file: the configuration is rejected, because the server would otherwise run with no log output at all.
- A reload changes the log file path or turns console logging on or off: the new setup takes effect without a restart, and lines logged before and after the switch reach the destinations configured at that time. The switch is not atomic: a line logged at the very instant of the switch can be lost or reach only one destination.
- The configuration sets the MOTD file: it must point at a mounted file. The docs must say which paths are writable.
- The operator sends the reload signal while a previous reload is still running: same behaviour as on a host install.
- The stop signal arrives during startup, before the listeners are bound: the process exits cleanly with no partial state.
- The container's memory limit is lower than the server's default heap would want: the JVM variant must size itself to the container limit instead of being killed by it.
- A capability or extension module is added to the project later: the native variant includes it only after being rebuilt. The docs must state that extensions are fixed at build time for the native variant.
- The image is run on a CPU architecture it was not built for: the container runtime's normal "no matching platform" error appears. No silent emulation is promised.

## Requirements *(mandatory)*

### Functional Requirements

**Container contract (both variants)**

- **FR-001**: The project MUST provide a container image of the jircd server that runs without a Java installation on the host.
- **FR-002**: The image MUST read its configuration from one documented, fixed path inside the container, overridable by passing a different path as the container's argument.
- **FR-003**: The image MUST declare the default plaintext (6667) and TLS (6697) ports.
- **FR-004**: The server process inside the image MUST run as a dedicated non-root user with a fixed numeric id, so operators can grant file access predictably.
- **FR-005**: The image MUST work with a read-only root filesystem and read-only configuration and certificate mounts. Any path the server writes to by default MUST be absent, or MUST be a documented volume.
- **FR-006**: The server's log destinations MUST be set in the YAML configuration, in a logging section with two independent settings:
  - **Console logging**, on or off, defaulting to on. When on, every log line goes to standard output.
  - **Log file path**, optional with no default. When set, every log line also goes to that file, rotated by size and day with the current retention limits (50 MB per file, 30 days, 1 GB in total). When unset, the server writes no log file.
  The same rules apply to host installs and both image variants. The image ships with no log file set, so by default it writes nothing to its filesystem.
- **FR-006a**: A configuration that turns console logging off and sets no log file MUST be rejected with a message saying the server would have no log output. A log file path whose directory is missing or not writable MUST be rejected with a message naming the path and the reason.
- **FR-006b**: A configuration reload MUST apply changes to the logging settings without a restart. A reload whose logging settings are rejected MUST leave the previous logging setup in place, like any other rejected reload (FR-008).
- **FR-007**: The server MUST be the container's main process, so that signals sent by the container runtime reach it directly.
- **FR-008**: The hang-up signal MUST trigger the same configuration reload as on a host install, with the same validation and the same handling of a rejected configuration.
- **FR-009**: On a termination or interrupt signal, the server MUST stop accepting new connections, send each connected client an `ERROR` line stating that the server is shutting down, close the connections, and exit with status 0. All of this MUST finish within 10 seconds, or the server exits anyway.
- **FR-010**: When the configuration is missing, unreadable or invalid at startup, the process MUST exit with a non-zero status after logging a message that names the problem and the path it tried.
- **FR-011**: The JVM variant MUST size its memory from the container's memory limit, not the host's memory.

**Native variant**

- **FR-012**: The project MUST provide a second image variant whose server is a native executable, built from the same source revision and the same build definition as the JVM variant.
- **FR-013**: The native variant MUST offer exactly the capabilities and server extensions present in the source revision it was built from, and MUST behave the same as the JVM variant for every protocol, configuration, reload, shutdown and logging behaviour in this specification.
- **FR-014**: The native variant MUST support `OPER` login against Argon2-hashed credentials, TLS with PEM and PKCS12 certificates, and YAML configuration loading, with the same results as the JVM variant.
- **FR-015**: Native compatibility MUST be verified by an automated smoke test against the built native executable, covering: startup with a mounted configuration, client registration over plaintext and TLS, channel messaging, `OPER` with an Argon2 credential, configuration reload by signal, and graceful shutdown by signal.
- **FR-016**: The native variant MUST be documented as experimental, meaning built and smoke-tested in CI but not yet recommended for production. Promoting it to supported is a separate, later decision, made once it has met SC-004 in at least four consecutive weekly load-test runs. While it is experimental, a native load-comparison regression is reported but does not block merges. A native build or smoke-test failure still fails CI (FR-018).

**Build, CI and publishing**

- **FR-017**: Both variants MUST be buildable locally with the documented commands, using only a container runtime, without a Java or GraalVM installation on the host.
- **FR-018**: CI MUST build both variants and run their container smoke tests on changes to main, and MUST fail the build when either variant fails to build or fails its smoke test.
- **FR-019**: The JVM variant MUST be published as a multi-architecture image for amd64 and arm64. The native variant MUST be built for amd64 only, while it is experimental. Adding arm64 to the native variant later MUST NOT change the container contract.
- **FR-020**: Image tags MUST identify the variant (JVM or native) and the source revision. Mutable convenience tags (such as "latest") MUST only point at builds of main that passed CI.
- **FR-021**: The images MUST carry standard metadata identifying the source repository, revision and licence.

**Documentation**

- **FR-022**: The README MUST document running each variant, including: the configuration path, how to mount configuration and certificates, which ports to publish, how to reload and stop, how to view logs and how to enable a log file on a mounted volume, which paths are writable, the non-root user id, and how the native variant differs (extensions fixed at build time, garbage-collection trade-off, support status).

### Key Entities

- **Image variant**: A built container image of one kind, JVM or native, for one source revision and one CPU architecture. It has tags that name the variant and revision.
- **Container contract**: The fixed set of operator-facing conventions both variants share: configuration path, ports, user id, writable paths, signal behaviour and log destination.
- **Logging settings**: The configuration's logging section, made up of a console on/off switch and an optional log file path. It is validated at startup and on reload, and applies the same way on host installs and in both image variants.
- **Smoke test**: An automated end-to-end check run against a built image (not the test JVM), covering the scenarios listed in FR-015.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator with only a container runtime installed can go from the README to a server that accepts client connections in under 5 minutes, using only the documented commands and a sample configuration.
- **SC-002**: Every acceptance scenario in User Stories 1 and 2 passes for both variants in CI on every change to main.
- **SC-003**: A container stop with 100 connected clients completes within 10 seconds, and every one of those clients receives the shutdown `ERROR` line before its connection closes.
- **SC-004**: The native variant meets the project's existing load-test delivery budgets (each message reaches every recipient within 1 second at 1,000 connections). Its throughput and latency are compared with the JVM variant and recorded. A regression of more than 10% against the JVM variant is flagged, as the constitution's performance rule requires.
- **SC-005**: The native variant starts accepting connections within 1 second of container start on CI hardware, and its image is smaller than the JVM variant's.
- **SC-006**: With 100 idle connected clients, each variant's resident memory is measured and recorded in the feature's validation notes, so operators can choose a variant on data.
- **SC-007**: No server log line is written inside the container filesystem during a full smoke-test run with default settings.

## Assumptions

- The JVM variant is the reference implementation. The native variant must match it, not the reverse.
- The native variant is built with GraalVM Community Edition for JDK 25, matching the project's Java 25 toolchain. Oracle GraalVM-only features (such as the G1 collector or profile-guided optimisation) are out of scope, so the native variant uses the Community Edition garbage collector. Its effect is measured in SC-004 and SC-006.
- Extensions are discovered at startup from what is built in. Loading extension jars at runtime is not supported today and stays out of scope, and the native variant cannot support it at all.
- Graceful shutdown on stop (FR-009) is in scope, because container restarts and upgrades make it a routine operation. The `ERROR` text follows the existing `ERROR` conventions used for other disconnects.
- Reducing JVM start-up with the JDK's ahead-of-time cache is out of scope for this feature. It can follow once start-up time is measured (SC-005 records the native figure; the JVM figure is recorded for comparison).
- Orchestration manifests (Compose files, Kubernetes manifests, Helm charts) are out of scope, apart from at most one minimal example run command or Compose snippet in the README.
- The project is unreleased (0.1.0-SNAPSHOT), so changing the default logging behaviour needs no version bump. Host installs that relied on the built-in `logs/jircd.log` must now set the log file path in their configuration to keep a log file. The README and the sample configuration MUST say so.
- Log level and rotation limits stay fixed at their current values. Making them configurable is out of scope.
- Operators who need custom log routing can still supply their own logging configuration file through the standard logging-framework mechanism. In that case the configuration's logging section is ignored, and a startup log line says so.
- Images are built in CI on GitHub-hosted runners.
