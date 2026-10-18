---

description: "Task list for 012-encapsulate-session-state"
---

# Tasks: Encapsulate Channel and Session State Transitions

**Input**: Design documents from `specs/012-encapsulate-session-state/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/state-operations.md, quickstart.md

**Tests**: Required. Constitution II and spec FR-019 call for tests first: every test task in a
phase is written and seen failing (or, for characterization tests, seen passing on unchanged
code) before that phase's implementation tasks.

**Organization**: Phases follow the plan's four delivery steps (plan.md "Delivery Steps"). Each
step is one independently mergeable PR. They are mapped to user stories like this:

| Delivery step | Phase | Story |
|---|---|---|
| — | 1 Setup, 2 Foundational (characterization net) | US2 safety net |
| Step 1 Shared fan-out and listed replies | 3 | US3 (P2) |
| Step 2 Membership transitions | 4 | US1 (P1) 🎯 |
| Step 3 Named mutators | 5 | US4 (P3) |
| Step 4 Invariants and cleanup | 6 | US4 (P3) + Polish |

US3 is delivered before US1 even though it has lower priority. US1's transition results are
delivered through `Recipients.deliver`, so landing the helper first keeps step 2 smaller. US2
("nothing visible changes") has no code of its own. It is the regression gate at the end of
every phase (tasks marked **[US2] gate**).

Path roots: `core/` = `jircd-core/src/main/java/net/jircd/core/session/`,
`coretest/` = `jircd-core/src/test/java/net/jircd/core/session/`,
`admin/` = `jircd-server-extensions/admin/src/main/java/net/jircd/serverextensions/admin/`,
`it/` = `jircd-integration-tests/src/test/java/net/jircd/integration/`.
Task descriptions use these roots followed by the file name.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: User story the task serves (US1–US4)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Record the baseline that later phases compare against

- [x] T001 Run `./gradlew build` on the unchanged branch and confirm it is green. Record the result in `specs/012-encapsulate-session-state/baseline.md` (new file: date, commit `git rev-parse HEAD`, pass/fail)
- [x] T002 Run `./gradlew loadTest` on the unchanged branch and append each load test's pass/fail, asserted latency bounds, workload sizes and Gradle-reported durations (measured latency/throughput figures are unavailable; approved 2026-10-16) (`Story1DeliveryLatencyLoadTest`, `ConcurrentConnectionScaleLoadTest`, `KeepAliveLoadTest`, `RateLimitLoadTest`) to `specs/012-encapsulate-session-state/baseline.md` for SC-005 (all load tests pass their asserted bounds; Gradle durations are recorded for information only)
- [x] T003 [P] Record the SC-003/SC-006 starting counts in `specs/012-encapsulate-session-state/baseline.md`: the hit list of the accessor-mutation `rg` from quickstart.md §2 (14 sites expected, see plan.md call-site inventory) and the FR-citation count per file in the files listed in plan.md "Source Code"

---

## Phase 2: Foundational (Characterization Net, blocks all steps)

**Purpose**: Pin today's client-visible behavior wherever later steps change the code path and
no existing test pins it yet. Each test MUST pass on the unchanged code. These are new tests;
no existing integration test is edited (FR-018).

- [x] T004 [P] [US2] Add `it/SajoinRepeatTest.java`: an admin already in `#lobby` sends `SAJOIN #lobby` again; assert another member receives a second `JOIN` line and the admin receives a fresh 353/366 (pins the SAJOIN behavior that must survive `JoinResult.alreadyMember`, contracts "SAJOIN note")
- [x] T005 [P] [US2] Add `it/DepartureRecipientsTest.java`: three clients in `#c`. Assert PART is received by all three, including the parter, KICK is received by all remaining members including the target, and the lines appear before the parter/target's subsequent numeric replies (R4 recipient rules)
- [x] T006 [P] [US2] Add `it/QuitNeighborsTest.java`: client A shares `#a` and `#b` with B and only `#b` with C, and D shares nothing. On A's QUIT, B and C each receive exactly one QUIT and D receives none; A also joins an A-only `#solo`; `#solo` becomes empty and a later `LIST` no longer shows it (while `#a` remains populated by B)
- [x] T007 [P] [US2] Add `it/ModeFanoutTest.java`: `MODE #c +o nick`, `+v`, `+s` then `+p`, and `TOPIC` are each echoed once to every member. After `+p`, `MODE #c` shows `p` but not `s` (pins the C-2 exclusion before step 3 moves it)
- [x] T008 [US2] **[US2] gate**: `./gradlew :jircd-integration-tests:test --tests '*SajoinRepeat*' --tests '*DepartureRecipients*' --tests '*QuitNeighbors*' --tests '*ModeFanout*'` is green on unchanged production code

