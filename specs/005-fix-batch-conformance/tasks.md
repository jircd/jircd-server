---

description: "Task list for the Fix Batch of Conformance Bugs feature"
---

# Tasks: Fix Batch of Conformance Bugs

**Input**: Design documents from `/specs/005-fix-batch-conformance/`

**Prerequisites**: plan.md, spec.md, research.md, quickstart.md (all present)

**Tests**: Included. The project constitution's Testing Standards principle mandates automated
coverage for every feature's primary behavior, and every item in this batch is itself a bug fix
("every bug fix MUST include a regression test that fails before the fix and passes after").
This feature additionally re-runs the exact irctest tests that originally surfaced each finding
(SC-003), the same two-channel verification precedent `003`/`004` both established.

**Organization**: Tasks are grouped by user story (spec.md Stories 1-6, priority order P1→P3).
Two shared-file coordination points exist across stories — see Dependencies below — everything
else is independently parallelizable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US6)
- File paths are relative to the repository root.

## Path Conventions

No new module — every path is an existing file from `001-ircv3-server`'s own module layout
(`jircd-core/session/command`, `jircd-core/session`, `jircd-core/capability`), plus
`jircd-server-extensions/admin` (Story 6), `jircd-server` (composition root), and
`jircd-integration-tests` (protocol-level tests).

---

## Phase 1: Setup

Not applicable — no new module, no new build dependency, no new tooling.

## Phase 2: Foundational

Not applicable — no single blocking prerequisite spans all six stories. Two pairs of stories
share a file (see Dependencies) but neither blocks the other four.

---

## Phase 3: User Story 1 - A Nickname Change Is Visible to Everyone Who Needs to Know (Priority: P1)

**Goal**: `NICK` confirms itself to the changing client and notifies every channel co-member.

**Independent Test**: Two clients share a channel; one changes nickname; verify both receive a
`NICK` message.

- [X] T001 [US1] In `handle()`, after the existing claim/release/`setNickname` sequence, capture
  the *old* hostmask before the mutation, build one `NICK <requested>` message with it as the
  prefix, and enqueue it to the session's own writer plus every member of every channel in
  `session.channelMemberships()` — the same fan-out shape `JoinCommandHandler`'s `JOIN`
  notification already uses, in
  `jircd-core/src/main/java/net/jircd/core/session/command/NickCommandHandler.java`
  (research.md "Story 1 — NICK broadcast")
- [X] T002 [US1] Integration test: a client with no channel memberships receives its own `NICK`
  confirmation; two clients sharing a channel both receive the notification when one changes
  nickname, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/NickBroadcastTest.java`
  (depends on T001)

**Checkpoint**: `NICK` broadcast fully functional and independently testable.

---

## Phase 4: User Story 2 - Direct Messages Honor the Same Guarantees as Channel Messages (Priority: P1)

**Goal**: `echo-message` works for DMs; an empty-body `PRIVMSG` is rejected.

**Independent Test**: With echo-message negotiated, send a DM and verify the sender is echoed;
send an empty-body DM and verify it's rejected.

- [X] T003 [US2] After the existing fan-out loop, when `echoToSender` is true and `target` isn't
  a channel, separately enqueue `outbound` to `session` (the loop itself only ever iterates the
  real recipient for a DM, so it can never self-echo today), in
  `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
  (research.md "Story 2", depends on nothing — but see Dependencies for T009's later edit to
  this same file)
- [X] T004 [US2] Extend the existing `params().size() < 2` guard to also reject an empty `body`
  string with `412 ERR_NOTEXTTOSEND`, in the same file (depends on T003, same file)
