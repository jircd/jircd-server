# Feature Specification: Capability Plugin Ownership and Runtime Lifecycle

**Feature Branch**: `014-capability-plugin-spi`

**Created**: 2026-10-22

**Status**: Draft — ready for design review; implementation requires user review.

**Input**: Make the existing capability modules own their behavior through a real plugin SPI, including runtime cancellation and capability notifications. Covers [#98](https://github.com/jircd/jircd-server/issues/98) and [#97](https://github.com/jircd/jircd-server/issues/97).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Capabilities own their behavior (Priority: P1)

A capability author implements and removes a capability in its own module. Server maintainers provide reusable extension points rather than adding capability-name decisions to the server for each feature. Clients continue to see the same messages when capabilities remain enabled.

**Why this priority**: The existing marker modules do not achieve the intended plugin boundary.

**Independent Test**: Exercise each existing capability, then run with its provider absent. Its offer and behavior disappear together, while ordinary IRC and other capabilities continue working. Run existing integration tests unchanged through the provider migrations; #97 updates exact CAP LS expectations for advertised core cap-notify.

**Acceptance Scenarios**:

1. **Given** clients with different negotiated capabilities, **When** they exchange PRIVMSG, NOTICE and TAGMSG, **Then** client tags, message identifiers, server time, echo recipients and self-delivery retain their existing semantics.
2. **Given** peers sharing several channels, **When** a user sets, changes or clears AWAY, **Then** away-notify recipients receive one cloak-aware notification each, with unchanged visibility and sender confirmations.
3. **Given** a member who is both operator and voiced, **When** clients request NAMES or channel WHO, including JOIN/SAJOIN listings, **Then** only multi-prefix clients receive every status in PREFIX order; ordinary clients receive the highest status.
4. **Given** related replies, **When** the recipient has both batch and message-tags, **Then** existing BATCH framing and member tags are preserved; missing either capability produces existing plain delivery.
5. **Given** any of the six existing capability providers is absent, **When** the server starts and clients use related commands, **Then** that capability is neither advertised nor executed, and core retains no dormant implementation of it. Core cap-notify remains advertised independently of every provider.

### User Story 2 - Runtime changes cancel negotiated behavior (Priority: P1)

An administrator disables or enables a capability through EXTENSION or REHASH. Connected clients obtain consistent state, receive availability notifications when eligible, and can safely negotiate again.

**Why this priority**: #97 violates the existing configuration path-equivalence contract.

**Independent Test**: Over real sockets, negotiate multi-prefix and message-tags, disable each through both paths, verify DEL, LS, LIST and subsequent NAMES/WHO/messages, then enable and verify NEW and explicit renegotiation. Repeat with explicit cap-notify, implicit CAP 302 and an older client without notifications.

**Acceptance Scenarios**:

1. **Given** sessions holding a capability, **When** it becomes disabled, **Then** its offer, executable behavior and every session's negotiated membership change as one observable transition. Subsequent CAP LIST excludes it.
2. **Given** explicit cap-notify or CAP LS 302-or-higher clients, **When** the offered set changes, **Then** they receive DEL for removals and NEW for additions even if they never held those capabilities. Other clients receive neither.
3. **Given** a held capability no longer offered, **When** the client requests `CAP REQ :-name`, **Then** removal succeeds. Removal of a discovered capability already pruned by disable is an acknowledged no-op. A mixed request containing an unavailable addition fails atomically.
4. **Given** a previously negotiated capability, **When** it is enabled again, **Then** eligible clients receive NEW, but behavior resumes only after a new positive request.
5. **Given** output captured or being prepared when disable publishes, **When** that work completes, **Then** it may still be emitted after DEL. An operation whose runtime/session view is captured after publication uses the new snapshot; earlier captured work may finish preparation later. A batch group admitted together keeps its close; a group still awaiting admission when batch or message-tags becomes ineffective is sent plain.
6. **Given** an invalid reload or repeated no-op toggle, **When** submitted, **Then** invalid configuration changes nothing and reports an actionable error, and an idempotent toggle emits no duplicate notifications.

### User Story 3 - Extension failures and growth remain bounded (Priority: P2)

Operators can run capabilities without malformed output, plugin errors or large fan-outs damaging unrelated sessions. Authors can add related behavior using the documented extension contract.

**Why this priority**: Moving behavior out of core must preserve failure isolation, wire limits and measured delivery budgets.

**Independent Test**: Use test-only providers that throw, return invalid data, compose reply decorations and require other capabilities. Prove failure state, cleanup, reply sizes and unaffected peer delivery; measure fan-out and concurrent reload against baseline.

**Acceptance Scenarios**:

1. **Given** a throwing or invalid-data provider, **When** evaluated, **Then** it becomes FAILED and is withdrawn using the disable lifecycle, a bounded diagnostic identifies provider and hook, and core operations and unrelated providers continue.
2. **Given** maximum configured lengths and compatible reply decorations, **When** configuration is validated or a provider enabled, **Then** impossible fixed reply fields are rejected before publication; variable text and NAMES lists are fitted without breaking tokens or silently losing replies.
3. **Given** unavailable or unnegotiated behavioral prerequisites, **When** a hook would run, **Then** its declared fallback applies. Batch alone remains negotiable and delivers plain members until message-tags is effective.
4. **Given** future extensions, **When** mapped to the SPI, **Then** account-notify can consume account changes, userhost-in-names can compose with status rendering, and chathistory/labeled-response can use grouping and request context without capability-name branches in core. These features are not implemented here.

### Edge Cases

- REQ racing disable, connection admission, LIST, nickname changes or another administrative transition; unregistered sessions.
- Lower/omitted LS version after 302; cap-notify removal by a 302 client versus a legacy client.
- Unknown versus discovered-but-disabled removals; repeated and conflicting REQ tokens processed in order; oversize ACK without mutation.
- A render captured before publication finishing after DEL; stalled socket with an admitted batch; unadmitted group losing a prerequisite; SendQ overflow while queuing DEL/NEW.
- Hook failure after core membership/away state committed; stop throwing; partial start/registration failure; callbacks finishing after provider stop or re-enable.
- Null/illegal tags, forged server tags, duplicate tag/field ownership, false byte bounds, invalid groups and oversized member tokens.
- Peers changing during audience collection; shared-channel duplicates; self-target messages; sender already in recipients.
- Missing prerequisites, dependency cycles, duplicate offers and incompatible reply budgets on enable/reload.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Each existing capability (message-tags, server-time, echo-message, away-notify, batch, multi-prefix) MUST own its behavior in its module. Core MUST retain only negotiation/lifecycle and generic protocol, session, channel, delivery and extension services, with no behavior decisions keyed to those names.
- **FR-002**: The plugin contract MUST support committed away/join/part/quit/nick events, interested-peer delivery, reply-field rendering, related-output grouping, inbound tags and outbound contributions. It MUST explain future account events and request context without implementing account/history features now.
- **FR-003**: Provider absence MUST remove both offer and behavior. Core authorization, visibility, identity protection, state ownership, reply ordering and cleanup MUST remain authoritative.
- **FR-004**: Stable enabled/negotiated configurations MUST preserve existing wire behavior: tag origins/identifiers, echo deduplication, AWAY visibility, prefix order and batch fallback/framing. Existing integration tests MUST pass unchanged in #100/#101; #97 MUST update exact CAP LS expectations to include core cap-notify. No unrelated assertions may be weakened.
- **FR-005**: Disable/enable/failure MUST publish a new immutable RuntimeSnapshot and prune withdrawn names from every live session in ConnectionHandler.liveSessions in one step under the existing administrative control mutex, then queue DEL/NEW. Capture and session insertion/removal MUST use that mutex so new work sees coherent state; already captured work may finish using its old view.
- **FR-006**: EXTENSION and validated REHASH MUST share one lifecycle. Validation errors MUST preserve active configuration/state. Plugin start/runtime failures MUST be isolated as FAILED. Repeated requests MUST be idempotent.
- **FR-007**: Explicit cap-notify and CAP 302-or-higher clients MUST receive correctly targeted DEL/NEW for offered-set changes, queued after state publication in transition order, within protocol/SendQ limits and without duplicate names. Other clients MUST receive neither.
- **FR-008**: Notification support MUST persist once CAP 302 is indicated and MUST NOT be removable by that client. Older clients MAY enable/remove cap-notify explicitly. Core-owned cap-notify MUST appear in CAP LS even without capability plugins. Implicit-only support is omitted from LIST; explicit negotiation appears there. Positive REQ follows the offered catalog without a special exception.
- **FR-009**: REQ MUST remain all-or-nothing, echo the exact request and validate reply size before mutation. Removal MUST succeed for a held capability regardless of offers and for a discovered capability already pruned. Unknown unheld removal, unavailable addition and empty requests MUST reject without mutation.
- **FR-010**: Enable MUST send NEW without restoring prior negotiated memberships. Prerequisites MUST be declared and evaluated generically, distinguishing provider availability, session prerequisites and optional interactions; batch's existing plain fallback MUST remain.
- **FR-011**: Ordinary output MUST use its captured immutable runtime/session view without an admission recheck or retry. Old captured/preparing work MAY follow DEL. Batch groups MUST be admitted as complete queue operations including close, with plain fallback if their required capabilities are ineffective at group admission. Provider stop MUST run outside core locks and be safe with overlapping captured callbacks.
- **FR-012**: Every hook MUST define exception/invalid-result policy, including start/stop and cleanup. A plugin fault MUST NOT roll back committed core operations, publish partial contributions, leak resources or stop unrelated delivery. Diagnostics MUST be rate bounded and omit contents, credentials and arbitrary exception payloads.
- **FR-013**: Reply-length contributions MUST declare verifiable byte bounds. Startup/reload/enable MUST account for every compatible NAMES/WHO decoration and the full 512-byte command section. Tags MUST retain independent origin/total limits; SendQ MUST include framing/tag bytes.
- **FR-014**: Hot-path dispatch MUST avoid global registry/session scans per recipient and plugin calls under channel/output-queue locks, with bounded work/memory. Re-runnable measurements MUST cover mixed recipients, large fan-out, rendering, grouping and runtime changes.
- **FR-015**: Migration MUST proceed one capability at a time with relevant tests green after each. Public SPI changes MUST document a migration path consistent with unreleased-project rules.
- **FR-016**: Contracts MUST document the SPI, failure policy, dependencies and lifecycle, including explicit amendments to existing configuration/CAP contracts.

### Key Entities

- **Capability provider**: Discoverable behavior owner with identity, one offer, hooks, prerequisites, reply bounds and lifecycle state.
- **Capability runtime view**: Immutable offered providers, hook tables and effective session memberships captured under administrative control.
- **Session negotiation**: Explicit capabilities, remembered CAP version and notification eligibility.
- **Committed event**: Successful core operation, permitted audience and pre/post identity for correct notifications.
- **Reply contribution**: Validated, bounded adjustment to an allowed field or tag, composed with compatible providers.
- **Delivery group**: Related logical messages, optional request correlation and an ordered framing plan admitted to one recipient together.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All six capabilities pass the existing integration scenarios, with only the planned CAP LS expectation updates in #97; removing each eliminates its offer and behavior while core cap-notify remains. Zero capability-specific core behavior branches remain outside negotiation/lifecycle.
- **SC-002**: Real-socket tests pass for both administrative paths, all three client classes, disable/re-enable and cancellation. Every post-transition LIST and output whose view is captured after publication reflects new state; captured older work may finish after DEL. Whole admitted batches remain closed, and unadmitted groups use plain fallback when required capabilities have become ineffective.
- **SC-003**: Every hook category passes exception/malformed-result isolation tests; emitted command sections are at most 512 bytes, every live admitted batch closes once, and rejected operations publish no partial state.
- **SC-004**: An irctest run with the same pinned controller/revision/configuration as baseline has zero new failures or skips; unrelated existing failures are recorded.
- **SC-005**: Delivery stays below the existing one-second maximum and JOIN-storm quiet-channel p99 below 500 ms. Same-machine message throughput regresses by no more than 10% against the recorded main baseline, unless explicitly justified in review; delivery p99, CPU and allocation increases above 10% also require optimization or recorded justification. At 1,000 live sessions, administrative-mutex wait plus immutable snapshot publication/all-session pruning has a 100 ms p99 budget. Measure notification enqueue, configuration I/O, plugin start/stop and socket drain separately; do not wait for previously captured output. #102 supplies the dedicated load measurement and final SC-005 evidence.
- **SC-006**: A test-only new capability composes events, fields and output without adding capability-name decisions to core. Each requested future capability maps to a concrete seam, with additional domain work identified.

## Assumptions

- In-process trusted providers and existing discovery/dependency direction remain. Dynamic JAR installation, classloader unloading, untrusted-plugin sandboxing and a general asynchronous event bus are outside scope.
- Core advertises cap-notify in CAP LS, including a runtime with no capability plugins, and does not expose it as a toggleable extension. Implicit support is omitted from LIST; explicit negotiation appears there. Exact-LS and provider-absence tests are updated in #97.
- Snapshot capture is the ordinary-output boundary. Work captured or preparing during a change may finish after DEL; no flush or strict output cutoff is promised. Batch admission alone makes a current prerequisite choice between already prepared framed/plain alternatives, with no plugin callback under core locks.
- Batch's message-tags requirement is behavioral with plain fallback, not a new batch-only negotiation rejection or automatic offer withdrawal.
- Account state/events, persistent history, labeled-response protocol semantics and userhost-in-names are future work; only justified extension seams are included here.
- Public SPI breakage requires a migration guide and PR explanation under the repository's unreleased `0.1.0-SNAPSHOT` rule; first release supplies the version signal. External-plugin compatibility adapters are not required.

## Delivery

Four ordered PRs resolve the feature: [#100](https://github.com/jircd/jircd-server/issues/100) introduces the SPI and migrates server-time (these design docs land there); [#101](https://github.com/jircd/jircd-server/issues/101) migrates the other five and removes legacy hooks; [#97](https://github.com/jircd/jircd-server/issues/97) adds the runtime lifecycle and advertises cap-notify; [#102](https://github.com/jircd/jircd-server/issues/102) completes FAILED withdrawal, bounded diagnostics and SC-005 load evidence. Each PR ends with a green `./gradlew build` checkpoint. Intermediate PRs are not claims that all final-feature criteria are already satisfied.
