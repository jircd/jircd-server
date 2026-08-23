# Quickstart: Validating Away-Notify and Batch

Validates `away-notify` end-to-end over real client connections, the same way this project's
other capability-visible behavior is validated (`jircd-integration-tests`). Validates `batch`
at the mechanism level, since this release ships no production consumer of it (research.md,
Decision 4). See [`contracts/away-notify-and-batch.md`](./contracts/away-notify-and-batch.md)
for exact wire details and [`data-model.md`](./data-model.md) for what state is involved.

## Prerequisites

- Build this feature's code with `./gradlew build` from the repo root (compiles all modules,
  including the two new `jircd-capabilities` modules and `SessionWriter.enqueueBatch`).
- A basic familiarity with this project's capability-negotiation flow (`CAP LS`, `CAP REQ`,
  `CAP END`) — see `specs/001-ircv3-server/quickstart.md` for the general pattern; this feature
  adds two new capability names to that same flow, nothing new about the flow itself.

## `away-notify` — Scenario 1: Opted-in channel-mate sees an away/back cycle (User Story 1, P1)

1. Connect and register two clients, A and B; both `JOIN #lobby`.
2. On B: negotiate `away-notify` (`CAP REQ :away-notify`, confirm `CAP * ACK`).
3. On A: `AWAY :gone for lunch`.
4. **Expect**: B receives `:A!<ident>@<host> AWAY :gone for lunch`. A itself still gets its
   own `306 RPL_NOWAWAY`-style confirmation exactly as before this feature (unchanged).
5. On A: `AWAY` (clears it).
6. **Expect**: B receives `:A!<ident>@<host> AWAY` with no trailing parameter.

## `away-notify` — Scenario 2: Non-opted-in client sees nothing (User Story 1, edge case)

1. Repeat Scenario 1's steps 1 and 3, but skip B's `CAP REQ :away-notify` in step 2.
2. **Expect**: B receives no `AWAY` notice at all — its experience is identical to before this
   feature existed (FR-005).

## `away-notify` — Scenario 3: One notice regardless of shared-channel count (User Story 1)

1. Connect A and B; both `JOIN #one` and `JOIN #two` (sharing two channels).
2. On B: negotiate `away-notify`.
3. On A: `AWAY :testing dedup`.
4. **Expect**: B receives exactly one `AWAY` notice, not two (FR-004).

## `away-notify` — Scenario 4: No shared channel means no notice (Edge Case)

1. Connect A and B; A joins `#alpha`, B joins `#beta` — no channel in common.
2. On B: negotiate `away-notify`.
3. On A: `AWAY :testing scope`.
4. **Expect**: B receives nothing (FR-006).

## `away-notify` — Scenario 5: A reason-only change while already away still notifies (Edge
Case)

1. Connect A and B, both in `#lobby`; B negotiates `away-notify`.
2. On A: `AWAY :first reason`, then immediately `AWAY :second reason` (never clearing in
   between).
3. **Expect**: B receives two separate `AWAY` notices, one per reason.

## `batch` — Scenario 6: Two concurrent groups are unambiguous (User Story 2, P2)

Run the new `jircd-core` unit test covering `SessionWriter.enqueueBatch`
(`./gradlew :jircd-core:test --tests "*SessionWriter*Batch*"`, exact class name per
`plan.md`'s Project Structure). It constructs a `SessionWriter` directly against a captured
output stream (the same pattern `LivenessMonitorTest.java` already establishes), negotiates
`batch` and `message-tags` on the test session, issues two concurrent `enqueueBatch` calls, and
asserts:

1. Each group's opening `BATCH +<ref>` line appears before its own member messages and its own
   closing `BATCH -<ref>` line.
2. Every member message carries a `batch=<ref>` tag matching its own group's reference tag —
   never the other group's.
3. The two reference tags are distinct.

## `batch` — Scenario 7: Declining `batch` (or `message-tags`) yields unwrapped delivery
(FR-010)

Same test class, a second case: a session with neither capability negotiated (or with `batch`
alone, not `message-tags`) receiving the same `enqueueBatch` call. **Expect**: the output
stream contains only the member messages, byte-for-byte what `enqueueRaw` would have produced
for each one individually — no `BATCH` lines, no `batch` tag.

---

Each scenario above corresponds to one acceptance scenario or edge case in
[`spec.md`](./spec.md) and should become one `@Test` method — Scenarios 1-5 in the new
`jircd-integration-tests/.../AwayNotifyTest.java`, Scenarios 6-7 in the new `jircd-core` unit
test (see `plan.md` Project Structure for exact file paths).