- [X] T005 [US2] Integration test: a client with echo-message negotiated sees its own DM echoed
  back; an empty-body DM is rejected with `412`, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/DirectMessageGuaranteesTest.java`
  (depends on T004)

**Checkpoint**: DM delivery guarantees fully functional and independently testable.

---

## Phase 5: User Story 3 - Connection and Capability Negotiation Follow the Documented Wire Format (Priority: P2)

**Goal**: `PING`/`PONG`, `CAP`, message-tag length limits, and client-tag forwarding all match
their documented wire shapes; invalid UTF-8 during registration resolves definitively.

**Independent Test**: Send `PING` with/without a token and verify the exact reply shape;
exercise `CAP LIST`/an invalid subcommand/a repeated-capability `NAK`; verify a client tag
survives relay; verify the two length limits are independent; verify invalid UTF-8 during
registration closes the connection.

- [X] T006 [P] [US3] Change `PONG`'s reply to `List.of(serverName.get(), token)`; when
  `message.params().isEmpty()`, reply `409 ERR_NOORIGIN` instead of substituting
  `session.connectionId()`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/PingPongCommandHandler.java`
  (research.md "Story 3")
- [X] T007 [P] [US3] Split the `LS, LIST -> handleLs(session)` case: `LIST` sends a new reply
  built from `session.negotiatedCapabilities()` via the existing
  `CapabilityNegotiationGrammar` reply path; change the `subcommand == null` branch's reply
  from `421` to `410 ERR_INVALIDCAPCMD`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/CapCommandHandler.java`
- [X] T008 [P] [US3] Change `NegotiationResult`'s `accepted`/`declined` fields from
  `Set<String>` to `List<String>`, preserving request order and duplicates for the reply-echo
  path — `session.negotiatedCapabilities()` (a `Set`, used for state) is unaffected, in
  `jircd-core/src/main/java/net/jircd/core/capability/CapabilityNegotiator.java`
- [X] T009 [US3] Switch from `OutboundMessage.now(presentedForm, commandName, target, body)`
  (4-arg, defaults `clientTags` to empty) to the 5-arg overload, passing `message.tags()`
  through, in
  `jircd-core/src/main/java/net/jircd/core/session/command/MessageCommandHandler.java`
  (same file as T003/T004 — depends on T004 completing first, see Dependencies)
- [X] T010 [P] [US3] Seed `render()`'s `tags` map with `message.clientTags()` before the
  capability-contributed-tags loop, so a capability tag can still win a key collision but a
  client tag otherwise survives, in
  `jircd-core/src/main/java/net/jircd/core/session/CapabilityTagRenderer.java`
  (depends on T009 for end-to-end effect, but is its own file — can be written in parallel with
  T009, just won't be observably correct until both land)
- [X] T011 [P] [US3] Split the combined `lineBytes.length + 2 > MAX_LINE_LENGTH_BYTES` check
  into two independent checks: the raw line's tag section (`@...` up to the first unescaped
  space) capped at 4096 bytes, the remaining command+params section capped at 512 bytes — both
  still checked before UTF-8 decoding, in
  `jircd-core/src/main/java/net/jircd/core/session/ConnectionHandler.java`
- [X] T012 [US3] Change the invalid-UTF8 branch in `processLine()` from "reply `421`, return,
  connection stays open" to enqueuing `ERROR :Malformed message (invalid UTF-8)` and calling
  `disconnectCleanup.cleanup(...)` — the same shape `QuitCommandHandler`/`KillCommandHandler`
  already use, in the same file (same file as T011 — sequential)
- [X] T013 [US3] Integration tests: `PING`/`PONG` two-param shape and bare-`PING` `409`; `CAP
  LIST` reports only negotiated capabilities; an invalid `CAP` subcommand gets `410`; a
  repeated-capability `CAP REQ` declined echoes back with duplicates intact; a client tag
  survives relay to a message-tags-negotiated recipient; a line whose tag section alone exceeds
  4096 bytes is rejected even under the old combined 4608 threshold; invalid UTF-8 in a
  pre-registration `USER` line closes the connection, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/ConnectionAndCapPrecisionTest.java`
  (depends on T006, T007, T008, T009, T010, T011, T012)

