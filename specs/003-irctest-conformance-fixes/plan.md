# Implementation Plan: irctest Conformance Fixes

**Branch**: `003-irctest-conformance-fixes` | **Date**: 2026-08-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-irctest-conformance-fixes/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Seven small, targeted corrections to reply content of already-implemented commands, found
by running the third-party irctest conformance suite against a real `jircd-server`
instance: `QUIT` now sends `ERROR` before closing (FR-001); an empty `USER` realname is
rejected (FR-002); `NAMES`/`JOIN`'s `353` visibility symbol reflects a channel's actual
`private`/`secret` state instead of always claiming public (FR-003); `LUSERS`' reply text
matches the conventional parseable shape, now reporting a real invisible-user count instead
of an implicit zero (FR-004); bare `WHOWAS` uses the RFC-precise `431` instead of the
generic `461` (FR-005); and `WHO`/`WHOIS` restore the RFC 2812 server-name field, which for
`WHOIS` also means correctly parsing its optional `WHOIS <server> <nick>` two-parameter form
for the first time (FR-006/FR-007, discovered during planning). Two related findings are
explicitly confirmed as no-change (FR-008, FR-009) per this feature's Clarifications. No new
module, no new dependency, no new entity — every change is inside an existing handler.

## Technical Context

**Language/Version**: Java 25 (LTS) — unchanged from `001-ircv3-server`/`002-extended-irc-commands`.

