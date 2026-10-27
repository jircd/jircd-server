# Implementation Plan: Userhost in NAMES

**Branch**: `015-userhost-in-names` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: `specs/015-userhost-in-names/spec.md`, [issue #117](https://github.com/jircd/jircd-server/issues/117).

## Summary

Add `jircd-capabilities:userhost-in-names`, following multi-prefix's ServiceLoader/module pattern. Register only `onNamesIdentity`, rendering the permitted `MemberSnapshot` as `nickname!ident@displayHost`. Declare additional identity width `I=U+H+2`; the existing generic budget catalog composes it with status width `P=2`. Wire the module into the server distribution and document configuration. No core behavior changes or new SPI are expected. Add only a protocol capability-name constant outside the module, plus tests/build/docs integration.

Planning is complete only as a reviewable proposal. Do not execute these tasks until user review authorizes implementation.

## Technical Context

**Language/Version**: Java 25, existing Gradle Kotlin DSL multi-module build.

**Primary Dependencies**: Existing jircd-protocol/jircd-core capability SPI and ServiceLoader; no new third-party runtime dependencies.

**Storage**: No new persistence; existing YAML configuration and per-session negotiated state.

**Testing**: Existing JUnit/AssertJ unit and real-socket integration fixtures; irctest pytest controller; tagged load tests. Tests first, failing before feature implementation.

**Target Platform**: Existing JVM IRC server deployment platforms.

**Project Type**: Multi-module network server with a capability provider library.

**Performance Goals**: Retain delivery max <1 s and JOIN-storm quiet p99 <500 ms, storm <15 s. Compare same-machine throughput against `a2a12ad` (or explicitly recorded implementation baseline); loss >10% or latency/CPU/allocation increases >10% require optimization or written review justification. Add a repeatable negotiated NAMES workload with p99 completion <1 s at 10/100/1,000 visible members, bounded queues and normal valid limits.

**Constraints**: 512-byte command sections including CRLF; UTF-8 accounting; display-host privacy; no per-member I/O or registry-wide enabled-provider scan. Pure hook with no mutable resources. Preserve existing capture/withdrawal semantics. Installed disabled providers also reserve bounds.

**Scale/Scope**: One capability module, protocol name, packaging, tests and docs; no WHO changes or legacy PROTOCTL. NAMES work remains O(visible members + output bytes).

## Constitution Check

| Gate | Before research | After design / implementation evidence required |
| --- | --- | --- |
| I: focused ownership, documented interfaces | PASS: provider owns identity rendering | PASS design: existing SPI only; provider Javadoc and docs task; static checks and independent review still required |
| II: deterministic tests and coverage | PASS: tests first planned | PASS design: unit/privacy/socket/byte/config/lifecycle matrix; coverage must not decrease, build floors 85% line / 75% branch |
| III: consistent user behavior and real usage | PASS: standard CAP and NAMES | PASS design: unnegotiated behavior/visibility preserved; scripted sockets and irctest recorded before done |
| IV: budgets and repeatable measurement | PASS: explicit budgets above | PASS design: tagged NAMES workload plus existing JOIN/delivery load comparisons, CPU/allocation evidence |
| Ordered merge gates | REQUIRED | Scoped tests → build/static checks → independent contributor review → real-use validation → performance evidence; rerun relevant checks after fixes |

No constitution exceptions. These are design checks, not claims that implementation tests have run.

## Project Structure

### Documentation (this feature)

```text
specs/015-userhost-in-names/
  spec.md
  checklists/requirements.md
  plan.md
  research.md
  data-model.md
  contracts/userhost-in-names.md
  quickstart.md
  tasks.md
  validation/                   # future measured evidence
```

### Source Code (repository root)

```text
jircd-capabilities/userhost-in-names/
  build.gradle.kts
  src/main/java/net/jircd/capabilities/userhostinnames/UserhostInNamesExtension.java
  src/main/resources/META-INF/services/net.jircd.core.extension.CapabilityExtension
  src/test/java/net/jircd/capabilities/userhostinnames/UserhostInNamesExtensionTest.java
jircd-protocol/src/main/java/net/jircd/protocol/CapabilityName.java
jircd-integration-tests/src/test/java/net/jircd/integration/
  UserhostInNamesTest.java
  UserhostInNamesLifecycleTest.java
  UserhostInNamesLoadTest.java
  NamesByteBudgetTest.java
settings.gradle.kts
jircd-server/build.gradle.kts
jircd-integration-tests/build.gradle.kts
README.md
specs/001-ircv3-server/contracts/server-configuration.md
```

**Structure Decision**: Depend on core/protocol from the new module; never reverse that direction. Mirror multi-prefix's runtime/test wiring, checking whether integration tests use server transitive runtime or explicit provider dependencies. Tests using the actual provider should live in the module or integration module, avoiding core test dependencies on capability modules.

## Design and Delivery

1. Register the standard capability name and identity-only hook through `CapabilityRegistrar.onNamesIdentity(ReplyBound, NamesIdentityHook)`. Use `ReplyLimits.identMax()` + `hostMax()` + 2 with checked long arithmetic. Current maxima are U=27/H=63, giving I=92 additional bytes; do not hard-code maxima in provider behavior.
2. Render only `MemberSnapshot.nickname()`, `ident()`, `displayHost()`. Core `MemberReplies` obtains the display hostname through `PresentedIdentity` only when identity decoration is negotiated. No host lookup, session re-query, cloak invocation or status formatting in this provider.
3. Preserve separate slots. Combined single-member bound is `S+2N+C+13+P+I<=512`; reserve P=2 when multi-prefix is installed and validate 366 separately (`S+N+C+29<=512`). Existing WHO/other hostmask limits also apply. Packing remains core's responsibility, after complete tokens are composed.
4. Verify discovery/config using a real distribution: `capabilities.userhost-in-names: enabled|disabled`, omitted means disabled on startup. Existing administrative toggles/reload, NEW/DEL, prune and explicit renegotiation behavior apply. No hard provider or negotiated dependency on multi-prefix.
5. Build a four-way negotiation matrix with exact token content, multibyte identities, cloaks, automatic and explicit listing paths, visibility, complete split lists and final 366. Verify unchanged WHO and provider absence. Test conservative invalid limits including disabled-provider installation and atomic failed reload.
6. Stage an irctest harness change in a coordinator-provisioned worktree. Preserve pre-existing shared harness modifications and the spec-014 freeze. Record revised controller patch, hashes, revision, dependencies and selection. Enable and declare both capabilities, then use identical revised controller on baseline/candidate; expected baseline lacks userhost, so target cases remain failures until candidate. Record every failure/skip delta; never claim the old freeze and new controller are identical.
7. Update README/config arithmetic: installing the new provider can reject formerly accepted large limits even when disabled. Document how to reduce S/N/C and keep the generic formula authoritative. Historical spec-014 validation remains unchanged.
8. Run required build and measured load/irctest gates, review checklist and other-vendor independent review before any future PR. Local trusted-branch Sonar analysis follows AGENTS.md when credentials are available without reading the credentials file.

## Complexity Tracking

No violations or new abstractions. A dedicated module is required by issue #117 and preserves established capability ownership.
