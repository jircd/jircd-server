# Validation environment

- **Base revision**: `d5a38b2` (origin/main when the worktree was created, 2026-11-03), then spec commit `004f55e`.
- **Host**: macOS (Apple Silicon), Docker Desktop engine 29.9.2 `linux/arm64`.
- **JDK**: Temurin 25.0.5+8 (SDKMAN, `~/.sdkman/candidates/java/25.0.5.0+8-tem`); Gradle 9.8.2 wrapper.
- **Local images are arm64.** The published native image and the performance comparison come from amd64 CI.

## Commands

- Scoped tests: `./gradlew :<module>:test --tests '<Class>'`
- Full gate: `./gradlew build` (tests, Spotless, SpotBugs, PMD, coverage floor)
- JVM image: `docker build --target jvm -t jircd:jvm .`
- Container suite: `./gradlew :jircd-integration-tests:containerTest -Djircd.image=jircd:jvm`

`jircd-server/jircd.yaml` (local credentials) is never opened or copied.