**Primary Dependencies**: None new — every fix reuses existing `jircd-core` state
(`ClientSession.userModes()`/`isAway()`, `Channel.activeModes()`, `NicknameRegistry`) and
existing numeric-reply infrastructure (`NumericReply.RPL_WHOISSERVER`/`ERR_NONICKNAMEGIVEN`
already exist in `jircd-protocol`'s full catalog, per `001-ircv3-server`'s "wire-protocol
recognition MUST represent the full RFC set").

**Storage**: N/A — unchanged; no new state, only derived values computed at reply time from
state already tracked (e.g. `LUSERS`' invisible count is a filter over the already-tracked
per-session `UserMode.INVISIBLE`).

**Testing**: JUnit 5 (Jupiter) + AssertJ unit tests; protocol-level integration tests over
real TCP sockets, identical approach to `001-ircv3-server`/`002-extended-irc-commands`. This
feature additionally has an external validation channel neither prior feature had: the
irctest suite itself (via the controller now in `github.com/jircd/irctest`), used to confirm
each fix against the exact conformance test that originally found it (spec.md SC-005).

**Target Platform**: Linux server (unchanged).

**Project Type**: Single backend network service — same multi-module Gradle build; no new
subproject. Every change lands in an existing file in `jircd-core`.

**Performance Goals**: No new Success Criteria beyond spec.md's SC-001 through SC-005
(functional correctness) — none of these seven commands is a per-message hot path; each
fix adds at most one extra reply line or one extra positional field to an already-low-
frequency reply (registration, disconnect, and query commands, not per-message fan-out).

**Constraints**: Every fix MUST reuse an already-established reply pattern or already-tracked
state rather than introducing a new one (research.md's own framing for each decision) — the
one constraint with real implementation weight is FR-006/FR-007's `WHOIS` two-parameter form,
which changes how `WhoisCommandHandler` reads its target argument, not just what it replies.

**Scale/Scope**: 7 functional requirements with behavior changes (FR-001 through FR-007), 2
requirements confirming no change (FR-008, FR-009), 5 user-facing files touched
(`QuitCommandHandler`, `UserCommandHandler`, `JoinCommandHandler`, `LusersCommandHandler`,
`WhowasCommandHandler`, `WhoCommandHandler`, `WhoisCommandHandler` — 7 files), 0 new
entities, 0 new configuration keys.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Status |
|---|---|---|
| I. Code Quality | Every fix is a small, single-purpose change to a handler that already exists and already has a single clear responsibility; no new abstractions introduced; reused patterns (research.md) avoid duplicating the same fix's logic across call sites (e.g. `JoinCommandHandler.sendNamesReply` is the single shared 353-rendering call site both `JOIN` and `NAMES` already use). | PASS |
| II. Testing Standards | Every FR gets integration coverage proving the specific reply content changed, plus the irctest test that originally found each gap re-run to confirm it now passes (spec.md SC-005) — a second, independent verification channel beyond this project's own test suite. | PASS |
| III. User Experience Consistency | Every changed reply reuses an existing numeric-reply convention already established elsewhere in this codebase (research.md documents the specific precedent for each) — no new reply shape is invented from scratch. | PASS |
| IV. Performance Requirements | No new per-message hot path; each fix touches a low-frequency reply (registration, disconnect, or an explicit query command) with no throughput target of its own, consistent with `002-extended-irc-commands`'s identical reasoning for its own low-frequency commands. | PASS |

No violations requiring justification. Complexity Tracking table below is intentionally
empty.

**Post-Design Re-check** (after Phase 1 — quickstart.md, contract updates): No new
violations introduced. FR-006/FR-007's `WHOIS` two-parameter parsing fix, discovered during
Phase 0 research rather than anticipated in spec.md, is the one piece of this plan that
does more than reorder/reword an existing reply — but it's still a strict widening of what
`WhoisCommandHandler` already accepts (a previously-misparsed valid RFC 2812 form now
parses correctly), not a new capability or a behavior change for any input that already
worked. Gate remains PASS.

## Project Structure

### Documentation (this feature)

```text
specs/003-irctest-conformance-fixes/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

No `data-model.md` — spec.md's Key Entities section already states this feature introduces
no new entities. No `contracts/` directory of its own — this feature corrects existing
contract text in `001-ircv3-server`/`002-extended-irc-commands`'s own contract files
directly (see below), rather than defining a new contract surface.

### Source Code (repository root)

No new subproject — every file below is an existing file from `001-ircv3-server`'s or
`002-extended-irc-commands`'s own Project Structure.

```text
jircd-core/
└── src/main/java/net/jircd/core/session/command/
    ├── QuitCommandHandler.java      # claim CLOSING, then send ERROR, via disconnectCleanup.disconnectWithError() (FR-001, #151)
    ├── UserCommandHandler.java      # reject empty realname with 461 (FR-002)
    ├── JoinCommandHandler.java      # sendNamesReply: compute 353's visibility symbol (FR-003)
    ├── LusersCommandHandler.java    # reword 251 text, real invisible count (FR-004)
    ├── WhowasCommandHandler.java    # bare-command case: 431 not 461 (FR-005)
    ├── WhoCommandHandler.java       # sendWhoReply: add server-name field to 352 (FR-006)
    └── WhoisCommandHandler.java     # add 312 RPL_WHOISSERVER; parse WHOIS <server> <nick>
                                     # two-parameter form correctly (FR-006/FR-007)

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    └── IrctestConformanceFixesTest.java   # new — one test per FR-001..FR-007

specs/001-ircv3-server/contracts/irc-protocol-commands.md
    # update QUIT (ERROR reply), USER (empty-realname rejection), NAMES/JOIN (353 symbol),
    # WHO (352 field layout), WHOIS (312 addition, two-parameter form) rows to reflect the
    # corrected behavior

specs/002-extended-irc-commands/contracts/irc-protocol-commands-extended.md
    # update LUSERS (251 text shape) and WHOWAS (431 for bare command) rows
```

**Structure Decision**: No new module, no new documentation artifact type. This feature is
purely corrective within the existing `jircd-core` command-handler layer, and its
documentation footprint is correspondingly small: a `plan.md`/`research.md`/`quickstart.md`
triad for itself, plus direct, targeted corrections to the two prior features' own contract
files (the same "keep contracts accurate to current behavior" precedent already followed
when `002-extended-irc-commands`'s `WHOWAS` hostname-privacy fix updated its own contract
mid-implementation).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally empty (see Constitution Check above, both gates PASS).