**Checkpoint**: The safety net is in place. Steps can begin.

---

## Phase 3: User Story 3 — Shared fan-out and listed replies (Delivery Step 1, Priority: P2)

**Goal**: One implementation of shared-channel recipient dedup, one of "deliver to a set", and one
of byte-aware list replies (FR-014, FR-015, FR-016). No change to state ownership yet.

**Independent Test**: NICK/AWAY/QUIT reach each recipient once across shared channels, and NAMES
lines never exceed 512 bytes for long multi-byte nicknames (spec US3 scenarios 1–3).

### Tests for User Story 3 (write first, see them fail)

- [x] T009 [P] [US3] Create `coretest/RecipientsTest.java`: `sharedChannelNeighbors` with `includeSelf=true` returns self first, then each neighbour once across three shared channels; with `includeSelf=false` self is absent; the filter (e.g. "has capability away-notify") is applied; a session with no channels returns only self or nothing. `deliver` skips a session whose `writer()` is null and still delivers to the rest
- [x] T010 [P] [US3] Create `jircd-core/src/test/java/net/jircd/core/session/command/RepliesListedTest.java`: `Replies.sendListed` with 60 tokens of 9-byte nicks plus a 2-byte-char channel `#ébudget` emits several lines, each with `MessageSerializer.commandBytes(line) <= MessageSerializer.MAX_COMMAND_BYTES`; the union of tokens equals the input in order; no token is split; an empty item list emits exactly one line with an empty trailing parameter; a singleton list still fits with the trailing colon reserved
- [x] T011 [P] [US3] Extend `coretest/ChannelTest.java` with `memberListing()` tests: an operator gets prefix `@`, a voiced non-operator gets `+`, a member who is both operator and voiced gets only `@`, a plain member gets no prefix, and each member appears once (data-model C-5)

### Implementation for User Story 3

