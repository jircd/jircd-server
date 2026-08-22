# Implementation Plan: Away-Notify and Batch Capabilities

**Branch**: `011-away-notify-batch` | **Date**: 2026-08-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-away-notify-batch/spec.md`

## Summary

Two independently-toggleable IRCv3 capabilities, added as new `jircd-capabilities/` modules
following the exact pattern `message-tags`/`server-time`/`echo-message` already established.
`away-notify` broadcasts the existing `AWAY` command's state changes (set/change/clear) to
every opted-in session sharing a channel with the sender, deduplicated to one notice per
recipient regardless of shared-channel count. `batch` is general-purpose message-grouping
plumbing on `SessionWriter` — wraps a caller-supplied list of messages in `BATCH +ref`/`BATCH
-ref` framing with a `batch=ref` tag on each member, but only for a recipient who negotiated
both `batch` and `message-tags`; falls back to unwrapped delivery otherwise. Nothing in this
release produces a batch yet (per spec.md's Assumptions) — the mechanism ships ready but
unused, and its own correctness is verified with a synthetic two-concurrent-batches scenario
per SC-003, not through a production consumer.

## Technical Context

**Language/Version**: Java 25 (Gradle toolchain, `build.gradle.kts`)

**Primary Dependencies**: None new. Reuses `jircd-protocol` (`Command`, `Message`,
`MessageSerializer`) and `jircd-core` (`ClientSession`, `SessionWriter`,
`AbstractCapabilityExtension`) already depended on by every existing capability module.

**Storage**: N/A — negotiated capabilities are already in-memory, per-connection state
(`ClientSession.negotiatedCapabilities()`); away-status and batch reference tags are
transient, never persisted.

**Testing**: JUnit 5, end-to-end socket tests in `jircd-integration-tests` — the established
pattern for capability-visible wire behavior (mirrors how `echo-message`/`server-time` are
verified over real `CAP REQ` + a real send). The `batch` mechanism has no production caller in
this release, so its test drives `SessionWriter.enqueueBatch` directly from a helper command
already reachable in test builds, rather than inferring correctness from an unrelated command's
side effect (see research.md, decision 4).

**Target Platform**: JVM server process (existing `jircd-server` runtime) — no new platform
surface.

**Project Type**: Existing multi-module Gradle project — two new leaf modules
(`jircd-capabilities:away-notify`, `jircd-capabilities:batch`), same shape as the three
existing capability modules.

**Performance Goals**: Matches SC-001 — `away-notify` fan-out reuses the same
`writer().enqueueRaw(...)` primitive `NICK`'s existing channel-mate broadcast already uses per
recipient, with one added `Set`-based dedup pass over the sender's channel memberships (bounded
by the sender's own channel count and the largest channel's membership, not global connection
count). `batch` adds two extra raw sends (open/close) plus one tag-merge per member message,
only for recipients who negotiated it — zero added cost for recipients who haven't.

**Constraints**: A `batch=<ref>` tag MUST NOT reach a recipient who hasn't also negotiated
`message-tags` — `SessionWriter.enqueueRaw`'s raw path applies zero capability gating on
whatever tags a `Message` already carries (unlike the `OutboundMessage`/`CapabilityTagRenderer`
fan-out path), so `enqueueBatch` must check both capabilities itself before attaching any tag,
never relying on a caller to have checked first (research.md, decision 2).

**Scale/Scope**: `away-notify` fan-out is bounded by the sender's own channel memberships and
their members' union — the same bound `NICK`'s existing broadcast already accepts. `batch` is
bounded by whatever the caller passes as member messages; this feature adds no unbounded loop.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Code Quality**: Each capability is a single-responsibility module, matching the existing
  three exactly — no shared "capability framework" abstraction invented beyond what already
  exists (`AbstractCapabilityExtension`). `enqueueBatch` lives on `SessionWriter`, the class
  that already owns "the only path that writes to one session's socket" and already holds the
  `session` reference `enqueueBatch`'s capability check needs — no new class introduced solely
  to hold that check. PASS.
- **II. Testing Standards**: Both capabilities get end-to-end coverage in
  `jircd-integration-tests` before being marked done. `away-notify` is tested through its real,
  only trigger (`AWAY`). `batch`'s lack of a production trigger in this release is treated as a
  first-class test-design question, not skipped — research.md documents the chosen approach.
  PASS (planned; enforced at `/speckit-tasks` + `/speckit-implement`).
- **III. User Experience Consistency**: `away-notify` reuses the existing `AWAY` command shape
  (`Command.AWAY`, same prefix/param convention `AwayCommandHandler` already uses for the
  sender's own confirmation) rather than inventing a new client-facing message shape. `batch`
  follows the IRCv3 `batch` specification's own wire shape exactly (`BATCH +ref`/`BATCH -ref`,
  `batch=ref` tag) rather than a project-invented variant. PASS.
- **IV. Performance Requirements**: Budget stated above; both additions are bounded by
  already-bounded collections (a sender's channel memberships; a caller-supplied message list),
  no new unbounded iteration. PASS.

No violations to record in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/011-away-notify-batch/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md         # Phase 1 output (/speckit-plan command)
├── quickstart.md         # Phase 1 output (/speckit-plan command)
├── contracts/             # Phase 1 output (/speckit-plan command)
│   └── away-notify-and-batch.md
└── tasks.md              # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
jircd-core/
└── src/main/java/net/jircd/core/session/
    ├── SessionWriter.java                  # add enqueueBatch(type, typeParams, members) —
    │                                        # checks recipient's negotiated "batch" AND
    │                                        # "message-tags"; wraps with BATCH +/-ref and a
    │                                        # per-member batch=ref tag if both present, else
    │                                        # sends members via enqueueRaw unwrapped
    └── command/
        └── AwayCommandHandler.java         # after set/clear, dedup the sender's channel-mates
                                             # into a Set<ClientSession>, filter to
                                             # negotiatedCapabilities().contains("away-notify"),
                                             # send one Command.AWAY raw Message each

jircd-capabilities/
├── away-notify/                            # NEW module — package
│   └── src/main/java/net/jircd/capabilities/awaynotify/
│       └── AwayNotifyExtension.java        # NEW — AbstractCapabilityExtension, ID
│                                            # "away-notify", no hook overrides (the fan-out
│                                            # itself lives in AwayCommandHandler, matching how
│                                            # NICK's broadcast has no capability-extension of
│                                            # its own either)
│   └── src/main/resources/META-INF/services/net.jircd.core.extension.CapabilityExtension
│   └── build.gradle.kts                    # mirrors echo-message's exactly
│
└── batch/                                  # NEW module
    └── src/main/java/net/jircd/capabilities/batch/
        └── BatchExtension.java             # NEW — AbstractCapabilityExtension, ID "batch",
                                             # no hook overrides (SessionWriter.enqueueBatch
                                             # does its own capability check directly, the same
                                             # message-tags-hardcoded-string precedent
                                             # CapabilityTagRenderer/TagmsgCommandHandler
                                             # already establish for jircd-core-can't-depend-
                                             # on-jircd-capabilities reasons)
    └── src/main/resources/META-INF/services/net.jircd.core.extension.CapabilityExtension
    └── build.gradle.kts

jircd-server/
└── build.gradle.kts                        # add runtimeOnly for both new modules, alongside
                                             # the three existing capability runtimeOnly lines

settings.gradle.kts                         # include the two new module paths

jircd-integration-tests/
└── src/test/java/net/jircd/integration/
    ├── AwayNotifyTest.java                 # NEW — end-to-end: opted-in channel-mate receives
    │                                       # away/back/reason-change notices; non-opted-in
    │                                       # receives none; one notice regardless of shared-
    │                                       # channel count; no notice across non-shared channels
    └── BatchTest.java                      # NEW — end-to-end: opted-in recipient sees correct
                                             # BATCH +/-ref framing and per-member batch= tag
                                             # around two concurrent batches; non-opted-in
                                             # recipient sees unwrapped messages only; a
                                             # batch-only-no-message-tags recipient also gets
                                             # unwrapped delivery (research.md, decision 2)
```

**Structure Decision**: No changes to existing modules' public shape beyond the two additions
called out above (`SessionWriter.enqueueBatch`, `AwayCommandHandler`'s new fan-out). Two new
leaf modules under `jircd-capabilities/`, identical in shape to the three that already exist.
End-to-end coverage lands in `jircd-integration-tests`, matching every other capability-visible
behavior in this project.

## Complexity Tracking

*No violations — table intentionally omitted.*
