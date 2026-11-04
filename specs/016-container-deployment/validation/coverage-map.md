# Coverage map: requirements → evidence

Tests marked *(container)* run with `containerTest` against a built image. All others run in `./gradlew build`.

| Requirement | Evidence |
|---|---|
| FR-001 image runs without host Java | `ContainerContractTest` *(container)*; quickstart §1–2 |
| FR-002 fixed config path, overridable | `ContainerFailureTest.noConfigurationMounted` (names `/etc/jircd/jircd.yaml`) *(container)*; Dockerfile `CMD` |
| FR-003 ports 6667/6697 | `ContainerContractTest.plaintextAndTlsClientsChat` *(container)* |
| FR-004 non-root fixed uid | `ContainerContractTest.runsAsUid65532` *(container)* |
| FR-005 read-only root, read-only mounts | every container test runs with `--read-only`; `ContainerContractTest` and `ContainerFailureTest` mount `/etc/jircd` read-only (the signal tests use a volume so the config can change deterministically on Docker Desktop) |
| FR-006 logging section | `LoggingConfigurationLoaderTest`, `LoggingSettingsApplierTest`, `LoggingConfigurationTest`; `ContainerContractTest.logsGoToStdout…`, `…logFileOnAMountedVolume` *(container)* |
| FR-006a logging validation | `LoggingConfigurationLoaderTest` (each rejection message) |
| FR-006b logging reload | `ConfigurationReloaderListenerTest`, `LoggingConfigurationTest` reload cases; `ContainerSignalTest.hupAppliesLoggingAndCapabilityChanges` *(container)* |
| FR-007 server is PID 1 | the exec-form `ENTRYPOINT` in the `Dockerfile` (no shell or init wrapper); `ContainerSignalTest` shows signals reach the server *(container)* |
| FR-008 HUP reload, rejected reload kept | `ContainerSignalTest` *(container)* |
| FR-009 graceful shutdown, exit 0 within 10 s | `GracefulShutdownTest` (incl. shutdown before start), `ShutdownSignalHandlerTest` (signal before the server exists exits 0); `ContainerSignalTest.stopWith100Clients…`, `…interruptBehavesLikeTerm` *(container)*. A TERM before the JVM reaches `main` gets the JVM default (143): no handler can exist yet |
| FR-010 startup errors name the problem | `LoggingConfigurationLoaderTest` (file errors), `ConfigurationFileErrorTest`; `ContainerFailureTest` *(container)* |
| FR-011 heap sized from container limit | Dockerfile `-XX:MaxRAMPercentage=75`; quickstart walkthrough |
| FR-012–FR-016 native variant | US3 (not in MVP) |
| FR-017–FR-021 build, CI, publishing | US4 (not in MVP); FR-017 local build covered by quickstart §1 |
| FR-022 README | Polish T047 (not in MVP) |
| SC-001 under 5 minutes from the README | Polish T049 walkthrough |
| SC-002 Stories 1–2 pass for both variants in CI | `containerTest` (JVM in MVP; native in US3, CI in US4) |
| SC-003 stop with 100 clients under 10 s, all get `ERROR` | `ContainerSignalTest.stopWith100Clients…` *(container)*; `GracefulShutdownTest` |
| SC-004–SC-006 native performance, start-up, memory | US3 `ContainerDeliveryLoadTest` (not in MVP) |
| SC-007 nothing written to the container's root filesystem by default | every container test runs with `--read-only`, which covers the root filesystem only; mounted volumes (a log-file volume, the signal tests' configuration volume) are not checked |