- [x] T012 [P] [US3] Create `core/Recipients.java` per contracts/state-operations.md "Recipients": `sharedChannelNeighbors(ClientSession, boolean includeSelf, Predicate<ClientSession> filter)` returns a `LinkedHashSet` (self first when included) and `deliver(Collection<ClientSession>, Message)` calls `enqueueRaw` on each non-null writer. Move the single `@SuppressWarnings("PMD.CloseResource")` with its "borrowed writer" reason here. The class Javadoc states that it must never be called while holding a registry or channel monitor
- [x] T013 [P] [US3] Add `Replies.sendListed(ClientSession, String serverName, NumericReply, List<String> fixedParams, List<String> items)` to `core/command/Replies.java`. Move the budget logic out of `JoinCommandHandler.sendNamesLine` unchanged: `MAX_COMMAND_BYTES - commandBytes(empty)` with the trailing colon reserved, items joined by single spaces, never split, at least one line
- [x] T014 [US3] Add `List<String> memberListing()` to `core/Channel.java`, `synchronized`, returning `@nick`/`+nick`/`nick` tokens in member iteration order (C-5)
- [x] T015 [US3] Rewrite `JoinCommandHandler.sendNamesLine` in `core/command/JoinCommandHandler.java` as `Replies.sendListed(requester, serverName, RPL_NAMREPLY, List.of(visibility, channel.name()), channel.memberListing())`. Keep the `sendNamesReply`/`sendNamesLine` signatures used by `NamesCommandHandler` and `admin/SajoinCommandHandler.java`
- [x] T016 [P] [US3] Replace `broadcastNickChange`'s hand-built set in `core/command/NickCommandHandler.java` with `Recipients.deliver(Recipients.sharedChannelNeighbors(session, true, s -> true), notification)`. Keep `setNickname` before the recipient computation (research R4)
- [x] T017 [P] [US3] Replace the recipient loop in `core/command/AwayCommandHandler.java` `broadcastAwayChange` with `Recipients.sharedChannelNeighbors(session, false, m -> m.negotiatedCapabilities().contains(CapabilityName.AWAY_NOTIFY))`. Keep the early return when the set is empty and keep the existing batch/enqueue call for delivery if it differs from `enqueueRaw`
- [x] T018 [P] [US3] Use a neighbour `LinkedHashSet` in `core/DisconnectCleanup.java`; after each `channel.removeMember(session)`, add `channel.members()` to it, then remove self. Replace only the per-neighbour delivery loop with `Recipients.deliver(neighbors, quit)`. Membership removal stays as is until T030
- [x] T019 [P] [US3] Replace the "for each member, if writer != null, enqueueRaw" loops with `Recipients.deliver(channel.members(), msg)` in `core/command/PartCommandHandler.java`, `core/command/KickCommandHandler.java`, `core/command/TopicCommandHandler.java` and `core/command/ModeCommandHandler.java` (line ~388 echo)
- [x] T020 [P] [US3] Same replacement in `admin/SajoinCommandHandler.java`, `admin/SamodeCommandHandler.java` and the JOIN broadcast in `core/command/JoinCommandHandler.java`
- [x] T021 [US3] **[US2] gate**: `./gradlew :jircd-core:test --tests '*Recipients*' --tests '*RepliesListed*' --tests '*OutboundMessageBudget*' --tests '*ChannelTest*'` then `./gradlew build` (includes `NickBroadcastTest`, `AwayNotifyTest`, `OutboundByteBudgetTest`, `Story1NamesTest`, `BareNamesTest`, Phase 2 tests) are green with no integration test modified

**Checkpoint**: Step 1 is mergeable. Dedup and list splitting each exist once (SC-004).

---

## Phase 4: User Story 1 — Membership transitions (Delivery Step 2, Priority: P1) 🎯 MVP

**Goal**: JOIN/PART/KICK/disconnect are single operations on `ChannelRegistry` under registry →
channel locking. Both membership sides change together, empty channels are dropped in the same
step, a `CLOSING` session cannot join, and races resolve serially (FR-001–FR-005, FR-007,
FR-008, FR-011–FR-013, FR-020).

**Independent Test**: The concurrent join/part/kick/disconnect stress run leaves zero membership
mismatches, zero empty channels and zero duplicate departure notices (SC-002). The issue #12/#13
race tests stay green.

### Tests for User Story 1 (write first, see them fail)

- [x] T022 [P] [US1] Add transition tests to `coretest/ChannelRegistryTest.java`:
  - `join` returns a `JoinResult` whose `recipients` include the joiner and whose `grantedOperator` is true only for the first member.
  - `join` for an existing member returns `alreadyMember=true`, skips admission and changes nothing.
  - `part` of a non-member returns empty.
  - `part` returns recipients captured before removal (parter included), clears op and voice, removes the session's membership and drops the channel when it empties.
  - `kick` returns `NotOperator`, `NoSuchMember` (folded-nick lookup) or `Kicked` with pre-removal recipients.
  - `leaveAll` returns the de-duplicated neighbours with self excluded and leaves `channelMemberships()` empty.
- [x] T023 [P] [US1] Add race tests to `coretest/ChannelRegistryTest.java` using the existing `CountDownLatch` + `Executors.newVirtualThreadPerTaskExecutor()` pattern. No sleeps.
  - (a) Two `kick` calls for the same target: exactly one `Kicked`, the other `NoSuchMember` (FR-020).
  - (b) `part` racing `kick` of the same member: exactly one `Departure`.
  - (c) The disconnect-vs-JOIN leak. `join`'s admission predicate blocks on a latch while another thread calls `session.lifecycle().closeIfNotAlreadyClosing()` then `registry.leaveAll(session)`. After both finish, `session.channelMemberships()` is empty and no channel lists the session.
  - (d) `join` of a `CLOSING` session returns empty (FR-004).

  (a) and (c) MUST fail on the current code.
