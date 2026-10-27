# Feature Specification: Userhost in NAMES

**Feature Branch**: `015-userhost-in-names`

**Created**: 2026-10-27

**Status**: Draft — awaiting review before implementation

**Input**: [Issue #117](https://github.com/jircd/jircd-server/issues/117): Implement the userhost-in-names capability.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Populate identities without WHO (Priority: P1)

A client negotiates `userhost-in-names` and obtains each visible member's nickname, ident and display host directly in NAMES replies, avoiding an additional WHO request.

**Why this priority**: This supplies the capability's primary value and resolves the missing capability negotiation behavior.

**Independent Test**: Use two real socket clients, with and without negotiation, against the same channel; compare complete member identities and closing replies. Automated tests must be written first and shown failing before implementation.

**Acceptance Scenarios**:

1. **Given** the capability is offered, **When** a client requests it before or after registration, **Then** CAP ACK/LIST reflect successful negotiation and subsequent 353 entries contain `nick!ident@displayHost`.
2. **Given** a member has a cloaked host, **When** a negotiated client receives a NAMES listing, **Then** the identity contains that display host and never the underlying real host.
3. **Given** a client has not negotiated the capability or has removed it, **When** it receives NAMES, **Then** entries retain the ordinary nickname identity.
4. **Given** visible channel members, **When** NAMES is emitted through explicit targeted/bare NAMES or an automatic JOIN/SAJOIN listing, **Then** the same negotiated identity rule applies without changing visibility or 366 completion.

### User Story 2 - Compose identities and member statuses safely (Priority: P1)

A client negotiates both capabilities and receives every held status followed by the full member identity, even when a channel's list spans several replies.

**Why this priority**: Clients must receive complete lists within IRC limits when both independent features are active.

**Independent Test**: Exercise all four negotiation combinations with an operator who is also voiced and a channel large enough to require multiple 353 lines; verify reconstructed identities, exact status order and serialized sizes.

**Acceptance Scenarios**:

1. **Given** an operator and voiced member, **When** both capabilities are negotiated, **Then** the entry is `@+nick!ident@displayHost`; userhost alone retains the highest status and multi-prefix alone retains the bare nickname.
2. **Given** a long member list and maximum accepted identity lengths, **When** NAMES is sent, **Then** complete tokens split between 353 lines, every command section including CRLF is at most 512 UTF-8 bytes, and every expected visible member appears once with a final 366.
3. **Given** proposed limits that cannot fit one worst-case member with installed decorations, **When** startup or reload validates them, **Then** they are rejected with an actionable error; a failed reload preserves the prior configuration.

### User Story 3 - Control capability availability (Priority: P2)

An operator enables or disables the capability using the existing configuration and capability lifecycle controls.

**Why this priority**: Operators need the same availability and reload behavior as other capabilities.

**Independent Test**: Start enabled and disabled configurations, then toggle/reload with connected clients and verify offers, notifications, negotiations and subsequent NAMES captures.

**Acceptance Scenarios**:

1. **Given** `userhost-in-names` is enabled, **When** a client lists capabilities, **Then** it is advertised; when disabled it is absent and requesting it receives NAK without partial acceptance of a combined request.
2. **Given** a negotiated client, **When** the provider is disabled, **Then** existing lifecycle rules withdraw the offer and negotiation, notify eligible clients, and newly captured NAMES uses the default identity.
3. **Given** a provider is re-enabled, **When** a client receives its new offer, **Then** identity expansion resumes only after explicit renegotiation.

### Edge Cases

- Multibyte ident/display-host data and exact-fit versus one-byte-over limits are counted as UTF-8 bytes.
- Empty channels, invisible users and secret/private channels retain existing visibility and completion rules.
- WHO, unrelated numerics and non-negotiating peers retain existing behavior.
- Capability removal, provider withdrawal and output already captured before withdrawal follow existing lifecycle semantics; DEL is not an output barrier.
- Disabled but installed decorations still participate in conservative configuration validation.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Offer the standard `userhost-in-names` capability when enabled and support ordinary CAP negotiation before and after registration.
- **FR-002**: Expand every permitted NAMES identity to `nick!ident@displayHost` only for the requesting client that negotiated it, across all NAMES-producing paths.
- **FR-003**: Use only the identity already permitted for display, including the cloak when applicable; do not expose or resolve an underlying host for this feature.
- **FR-004**: Preserve existing authorization, visibility, member ordering rules and reply completion; leave WHO and other replies unchanged.
- **FR-005**: Compose expanded identity with independently selected status prefixes, including `@+` for multi-prefix, without requiring either capability to negotiate the other.
- **FR-006**: Limit every 353 command section to 512 UTF-8 bytes including CRLF; split lists only between complete tokens, never truncate member identities or lose members in accepted configurations.
- **FR-007**: Validate worst-case compatible identity/status widths at startup, reload and enable according to existing reply-budget rules. Reject invalid configuration atomically with an actionable diagnostic.
- **FR-008**: Support `enabled`/`disabled` configuration and existing provider lifecycle controls, notification rules and explicit renegotiation after re-enable.
- **FR-009**: Preserve CAP all-or-nothing request semantics when any requested capability is unavailable.

### Key Entities

- **Requester capability state**: The client's negotiated names and effective provider availability for a captured reply.
- **Presented member identity**: Nickname, ident and display host authorized for the requester; paired with held statuses independently.
- **NAMES listing**: Visible complete member tokens packed into bounded 353 replies followed by 366.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All acceptance scenarios pass; in the negotiated/unnegotiated, cloak and four-way status matrix, 100% of expected identities match and zero protected real hosts leak.
- **SC-002**: Boundary and multi-line scenarios deliver every expected visible member exactly once; zero 353 command sections exceed 512 bytes and all listings complete with 366.
- **SC-003**: The three capability-request cases identified in issue #117 pass with both capabilities enabled and declared in the validation harness; a full comparison adds no unexplained failures or skips.
- **SC-004**: Existing delivery remains below a one-second maximum and quiet-channel JOIN-storm p99 below 500 ms. Same-machine throughput loss stays within 10% of baseline; larger throughput, latency, CPU or allocation regressions require optimization or explicit review justification.

## Assumptions

- Follow the [IRCv3 userhost-in-names extension](https://ircv3.net/specs/extensions/userhost-in-names); legacy PROTOCTL/UHNAMES negotiation is outside scope.
- Reuse spec-014 capability services and privacy rules. Capability behavior belongs in its own module; no capability-specific core behavior is planned.
- Default availability follows existing optional capabilities (disabled unless configured). Existing generic lifecycle semantics apply without a new dependency on multi-prefix.
- The frozen irctest controller requires an explicit, documented harness change to enable and declare both capabilities. Preserve the old freeze, record a new patch/hash, and compare baseline/candidate with the same revised harness. Do not silently edit external harness state during this planning phase.
- No new commands, persistence, WHO behavior or dynamic JAR loading are introduced. The existing JOIN/NAMES hot path requires measured load evidence; performance results are not claimed by these design artifacts.