**Checkpoint**: Connection/negotiation wire-format precision fully functional and independently
testable.

---

## Phase 6: User Story 4 - Channel Membership Commands Match Their Documented Grammar (Priority: P2)

**Goal**: Multi-channel `JOIN` works; a joiner sees the existing topic; `KICK` defaults its
comment; `MODE +b` accepts both query forms; `+o`/`+v` on a nonexistent nickname gets the
precise error.

**Independent Test**: Join two channels in one command; join a channel with an existing topic;
kick with no comment; query a ban list with `+b`; grant operator status to a nonexistent
nickname.

- [X] T014 [P] [US4] Split the channel-name and (if present) key parameters on `,`, looping the
  existing single-channel logic (validation → `getOrCreate` → gates → membership →
  notification → `sendNamesReply`) once per named channel, pairing each with its positional key
  if any; a channel that fails its own check is skipped, others in the same command still
  process, in
  `jircd-core/src/main/java/net/jircd/core/session/command/JoinCommandHandler.java`
  (research.md "Story 4")
- [X] T015 [US4] After the `JOIN` notification fan-out and before `sendNamesReply`, if
  `channel.topic()` is present, send `332 RPL_TOPIC`/`333 RPL_TOPICWHOTIME` to the joining session
  only, in the same file (same file as T014 — sequential)
- [X] T016 [P] [US4] Change the no-comment branch's default from `null` (omitted parameter) to
  `session.nickname()`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/KickCommandHandler.java`
- [X] T017 [P] [US4] Change the bare-ban-list-query condition to accept both `"b"` and `"+b"`,
  in `jircd-core/src/main/java/net/jircd/core/session/command/ModeCommandHandler.java`
- [X] T018 [US4] Add a `NicknameRegistry` constructor parameter; in the `MEMBER`-kind branch of
  `applyChanges`, look up the target nickname in the registry before the existing
  `channel.findMember` check — reply `401 ERR_NOSUCHNICK` (the same reply `WHOIS`/`KILL` use
  for this exact condition) if it isn't connected at all, leaving `441
  ERR_USERNOTINCHANNEL` for the "connected but not a member here" case, in the same file (same
  file as T017 — sequential); update the constructor call in
  `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java` (see Dependencies)
- [X] T019 [US4] Integration tests: `JOIN #one,#two` joins both; a joiner to a topic'd channel
  receives `332`/`333`; a no-comment `KICK` defaults to the kicker's nickname; `MODE #chan +b`
  returns the ban list; `MODE #chan +o <nonexistent-nick>` returns `401` not `441`, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/ChannelMembershipGrammarTest.java`
  (depends on T015, T016, T017, T018)

**Checkpoint**: Channel-membership grammar completeness fully functional and independently
testable.

---

## Phase 7: User Story 5 - User and Server Information Queries Are Complete (Priority: P3)

**Goal**: `USERHOST` and `INFO` are implemented; `WHOIS` on a missing nickname still sends
`318`; an exact-nickname `WHO` bypasses invisibility; `AWAY :` (empty argument) clears.

**Independent Test**: Query host information for a nickname; query server information; look up
a nonexistent nickname; look up an invisible user by exact nickname; clear away status with an
empty argument.

- [X] T020 [P] [US5] New handler: looks up each of up to 5 space-separated nicknames via
  `NicknameRegistry`, replying `302 RPL_USERHOST` with one
  `nick[*]=[+|-]user@host` entry per found nickname (`*` = operator, `+`/`-` = present/away —
  both already computable from `ClientSession`), in
  `jircd-core/src/main/java/net/jircd/core/session/command/UserhostCommandHandler.java`
  (research.md "Story 5")
- [X] T021 [P] [US5] New handler: sends a short, fixed `371 RPL_INFO` burst (server
  name/version, the same `serverVersion` source `VERSION`'s `351` reply already uses) followed
  by `374 RPL_ENDOFINFO`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/InfoCommandHandler.java`