- [x] T024 [P] [US1] Create `coretest/MembershipStressTest.java`: 32 sessions × 8 channels, ≥1,000 randomized join/part/kick/leaveAll operations (fixed `Random` seed) across virtual threads. Afterwards assert invariant R-3 (`s ∈ c.members() ⇔ c ∈ s.channelMemberships()`) for every pair, R-2 (no empty channel in `registry.all()`), `operators ⊆ members` and `voiced ⊆ members`, and that each departure's recipient set contained no duplicates (SC-002)
- [x] T025 [P] [US1] Add `coretest/DisconnectCleanupTest.java` cases: cleanup of a session in three channels removes all memberships, drops the channels that emptied, sends one QUIT per neighbour, and remains idempotent on a second call

### Implementation for User Story 1

- [x] T026 [US1] Create the records `core/JoinResult.java` (`channel`, `recipients`, `grantedOperator`, `alreadyMember`, `memberListing`), `core/Departure.java` (`channel`, `departed`, `recipients`) and sealed `core/KickOutcome.java` (`NotOperator`, `NoSuchMember`, `Kicked(Departure)`) per contracts/state-operations.md "Result records". Recipient sets are immutable copies
- [x] T027 [US1] In `core/ClientSession.java`, add package-private `addMembership(Channel)` and `removeMembership(Channel)`, and make `channelMemberships()` return `Collections.unmodifiableSet(channelMemberships)`
- [x] T028 [US1] In `core/Channel.java`, make `addMember` and `removeMember` package-private, make `removeMember` `synchronized` (it removes from members, operators and voiced together), and make `members()` return an unmodifiable view
- [x] T029 [US1] Rewrite `core/ChannelRegistry.java`:
  - `join(name, session, admission)` takes the registry monitor, then the channel monitor. Inside: CLOSING check → empty; already a member → `alreadyMember`, no admission; otherwise admission, `addMember`, `addMembership`, and capture recipients and `memberListing()`.
  - Add `part(Channel, ClientSession)`, `kick(Channel, ClientSession kicker, String targetNickname)` and `leaveAll(ClientSession)`, each under the registry monitor and then each channel's monitor in turn (never two at once). Each removes both sides, drops the channel if empty and returns its result.
  - Make `getOrCreate` and `removeIfEmpty` package-private.
  - **Approved deviation (2026-10-16)**: on rejected admission remove a channel just created by this join only if it is still empty and still the registered instance (R-2). Keep `rejectedAdmissionDoesNotInsertMembership`'s name; assert no session memberships and an empty `registry.lookup("#lobby")`, replacing its prior empty-registered-channel assertion.
  - Class Javadoc states the lock order "registry → channel; never two channels; no delivery under either" and invariants R-1…R-4 (data-model.md).
