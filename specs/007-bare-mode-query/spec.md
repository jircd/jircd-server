# Feature Specification: Bare Channel Mode Query

**Feature Branch**: `007-bare-mode-query`

**Created**: 2026-08-21

**Status**: Draft

**Input**: User description: "Implement the bare channel MODE query — `MODE #channel` with no flag
argument — the one remaining loose end from the same deferred-scope cluster
`006-complete-core-protocol` just closed (+l/+k/+t). Currently returns the generic
unknown-mode error instead of the channel's current mode settings and creation time."

This feature is a fifth correctness/completeness follow-up to `001-ircv3-server`/
`003-irctest-conformance-fixes`/`005-fix-batch-conformance`/`006-complete-core-protocol`. The
original `003`/`005` scope-exclusion list grouped "the associated bare-MODE-query [error]
behavior" together with channel modes `+l`/`+k`/`+t` as one deferred item; `006` implemented the
three modes' own enforcement but never picked up this specific reply-format gap. Confirmed via a
fresh, full irctest sweep (excluding Ergo/Sable-specific tests) run immediately after `006` shipped
— this is the only new, actionable finding out of 51 failures; every other failure traces to an
already-documented exclusion (`metadata`/`multiline`/`draft/message-redaction` extensions,
`labeled-response`/`batch`/`multi-prefix`/`userhost-in-names` capabilities never implemented,
Modern-only `RPL_LOCALUSERS`/`RPL_GLOBALUSERS`, deprecated-marked irctest tests,
`INFO`'s remote-server-target routing) — none of that reopens. The historical
`333 RPL_TOPICWHOTIME` gap has since been implemented in #75: JOIN and TOPIC queries
now return the stored presented setter and Unix timestamp immediately after `332 RPL_TOPIC`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Member Sees a Channel's Current Mode Settings at a Glance (Priority: P1)

A client wants to see which channel-level settings (like invite-only, moderated, topic-lock, a
membership limit, or a key) are currently active on a channel, in one query, without needing to
already know which ones to check individually.

**Why this priority**: The core, most commonly relied-upon piece of this query — every mainstream
IRC client surfaces "channel modes" this way, and right now the query fails outright with a
generic, unrelated error instead of answering the question at all.

**Independent Test**: Set a mix of boolean and value-carrying modes on a channel, query its modes
with no flag argument, and verify the reply's mode string includes every active flag (with each
value-carrying flag's current value appended) — independent of Story 2.

**Acceptance Scenarios**:

1. **Given** a channel with `invite-only`, `members-only`, and `topic-lock` all active, **When** a
   member queries the channel's modes with no flag argument, **Then** the reply's mode string
   includes exactly those three flags.
2. **Given** a channel with a membership limit and a key both set, **When** its modes are queried,
   **Then** the reply's mode string includes both flags, followed by their current values in the
   same order the flags appear.
3. **Given** a channel with no channel-level flags currently active, **When** its modes are
   queried, **Then** the reply's mode string is exactly a bare `+`, not an empty value and not
   omitted.
4. **Given** a channel's ban list has entries, **When** its modes are queried with no flag
   argument, **Then** the ban list does NOT appear in this reply — ban masks and per-member
   privileges (operator, voice) have their own existing, dedicated query forms and are not part of
   this channel-level settings summary.

---

### User Story 2 - Any Member Can Check Settings, Not Just Operators (Priority: P2)

A client who is a member of a channel, but not an operator, wants to check the channel's current
settings without needing to ask an operator or attempt (and fail) a change first.

**Why this priority**: A read-only query having the same privilege requirement as making a change
would be a needless, surprising restriction inconsistent with how this server already treats other
read-only channel queries (viewing the topic, listing members) — but it's a narrower concern than
Story 1's core functionality gap.

**Independent Test**: As a non-operator member, query a channel's modes and verify the reply
succeeds — independent of Story 1's specific mode-string content.

**Acceptance Scenarios**:

1. **Given** a client who is a member of a channel but not an operator, **When** they query the
   channel's modes, **Then** the query succeeds the same way it would for an operator.
2. **Given** a private or secret channel, **When** a non-member, non-administrator client queries
   its modes, **Then** the query is refused the same way that channel is already hidden from a
   non-member querying its topic or membership — indistinguishable from a nonexistent channel.

---

### User Story 3 - The Query Also Reports When the Channel Was Created (Priority: P3)

A client querying a channel's settings also wants to know when the channel came into existence.

**Why this priority**: Lowest priority — an additional, smaller piece of information alongside the
mode string, useful but not the core ask, and not something any other existing command already
reports.

**Independent Test**: Query a channel's modes and verify the reply also includes its creation
time, distinct from the mode-string reply — independent of Stories 1 and 2.

**Acceptance Scenarios**:

1. **Given** any channel, **When** its modes are queried, **Then** the reply also includes the
   channel's creation time, as its own distinct part of the response.
2. **Given** a channel that is later recreated after becoming empty, **When** its modes are
   queried after recreation, **Then** the reported creation time reflects the recreation, not the
   original creation — consistent with every other piece of per-channel state already being reset
   when a zero-member channel is recreated.

---

### Edge Cases

- What happens if a channel has both boolean flags and value-carrying flags active at once? The
  mode string lists all flags together (letters only), followed by each value-carrying flag's
  value, in the same left-to-right order the flags themselves appear — not interleaved.
- What happens to a channel's reported creation time across a `+l`/`+k`/`+t` toggle, a topic
  change, or any other mode change? It's unaffected — only recreating the channel from zero
  members resets it, the same reset every other per-channel field already undergoes.
- What happens if this query is sent for a channel that doesn't exist at all? The existing
  not-found handling for a nonexistent channel is unchanged by this feature.

## Requirements *(mandatory)*

### Functional Requirements

**Mode Settings Summary**

- **FR-001**: Querying a channel's modes with no flag argument MUST return the channel's current
  set of active channel-level flags as a single mode string.
- **FR-002**: The mode string MUST include every currently active boolean flag and every currently
  active value-carrying flag, but MUST NOT include ban-mask entries or per-member privileges
  (operator, voice) — those remain available only through their own existing, dedicated query
  forms.
- **FR-003**: For each active value-carrying flag included in the mode string, its current value
  MUST also be included in the reply, in the same left-to-right order the flags themselves appear
  in the mode string.
- **FR-004**: When no channel-level flag is currently active, the mode string MUST be exactly a
  bare `+` — not an empty value, not omitted.

**Query Access**

- **FR-005**: Querying a channel's modes MUST NOT require channel-operator privilege — any current
  member MUST be able to query successfully.
- **FR-006**: A private or secret channel's mode query MUST be refused for a non-member,
  non-administrator requester the same way that channel is already hidden from such a requester
  for its topic and membership — indistinguishable from a nonexistent channel.

**Creation Time**

- **FR-007**: Querying a channel's modes MUST also report the channel's creation time, as a
  distinct part of the reply from the mode string itself.
- **FR-008**: A channel's reported creation time MUST reset when the channel is recreated after
  becoming empty, consistent with every other piece of per-channel state already resetting at that
  point.

### Key Entities

- **Channel** (already modeled in `001-ircv3-server`, extended in `006-complete-core-protocol`):
  gains one new piece of per-channel state — its creation time, set once and never changed for the
  life of that channel instance.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A client querying any channel's modes with no flag argument always receives a
  substantive reply describing that channel's current settings, never the generic unknown-mode
  error, 100% of the time.
- **SC-002**: A non-operator member querying a channel's modes succeeds exactly as often as an
  operator doing the same query.
- **SC-003**: Running the irctest conformance suite's test corresponding to this query
  (`chmodes/modeis.py::testChannelModeIs`) shows it passing, with no regression in any
  previously-passing test.

## Assumptions

- FR-002's scope boundary (channel-level boolean and value-carrying flags only, never list-kind or
  member-kind) follows Modern IRC's own documented reply shape for this query, and matches how
  this server's own `MODE #chan +b`/`NAMES` already provide the excluded information through their
  own dedicated forms — no new query mechanism is invented to duplicate that.
- FR-005/FR-006's privilege model mirrors this server's own existing `TOPIC` view-vs-set split
  (viewing requires no special privilege beyond the channel being visible; setting is
  operator-gated) — reused directly, not a new convention.
- This feature does not change how any channel-level flag is set or cleared, or the privilege
  required to do so — it only changes what a bare, flag-less query returns. `MODE #channel <flag>`
  (setting or clearing an actual flag) is entirely unaffected.
- The already-documented scope exclusions this feature does not touch (see Input above) remain
  exactly as previously decided — this feature does not reopen any of them.
