# Implementation Plan: Outbound Queue Overflow Policy

**Branch**: `013-outbound-sendq-policy` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/013-outbound-sendq-policy/spec.md`

## Summary

Replace `SessionWriter`'s fixed 256-entry queue with a per-connection byte budget (`sendqLimitBytes`,
server-wide, validated, applied to connections opened after a reload), keep close-on-overflow as the
policy, and make overflow visible: a best-effort `ERROR :Closing Link: <host> (SendQ exceeded)` to
the client, a distinct `SendQ exceeded` QUIT/disconnect reason, and one overflow entry in
`ConnectionMonitorLog` carrying the limit and a running total. The default is sized from a measured
JOIN-storm volume so the #48 storm of 2,000 simultaneous JOINs completes without waves. Fan-out
reduction (issue option 3) is not part of this plan; see [research.md](research.md) D1.

Decisions accepted by the user (spec.md Clarifications): storm of 2,000, server-wide limit, no
fan-out change, backlog discard on overflow, bounds 16 KiB floor / 512 KiB default / 2 MiB ceiling.

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain), multi-module

**Primary Dependencies**: none new (SLF4J logging, SnakeYAML config already used)

**Storage**: N/A

**Testing**: JUnit 6 (6.1.3) + AssertJ; unit tests in `jircd-core`, end-to-end over real sockets in
`jircd-integration-tests`; `@Tag("load")` for the storm (`./gradlew loadTest`)

**Target Platform**: JVM server (macOS dev, Linux CI)

**Project Type**: multi-module server (library modules + app)

**Performance Goals**: 2,000-client simultaneous JOIN storm completes with 0 overflow disconnects;
existing JOIN-storm budgets hold (storm < 15 s, `#quiet` p99 < 500 ms, max < 1 s); no regression
> 10% against the stored baseline on `ConcurrentConnectionScaleLoadTest` / `Story1DeliveryLatencyLoadTest`

**Constraints**: sender never blocks on a slow recipient; per-connection memory bounded by
limit + one message; byte accounting on the fan-out hot path must not serialize per recipient

**Scale/Scope**: thousands of connections, channels of 2,000+ members; worst-case queue memory =
connections x limit, documented

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Read from `.specify/memory/constitution.md` (Principles I-IV, Quality Gates):

| Gate | Status | Note |
|------|--------|------|
| Code quality (I) | PASS | Small change in `SessionWriter`, config record, log class; no new module |
| Testing (II): tests first | PASS (planned) | tasks.md orders failing tests before each implementation task |
| UX consistency (III) | PASS | New client-visible text (`ERROR`, QUIT reason) specified in `contracts/client-visible.md` and matches the existing `ERROR :Closing Link` precedent of KILL |
| Performance (IV) | PASS (planned) | Budget declared in Technical Context; load test re-runnable via `loadTest`; before/after measurement is a task |
| Quality gates | PASS (planned) | `./gradlew build` (tests, Spotless, SpotBugs, PMD, 85%/75% coverage) is the final task |

Post-design re-check: no violations; Complexity Tracking not needed.

## Project Structure

### Documentation (this feature)

```text
specs/013-outbound-sendq-policy/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── server-configuration.md
│   ├── client-visible.md
│   └── monitoring-log.md
└── tasks.md
```

### Source Code (repository root)

```text
jircd-core/src/main/java/net/jircd/core/
├── config/ServerConfiguration.java      # + sendqLimitBytes, default/min/max constants
├── config/ConfigurationLoader.java      # parse + validate sendqLimitBytes
├── session/SessionWriter.java           # byte budget, overflow state machine, ERROR attempt
├── session/ConnectionHandler.java       # pass limit supplier, new overflow callback + reason
└── session/ConnectionMonitorLog.java    # sendqOverflow(...) entry + running total
jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java  # wire supplier
jircd-core/src/test/java/net/jircd/core/{config,session}/                # unit tests
jircd-integration-tests/src/test/java/net/jircd/integration/
├── JoinStormLoadTest.java               # brought in from feature/issue-48-join-storm-load-test, waves removed
└── SendqOverflowTest.java               # non-reading client, ERROR line, reason, config
README.md                                # documented policy (FR-012)
```

**Structure Decision**: existing modules only. The limit lives in `jircd-core` next to the writer
and the other connection settings (`rateLimit`, `keepAliveFrequencySeconds`).

## Complexity Tracking

No constitution violations.