- [X] T022 [P] [US5] In the not-found branch, send `318 RPL_ENDOFWHOIS` (using the originally
  requested nickname string, the only value available on this path) before the existing
  `return`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhoisCommandHandler.java`
- [X] T023 [P] [US5] In the exact-nickname branch only, stop applying the shared `isVisibleTo`
  invisibility gate — the mask and no-argument forms keep it unchanged, in
  `jircd-core/src/main/java/net/jircd/core/session/command/WhoCommandHandler.java`
- [X] T024 [P] [US5] Change the clear-away condition to
  `message.params().isEmpty() || message.params().getFirst().isEmpty()`, in
  `jircd-core/src/main/java/net/jircd/core/session/command/AwayCommandHandler.java`
- [X] T025 [US5] Wire `UserhostCommandHandler`/`InfoCommandHandler` into the command dispatch
  table, in `jircd-server/src/main/java/net/jircd/server/JircdServerApplication.java` (depends
  on T020, T021; same file as T018's edit — see Dependencies)
- [X] T026 [US5] Integration tests: `USERHOST` returns host info; `INFO` returns `371`/`374` not
  `421`; `WHOIS` on a missing nickname still sends `318`; `WHO` on an invisible user's exact
  nickname still returns a match; `AWAY :` clears away status, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/InformationQueryCompletenessTest.java`
  (depends on T022, T023, T024, T025)

**Checkpoint**: Information-query completeness fully functional and independently testable.

---

## Phase 8: User Story 6 - Becoming a Server Operator Is Immediately Visible to the Operator's Own Client (Priority: P3)

**Goal**: A successful `OPER` also sends an unsolicited `MODE <nick> +o`.

**Independent Test**: Authenticate as a server operator and verify the unsolicited notification
arrives alongside the existing success confirmation.

- [X] T027 [US6] After the existing `381 RPL_YOUREOPER` send, enqueue an unsolicited
  `MODE <nick> +o` directly to `session.writer()`, the same self-directed `MODE` echo shape
  `UserModeCommandHandler` already uses, in
  `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/OperCommandHandler.java`
  (research.md "Story 6")
- [X] T028 [US6] Integration test: a successful `OPER` results in both `381` and an unsolicited
  `MODE <nick> +o`, in
  `jircd-integration-tests/src/test/java/net/jircd/integration/OperSelfNotificationTest.java`
  (depends on T027)

**Checkpoint**: Operator self-notification fully functional and independently testable.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T029 [P] Update `specs/001-ircv3-server/contracts/irc-protocol-commands.md`'s `NICK`,
  `PRIVMSG`/`NOTICE`, `PING`/`PONG`, `CAP`, `JOIN`, `KICK`, `MODE`, `WHO`, `WHOIS` rows/notes;
  move `USERHOST`/`INFO` from "Recognized only" to "Implemented" with their new rows
- [X] T030 [P] Update
  `specs/002-extended-irc-commands/contracts/irc-protocol-commands-extended.md`'s `AWAY` row
- [X] T031 [P] Code cleanup pass: confirm `./gradlew build` runs Spotless/SpotBugs/PMD clean
  across every touched module
- [X] T032 Run the full `specs/005-fix-batch-conformance/quickstart.md` validation pass
  manually against a running `./gradlew :jircd-server:run` instance (constitution UX
  Consistency principle's required manual usage-scenario check)
- [X] T033 Re-run the irctest suite (`github.com/jircd/irctest`'s `irctest.controllers.jircd`
  controller, `--timeout=60 --timeout-method=signal`) and confirm every test named in
  quickstart.md's "Automated cross-check" now passes, with no regression in any
  previously-passing test (spec.md SC-003)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)** / **Foundational (Phase 2)**: Both empty.
