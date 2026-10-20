# Implementation Plan: Outbound Queue Overflow Policy

**Branch**: `013-outbound-sendq-policy` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/013-outbound-sendq-policy/spec.md`

## Summary

Replace `SessionWriter`'s fixed 256-entry queue with a per-connection byte budget (`sendqLimitBytes`,
server-wide, validated, applied to connections opened after a reload), keep close-on-overflow as the
policy, and make overflow visible: a best-effort `ERROR :Closing Link: <host> (SendQ exceeded)` to
the client, a distinct `SendQ exceeded` QUIT/disconnect reason, and one overflow entry in
`ConnectionMonitorLog` carrying the limit and a running total. T003 records a 31,286 B peak
(3x = 93,858 B), retaining the 512 KiB default. A separate
single-wave survival test targets 2,000 simultaneous JOINs; #48's wave-of-100 latency test stays
unchanged. Fan-out reduction (issue option 3) is not part of this plan; see [research.md](research.md) D1.

Decisions accepted by the user (spec.md Clarifications): storm of 2,000, server-wide limit, no
fan-out change, backlog discard on overflow, bounds 16 KiB floor / 512 KiB default / 2 MiB ceiling.
Follow-up decisions replace the fixed
allowance with per-message memoized pure capability contribution bytes and separate single-wave
survival from wave-of-100 latency. Accounting is a conservative upper bound; rendering stays at
drain time (see contracts/outbound-accounting.md).

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain), multi-module

**Primary Dependencies**: none new (SLF4J logging, SnakeYAML config already used)

**Storage**: N/A

**Testing**: JUnit 6 (6.1.3) + AssertJ; unit tests in `jircd-core`, end-to-end over real sockets in
`jircd-integration-tests`; `@Tag("load")` for the storm (`./gradlew loadTest`)

**Target Platform**: JVM server (macOS dev, Linux CI)

**Project Type**: multi-module server (library modules + app)

**Performance Goals**: 2,000-client simultaneous JOIN storm completes with 0 overflow disconnects;
existing wave-of-100 JOIN-storm budgets hold (storm < 15 s, `#quiet` p99 < 500 ms, max < 1 s); no regression
> 10% against the stored baseline on `ConcurrentConnectionScaleLoadTest` / `Story1DeliveryLatencyLoadTest`

**Load-test resources**: integration `loadTest` may set a documented 2 GiB maximum heap: the
T003 probe exhausts 512 MiB but reaches all 2,000 clients with 2 GiB. Ordinary test settings and
all existing latency bounds stay unchanged; single-wave survival has no latency assertion.

**Constraints**: sender never blocks on a slow recipient; queued protocol data per connection
is bounded by limit + one message (additional JVM object/metadata overhead applies); byte
accounting on the fan-out hot path must not serialize per recipient; compute each enabled
capability's escaped contribution byte metadata once per message/capability, with a message-scoped
thread-safe memo, and sum only the recipient's selected entries. Document/test purity at the
CapabilityExtension interface and in both existing implementations. Preserve selection consistency
between accounting and drain across negotiation/reload; account for merge collisions/truncation
conservatively and validate the bound against rendered lines, including legal large server tags

**Scale/Scope**: thousands of connections, channels of 2,000+ members; worst-case queued wire data =
connections x limit, documented with additional JVM object/metadata overhead

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Read from `.specify/memory/constitution.md` (Principles I-IV, Quality Gates):

| Gate | Status | Note |
|------|--------|------|
| Code quality (I) | PASS | Existing modules; message-scoped accounting metadata and capability purity contract |
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
│   ├── monitoring-log.md
│   ├── outbound-accounting.md
│   └── load-tests.md
└── tasks.md
```

### Source Code (repository root)

```text
jircd-core/src/main/java/net/jircd/core/
├── config/ServerConfiguration.java      # + sendqLimitBytes, default/min/max constants
├── config/ConfigurationLoader.java      # parse + validate sendqLimitBytes
├── session/OutboundMessage.java         # message-scoped capability contribution byte memo
├── session/SessionWriter.java           # byte budget, overflow state machine, ERROR attempt
├── session/ConnectionHandler.java       # pass limit supplier, new overflow callback + reason
├── extension/CapabilityExtension.java   # pure message contribution contract
└── session/ConnectionMonitorLog.java    # sendqOverflow(...) entry + running total
jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java  # wire supplier
jircd-core/src/test/java/net/jircd/core/{config,session}/                # unit tests
jircd-capabilities/{message-tags,server-time}/src/test/java/            # contribution purity tests
jircd-integration-tests/src/test/java/net/jircd/integration/
├── JoinStormLoadTest.java               # merged via PR #59, keep waves of 100 and all bounds
├── SingleWaveJoinStormLoadTest.java     # 2,000 simultaneous JOINs, survival only
└── SendqOverflowTest.java               # non-reading client, ERROR line, reason, config
jircd-integration-tests/build.gradle.kts # documented 2 GiB loadTest heap if needed
README.md                                # documented policy (FR-012)
```

**Structure Decision**: existing modules only. The limit lives in `jircd-core` next to the writer
and the other connection settings (`rateLimit`, `keepAliveFrequencySeconds`).

## Complexity Tracking

No constitution violations. The message-scoped memo is justified by avoiding repeated capability
byte computation during fan-out; its lifetime must not create an unbounded global cache. The
larger load-test heap and separate survival target follow the recorded user decisions, not a
relaxation of the existing latency regression gate.