- [x] T030 [US1] Rewrite `core/DisconnectCleanup.java` to call `channelRegistry.leaveAll(session)` after `closeIfNotAlreadyClosing()` and deliver QUIT with `Recipients.deliver`. WHOWAS, nickname release and writer close order stay unchanged
- [x] T031 [P] [US1] Rewrite `core/command/PartCommandHandler.java` to use `channelRegistry.part(channel, session)`. Empty → `ERR_NOTONCHANNEL` (same text). Otherwise build the PART message and deliver to `departure.recipients()`
- [x] T032 [P] [US1] Rewrite `core/command/KickCommandHandler.java` to switch on `channelRegistry.kick(...)`: `NotOperator` → the existing `SecurityEventLog.rejectedModerationAction` + `ERR_CHANOPRIVSNEEDED`, `NoSuchMember` → `ERR_USERNOTINCHANNEL`, `Kicked` → KICK to `recipients()`. The default reason (kicker nickname) and parameter shapes are unchanged
- [x] T033 [P] [US1] Rewrite `core/command/JoinCommandHandler.java` `joinOne`:
  - Drop the pre-lookup already-member check and treat `JoinResult.alreadyMember()` as a silent no-op (issue #16).
  - Deliver JOIN to `result.recipients()`.
  - Send NAMES from `result.memberListing()` through `Replies.sendListed`.
  - In `admissionFailure`, consume invitations via the channel (temporarily `channel.invited().remove(...)`; switched to `consumeInvitation` in T041).
- [x] T034 [P] [US1] Rewrite `admin/SajoinCommandHandler.java` to use `JoinResult`: deliver JOIN to `recipients()` and send NAMES from `memberListing()` **even when `alreadyMember()` is true** (pinned by T004)
- [x] T035 [US1] Migrate the choreography in `coretest/ChannelRegistryTest.java` (`joiningDuringLastMemberRemovalKeepsOneRegisteredChannel`, `lastMemberRemovalDuringAdmissionDoesNotDetachTheJoinedChannel`, `staleRemovalCannotRemoveAReplacementChannel`, …) off direct `addMember`/`removeMember`/`channelMemberships().add/remove` onto `join`/`part`. Keep each test's final assertions. Where the old interleaving can no longer occur (removal during admission now blocks on the registry monitor), restate the test as "the removal completes after the admitted join and the channel stays registered with the joiner" and say so in the test's Javadoc (FR-018). Also migrate `coretest/DisconnectCleanupTest.java`'s review regression: its join-between-channel-removals interleaving becomes impossible under `leaveAll`'s registry lock; arrange the newcomer's admitted join before cleanup and retain the QUIT delivery assertion, documenting the serialized outcome
- [x] T036 [P] [US1] Migrate `coretest/ChannelTest.java` and `coretest/OutboundMessageBudgetTest.java` setup to the package-private `addMember` (same package) or `ChannelRegistry.join`. `voiced().add` is migrated in T044
- [x] T037 [US1] **[US2] gate**: `./gradlew :jircd-core:test --tests '*ChannelRegistry*' --tests '*DisconnectCleanup*' --tests '*MembershipStress*'`, then `./gradlew build` and `./gradlew loadTest`. All green; all load tests pass their asserted bounds (including <1s per message delivery); Gradle durations are recorded in `baseline.md` for information only, and `git diff --stat origin/main -- jircd-integration-tests/src/test/java/net/jircd/integration/` shows only the Phase 2 additions and test-support helper changes

**Checkpoint**: Step 2 is mergeable and is the MVP. The membership invariants hold under
concurrency.

### Step 2b: JOIN critical-section fix (FR-021, SC-007, research R10)

Added 2026-10-16 after the #48 JOIN-storm measurement. Delivered after Phase 5, before Phase 6.

- [x] T037a [US1] Tests first. Add to `coretest/ChannelRegistryTest.java`:
  - (a) A test using a package-private `ChannelRegistry(Consumer<Channel> joinSnapshotHook)`. The hook blocks a JOIN to `#storm` at the start of its snapshot phase, and `lookup("#quiet")`, `part` and `join` on `#quiet` from another thread complete while it is blocked.
  - (b) A test that, while a thread holds a channel's monitor, `memberListing()` from another thread completes.

  Add to `jircd-core/src/test/java/net/jircd/core/session/command/JoinBanGateLockTest.java`:
  - (c) A JOIN to a channel with a ban, with a pausing hostname-display extension. While the extension is paused, `lookup()` of another channel completes.

  Observe (a)–(c) failing on the step 3 code, with the hook placed where the snapshot was then built.
- [x] T037b [US1] In `core/ChannelRegistry.java`, keep the CLOSING/already-member/admission/insertion phase under registry → channel. Build `JoinResult` (recipients and `memberListing()`) after both monitors are released, calling the hook first. Update the class Javadoc lock rule to say "no O(members) work or extension code under the registry monitor". In `core/Channel.java`, remove `synchronized` from `memberListing()`
- [x] T037c [US1] In `core/command/JoinCommandHandler.java`, compute `PresentedIdentity.presentedForm` once before `channelRegistry.join` and pass it to `passesBanGate`. Reuse it for the JOIN line. Replies and their order are unchanged
- [x] T037e [US1] Tests first (spec Clarifications 2026-10-16, decision 1). Add `ChannelRegistryTest.joinerKickedBeforeTheSnapshotIsStillARecipient`: pause J's join in the snapshot hook, KICK J, and assert J is still in `JoinResult.recipients()` and the listing is shown as read. Add `coretest/JoinSnapshotHandlerTest.joinerKickedDuringTheSnapshotStillReceivesItsOwnJoin` (handler level: J receives its own JOIN). Both observed failing first. Then in `core/ChannelRegistry.java` add the joiner to the recipient copy unconditionally, outside all locks; never patch the listing. `MembershipStressTest`'s "joiner is a recipient" assertion stays
- [x] T037f [US1] Tests first (decision 2). Add `JoinSnapshotHandlerTest.joinLineUsesTheIdentityPresentedAfterTheJoin`: enable a cloak extension while the JOIN is paused in the snapshot hook, then assert the JOIN line carries the cloaked host. Observed failing first. Then in `core/command/JoinCommandHandler.java` re-sample `presentedForm` after `join` for the JOIN line; the ban check keeps the pre-lock sample. Update the `core/PresentedIdentity.java` contract note (FR-022)
- [x] T037d [US1] **[US2] gate**: run the scoped `ChannelRegistry*`, `ChannelTest`, `MembershipStress*`, `DisconnectCleanup*` and `*Join*` tests, then `./gradlew build` (exit 0, coverage floor included). The user then measures SC-007 with the #48 `JoinStormLoadTest`: at least 7 alternating runs per variant (main and feature branch) on an otherwise idle machine. The medians of the storm completion time and of the `#quiet` p50 latency must each be within 10% of main; `#quiet` p99 and max only need to stay within the test's bounds (500 ms / 1 s). Record per-run values and medians in `baseline.md`. First-version (v1) evidence is in research R10. Done 2026-10-17: final measurement storm median 2293 → 2335 ms (+1.8%), `#quiet` p50 median 19.6 → 19.4 ms (−1.0%), worst p99/max 181 ms main / 157 ms branch, all within bounds (research R10, `baseline.md`)

---

## Phase 5: User Story 4 — Named mutators for privileges, modes, invites, bans, user modes (Delivery Step 3, Priority: P3)

**Goal**: No code outside `Channel`/`ClientSession`/`ChannelRegistry` can change their
collections; every change is a named operation (FR-006, FR-009, FR-010).

**Independent Test**: The quickstart.md §2 accessor-mutation search returns nothing, and
mutation through any accessor throws `UnsupportedOperationException` (SC-003).

### Tests for User Story 4 (write first, see them fail)

- [x] T038 [P] [US4] Extend `coretest/ChannelTest.java`:
  - `grantOperator`/`grantVoice` on a non-member returns `false` and changes nothing (C-1); on a member it returns `true`; `revoke*` removes.
  - `setMode(SECRET)` clears `PRIVATE` and vice versa (C-2).
  - `invite` + `isInvited` + `consumeInvitation` are case-folded and consume once.
  - `removeBan` of an absent mask is a no-op.
  - `members()`, `operators()`, `voiced()`, `activeModes()` and `bans()` throw `UnsupportedOperationException` on `add`.
- [x] T039 [P] [US4] Add `coretest/ClientSessionTest.java` (new): `setUserMode`/`clearUserMode` change `userModes()`; `userModes()` and `channelMemberships()` throw `UnsupportedOperationException` on `add`; `grant/revokeAdministratorPrivilege` still toggle `UserMode.OPERATOR`
- [x] T040 [P] [US4] Add a race test to `coretest/ChannelTest.java`: `grantOperator(member)` racing `ChannelRegistry.part` of that member (latch-forced) never leaves the ex-member in `operators()` (C-1 ghost-operator case)

### Implementation for User Story 4

- [x] T041 [US4] In `core/Channel.java`:
  - Add `synchronized` `grantOperator`, `revokeOperator`, `grantVoice` and `revokeVoice`. Grants check membership.
  - Add `setMode(ChannelMode)`, which enforces the `+s`/`+p` exclusion, and `clearMode`.
  - Add `invite(String nickname)`, `isInvited(String nickname)` and `consumeInvitation(String nickname)`, all folding with `CaseMapping`.
  - Make `removeBan` `synchronized`.
  - Return unmodifiable views from `operators()`, `voiced()`, `activeModes()` and `bans()`, and remove `invited()`.
- [x] T042 [US4] In `core/ClientSession.java`, add `setUserMode(UserMode)` and `clearUserMode(UserMode)` and make `userModes()` return an unmodifiable view
- [x] T043 [P] [US4] Migrate `core/command/ModeCommandHandler.java`: `applyBoolean` uses `setMode`/`clearMode` (drop the inline +s/+p logic) and `applyMember` uses `grant*`/`revoke*`. Echo and reply behavior are unchanged
- [x] T044 [P] [US4] Migrate `admin/SamodeCommandHandler.java` (`operators().add/remove` → `grantOperator`/`revokeOperator`) and `coretest/OutboundMessageBudgetTest.java` (`voiced().add` → `grantVoice`) plus `coretest/ChannelTest.java` (`operators().add` / `voiced().add` → `grantOperator` / `grantVoice`). Also migrate the voice setup added by T022/T025 in `coretest/ChannelRegistryTest.java`, `coretest/DisconnectCleanupTest.java`, and `coretest/MembershipStressTest.java` to `grantVoice`
- [x] T045 [P] [US4] Migrate `core/command/InviteCommandHandler.java` (`invited().add(fold(nick))` → `channel.invite(nick)`) and `core/command/JoinCommandHandler.java` `admissionFailure` (`invited().contains`/`remove` → `isInvited`/`consumeInvitation`, still peeked first and consumed only after every gate passes)
- [x] T046 [P] [US4] Migrate `core/command/UserModeCommandHandler.java` (`userModes().add/remove` → `setUserMode`/`clearUserMode`). Keep the existing rule that `-o` is allowed and `+o` is refused
- [x] T047 [US4] **[US2] gate**: run the quickstart.md §2 search (expect no output), then `./gradlew build`. `Story5*` (ban/invite/kick/mode/voice), `Story6Samode*`, `Story1UserModeTest` and `ChannelModeIsTest` are green with no integration test modified

**Checkpoint**: Step 3 is mergeable. FR-009 is enforced by the compiler and the views.

---

## Phase 6: User Story 4 — Invariants and cleanup (Delivery Step 4, Priority: P3) + Polish

**Goal**: The state rules are documented at their owners as current invariants, and historical
FR citations are gone from touched files (FR-017, SC-006). Final gates (SC-001…SC-006).

**Independent Test**: The quickstart.md §3 search over touched files returns nothing. A reviewer
finds the lock order and invariants R-1…R-4, C-1…C-5 and S-1 in the class Javadoc.

- [ ] T048 [P] [US4] Write class-level invariant Javadoc in `core/Channel.java` (C-1…C-5, "guarded by this monitor; acquired only after the registry monitor") and `core/ClientSession.java` (S-1, "channelMemberships written only by ChannelRegistry transitions while the channel monitor is held"). Replace the FR-cited method comments (`createdAt`, `memberLimit`, `key`, `addMember`, `findMember`, `addBan`, `removeBan`, the USER one-shot comment) with present-tense rules
- [ ] T049 [P] [US4] Rewrite FR-cited comments in `core/ChannelRegistry.java`, `core/DisconnectCleanup.java` (idempotency, WHOWAS, cloak notes → current invariants) and `core/NicknameRegistry.java` (only if touched) as present-tense invariants
- [ ] T050 [P] [US4] Rewrite FR-cited comments as present-tense rules in every handler touched in Phases 3–5: `core/command/JoinCommandHandler.java`, `PartCommandHandler.java`, `KickCommandHandler.java`, `NickCommandHandler.java`, `AwayCommandHandler.java`, `ModeCommandHandler.java`, `TopicCommandHandler.java`, `InviteCommandHandler.java`, `UserModeCommandHandler.java`, `NamesCommandHandler.java`, `Replies.java`, `admin/SajoinCommandHandler.java`, `admin/SamodeCommandHandler.java`
- [ ] T051 [US4] Run the quickstart.md §3 search over `git diff --name-only origin/main -- '*.java'` and fix the remaining hits (SC-006)
- [ ] T052 Run `./gradlew spotlessApply`, then `./gradlew build` (Spotless, SpotBugs, PMD, all tests) and fix any finding. Each new suppression gets a documented reason (Constitution I)
- [ ] T053 Run `./gradlew loadTest`; all load tests must pass their asserted bounds (including <1s per message delivery). Record Gradle durations next to the baseline in `specs/012-encapsulate-session-state/baseline.md` for information only (SC-005, Quality Gate 5)
- [ ] T054 Do the quickstart.md §4 manual walkthrough against a locally started server (two clients: JOIN, NAMES, MODE +o/+v, KICK, PART, NICK, AWAY with away-notify, QUIT) and note the outcome in `specs/012-encapsulate-session-state/baseline.md` (Constitution III, Quality Gate 4)
- [ ] T055 **[US2] gate**: verify SC-001 and SC-004. `git diff --stat origin/main -- jircd-integration-tests` shows only the Phase 2 additions and test-support helper changes. The list of migrated unit tests (T035, T036, T044) is written into the PR description with "same invariant" notes. The PR notes the FR-020 serial race outcome (confirmed 2026-10-16) and the approved Step 3 review hardening: MODE/SAMODE late departures receive 441 and refused changes are not echoed; existing early membership replies and valid idempotent changes are unchanged

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)** → **Phase 2 (Characterization)** → **Phase 3 (Step 1, US3)** →
  **Phase 4 (Step 2, US1)** → **Phase 5 (Step 3, US4)** → **Phase 6 (Step 4, US4 + polish)**.