- **User Stories (Phase 3-8)**: All six are independently implementable in any order — the
  only two file-sharing points are noted below, and neither creates a cross-story blocking
  chain (US2 and US3 can each start immediately; US4's internal T018 and US5's T025 just need
  to land in a compatible order with each other, not with any other story).
- **Polish (Phase 9)**: T029/T030 can start once their respective FRs' stories land; T031 once
  all code changes are in; T032/T033 depend on everything.

### Shared-file coordination (read before parallelizing)

- **`MessageCommandHandler.java`** is edited by both **US2** (T003, T004) and **US3** (T009).
  Land US2's T003/T004 first, then US3's T009 — same precedent `002-extended-irc-commands`
  already established for this exact file. Do not implement T009 in parallel with T003/T004.
- **`JircdServerApplication.java`** (composition root) is edited by both **US4** (T018, the
  `ModeCommandHandler` constructor call) and **US5** (T025, wiring the two new handlers). These
  are independent edits to different call sites in the same file — land one, then the other,
  to avoid a merge conflict; no functional dependency between them.

### Within Each User Story

- Implementation before that story's own integration test.
- Where a story's tasks touch the same file (US2's T003→T004; US3's T011→T012; US4's
  T014→T015 and T017→T018), they're marked sequential, not `[P]`.

### Parallel Opportunities

- Stories 1, 2, 3, 4, 5, 6 can all start in parallel (respecting the two shared-file notes
  above).
- Within US3: T006, T007, T008, T010, T011 are all `[P]` (different files, no dependency on
  each other) — only T009 (shared file with US2) and T012 (same file as T011) are sequential.
- Within US5: T020, T021, T022, T023, T024 are all `[P]` (different files) — T025 (shared file
  with US4) and T026 (integration test) come after.
- T029, T030, T031 (Polish) are `[P]` — different files.

---

## Parallel Example: Phase 5 (User Story 3)

```bash
# T006, T007, T008, T010, T011 touch different files and have no dependency on each other:
Task: "PONG server-name param + bare-PING 409 in PingPongCommandHandler.java"
Task: "Real CAP LIST + 410 invalid subcommand in CapCommandHandler.java"
Task: "List-based NegotiationResult in CapabilityNegotiator.java"
Task: "Merge clientTags into rendered tags in CapabilityTagRenderer.java"
Task: "Independent tag/command length checks in ConnectionHandler.java"
```

---

## Implementation Strategy

### MVP First (User Stories 1 and 2 Only)

1. Complete Phase 3 (Story 1 — NICK broadcast) and Phase 4 (Story 2 — DM delivery guarantees),
   both P1.
2. **STOP and VALIDATE**: run their integration tests and quickstart.md's Story 1/2 sections.
3. These two alone fix the single most severe finding (silent `NICK`) and the two most
   user-visible `PRIVMSG` gaps — everything after is P2/P3 precision and completeness work.

### Incremental Delivery

1. Story 1 → Story 2 → validate (MVP, both P1).
2. Story 3 (connection/CAP precision, P2) → validate independently.
3. Story 4 (channel-membership grammar, P2) → validate independently.
4. Story 5 (info-query completeness, P3) → Story 6 (OPER self-notify, P3) → validate
   independently, any order.
5. Polish.

---

## Notes

- [P] tasks = different files, no dependencies on incomplete tasks.
- [Story] label maps each task to its user story for traceability.
- Two shared-file coordination points (`MessageCommandHandler.java`,
  `JircdServerApplication.java`) are called out explicitly above — respect them even though
  most of this feature's 33 tasks are otherwise fully parallel across stories.
- No task in this feature modifies `spec.md`/`plan.md`/`research.md` — only
  `contracts/irc-protocol-commands.md` (001) and
  `contracts/irc-protocol-commands-extended.md` (002), the same "keep contracts accurate to
  current behavior" precedent `003`/`004` already established.
- Commit after each task or logical group.
- Stop at any checkpoint to validate a story independently.