- Steps are strictly sequential because each is its own PR on top of the previous one (FR-018),
  and later steps edit the same handler files.
- Phase 5 depends on Phase 4 for `ChannelRegistry.part` (T040) and on the package-private
  `addMember` migration (T036).
- Phase 6 depends on all code phases (it documents the final shape).

### Within each phase

- Test tasks come before implementation tasks. New tests must be observed failing, except the
  Phase 2 characterization tests, which must pass on unchanged code.
- Phase 3: T012–T014 (helpers) before T015–T020 (callers).
- Phase 4: T026 → T027/T028 → T029 → T030–T034 (callers) → T035/T036 (test migration) → T037.
- Phase 5: T041/T042 before T043–T046.

### User story dependencies

- **US3** needs nothing beyond Phase 2.
- **US1** uses `Recipients` from US3 (delivery of transition results).
- **US4** uses US1's package-private membership API and `part` for its ghost-operator race
  test.
- **US2** is a gate in every phase, not a code increment.

## Parallel Opportunities

- Phase 1: T003 runs alongside T001/T002.
- Phase 2: T004–T007 (four independent new integration test files).
- Phase 3: tests T009–T011 together; helpers T012/T013 together; caller migrations T016–T020
  together (distinct files).
- Phase 4: tests T022–T025 together (T022/T023 share a file, so one writer); handler rewrites
  T031–T034 together after T029.
- Phase 5: tests T038–T040 (T038/T040 share `ChannelTest.java`, so one writer); migrations
  T043–T046 together.
- Phase 6: T048–T050 together.

### Parallel example: Phase 4 handlers (after T029/T030)

```text
T031 PartCommandHandler → ChannelRegistry.part
T032 KickCommandHandler → ChannelRegistry.kick
T033 JoinCommandHandler → JoinResult
T034 SajoinCommandHandler → JoinResult (alreadyMember still broadcasts)
```

## Implementation Strategy

### MVP

Phases 1–4. Ship Steps 1 and 2. That fixes the defect class behind the issue (cross-object
membership consistency, the disconnect-vs-JOIN leak, serial KICK/PART races) with dedup and list
splitting already centralized. Stop and validate at T037.

### Incremental delivery

1. Step 1 (Phase 3) → PR → merge: pure consolidation, no ownership change.
2. Step 2 (Phase 4) → PR → merge: transitions and lock order (MVP).
3. Step 3 (Phase 5) → PR → merge: named mutators, accessors read-only.
4. Step 4 (Phase 6) → PR → merge: invariant docs, comment cleanup, final gates.

Each PR references issue #19, explains why, and lists any migrated unit tests (FR-018).

## Notes

- One writer per working tree (AGENTS.md). Parallel [P] tasks given to workers need disjoint
  files or separate worktrees.
- Do not edit existing integration tests. Do not commit `jircd-server/jircd.yaml`.
- FR-020's serial race outcome (confirmed 2026-10-16, spec Clarifications) is implemented by
  T023 and T032.
