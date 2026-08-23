# Feature Specification: Away-Notify and Batch Capabilities

**Feature Branch**: `011-away-notify-batch`

**Created**: 2026-08-22

**Status**: Draft

**Input**: User description: "Add IRCv3 `away-notify` and `batch` capabilities as new
independently-toggleable capability modules, following the same pattern as the existing
`message-tags`, `server-time`, and `echo-message` capabilities. `away-notify`: clients who
negotiate it are notified when another user sharing a channel with them changes their AWAY
status (goes away with a reason, or comes back), building on the existing AWAY command.
`batch`: a generic mechanism letting the server group a set of related server-to-client
messages under one label, implemented as a generally reusable mechanism (not hardcoded to one
consumer), intended for future reuse by capabilities such as a not-yet-built chathistory
capability. Both are unblocked — away-notify only needs the already-implemented AWAY command
plus the existing capability-negotiation framework; batch has no dependency on accounts,
authentication, or any not-yet-built infrastructure. Corresponds to GitHub issue #4."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See when a channel-mate goes away or returns (Priority: P1)

A user in a busy channel wants to know, in real time, when the people they're talking to step
away or come back — without having to guess from silence or ask "you there?" They opt in to
this awareness once, and from then on the server keeps them informed automatically as it
happens.

**Why this priority**: This is the entire value of the feature — the notification behavior
itself. It delivers value the moment a single opted-in client shares a channel with anyone
who changes their away status, with no dependency on the batching mechanism.

**Independent Test**: Can be fully tested by having one client opt in to receive these
notices, sharing a channel with a second client, having the second client mark themselves
away (with a reason) and then return, and confirming the first client receives a distinct
notice for each transition while a client who has not opted in receives neither.

**Acceptance Scenarios**:

1. **Given** two users share a channel and one has opted in to receive away-status
   notifications, **When** the other user marks themselves away with a reason, **Then** the
   opted-in user receives a notice identifying who went away and their stated reason.
2. **Given** an opted-in user shares a channel with someone who is currently marked away,
   **When** that person returns (clears their away status), **Then** the opted-in user
   receives a notice that they are no longer away.
3. **Given** a user has not opted in to receive away-status notifications, **When** someone
   they share a channel with changes their away status, **Then** they receive no such notice
   (their experience is unchanged from today).
4. **Given** two users share more than one channel together, **When** one of them changes
   their away status, **Then** the other receives exactly one notice of the change, not one
   per shared channel.

---

### User Story 2 - Client can tell which server messages belong together (Priority: P2)

A client wants to present a set of related messages the server sends it as a single logical
group — for example, so a user interface can render them together or wait until the whole
group has arrived before displaying any of it — rather than treating every line as an
independent, unrelated event.

**Why this priority**: This is foundational, general-purpose plumbing rather than a
user-facing notification in its own right — nothing in this feature yet produces a batch of
its own, so its value today is purely the mechanism itself, ready for a future capability
(e.g. a not-yet-built history-retrieval capability) to use. It is lower priority than User
Story 1 because there is no end-user-visible behavior change until something else generates a
batch.

**Independent Test**: Can be fully tested by having an opted-in client trigger some
server-generated group of related messages and confirming the group is clearly marked as
starting, that every message belonging to the group is identifiably part of it, and that the
group is clearly marked as ending — independent of what specifically caused the group to be
sent.

**Acceptance Scenarios**:

1. **Given** a client has opted in to this capability, **When** the server needs to deliver a
   set of related messages together, **Then** the client receives a clear start marker
   identifying the group and its purpose, followed by the member messages each identifiable as
   belonging to that group, followed by a clear end marker for the same group.
2. **Given** a client has not opted in to this capability, **When** the server would otherwise
   have grouped a set of related messages, **Then** the client receives the same underlying
   messages without any grouping markers, exactly as it would have before this feature
   existed.
3. **Given** the server needs to deliver two unrelated groups of messages to the same client
   around the same time, **When** both are sent, **Then** each group is distinctly and
   unambiguously identifiable — a message belonging to one group is never confused with the
   other.

---

### Edge Cases

- What happens when a user changes their away status while another user is in the process of
  negotiating capabilities (hasn't yet confirmed opt-in)? The change is only delivered to
  connections that have already completed opting in at the moment the status change occurs;
  it is not queued or replayed for a connection that opts in afterward.
- What happens when a user sets an away reason, then immediately sets a different away reason
  without ever coming back in between? Each change (including a reason-only change while
  already away) produces its own notice to opted-in channel-mates, consistent with the
  existing AWAY command already confirming every such change to the sender themselves.
- What happens when the user going away and the opted-in observer share zero channels (e.g.
  only know of each other from a prior private message)? No notice is sent — this capability
  is scoped to shared-channel visibility only, the same visibility boundary other channel
  presence information already respects.
- What happens if a client disconnects mid-way through receiving a batch's member messages?
  The partial delivery is simply lost with the connection, the same as any other in-flight
  message; there is no replay or resumption mechanism.
- What happens if nothing ever triggers the server to use the batch mechanism (no consumer
  exists yet in this release)? Opting in to this capability is harmless and produces no
  visible behavior change beyond the opt-in acknowledgment — the mechanism sits ready and
  unused until a future capability generates a batch.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST offer away-status-change notification as an independently
  toggleable capability that a client opts into during capability negotiation, alongside the
  server's existing capability set.
- **FR-002**: System MUST notify every currently connected client that has opted into
  away-status-change notification and shares at least one channel with a user whenever that
  user sets, changes, or clears their away status.
- **FR-003**: System MUST identify, in each such notice, which user's away status changed and
  what their new away reason is (or that they are no longer away).
- **FR-004**: System MUST send at most one such notice per status change per recipient,
  regardless of how many channels the recipient and the changed-status user share in common.
- **FR-005**: System MUST NOT send any such notice to a client that has not opted into this
  capability, and MUST NOT change any other currently observable behavior of the AWAY command
  for clients who never opt in.
- **FR-006**: System MUST NOT send a notice to an opted-in client about a status change from a
  user with whom they share no channel.
- **FR-007**: System MUST offer a general-purpose message-grouping capability, independently
  toggleable during capability negotiation, that lets the server mark a set of related
  messages it sends to a client as belonging together.
- **FR-008**: System MUST clearly and unambiguously mark, for an opted-in client, the start of
  a message group (including the group's purpose/type), every message that is a member of
  that group, and the end of that same group.
- **FR-009**: System MUST support multiple concurrent or overlapping message groups being
  delivered to the same client without ambiguity about which member messages belong to which
  group.
- **FR-010**: System MUST NOT alter the messages a client that has not opted into the
  grouping capability receives — such a client MUST see the same content it would have
  received without this feature, with no grouping markers.
- **FR-011**: The grouping capability MUST be implementable and usable by this feature's own
  delivery needs without being hardcoded to any single specific use — it MUST be a
  general-purpose mechanism other, future capabilities can also use to group their own
  messages.
- **FR-012**: Neither capability MUST require a client to have any authenticated identity or
  registered account — both operate purely on the existing connection/channel-membership
  model already in place.

### Key Entities

- **Away-Status Change Notice**: A real-time, one-to-one server-to-client message identifying
  a user whose away status just changed and their resulting state (away with a reason, or no
  longer away); delivered independently to each opted-in, channel-sharing recipient at the
  moment the change happens — not persisted or replayed.
- **Message Group (Batch)**: A named, typed collection of otherwise-independent
  server-to-client messages that the server has marked as belonging together for one specific
  delivery, bounded by an explicit start and end marker; scoped to a single client connection
  and a single delivery, not a durable or shared object.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of currently connected, channel-sharing, opted-in clients receive an
  away-status-change notice within the same delivery time as a standard channel message.
- **SC-002**: 0% of away-status-change notices are received by clients that have not opted
  into the capability.
- **SC-003**: 100% of message groups delivered to an opted-in client have an unambiguous,
  correctly matched start marker, member-message identification, and end marker, verified
  across at least one scenario with two concurrent groups in flight to the same client.
- **SC-004**: 0% of clients that have not opted into the grouping capability observe any
  change to the messages they already received before this feature existed.

## Assumptions

- The visibility boundary for away-status-change notices — shared channel membership — is the
  same boundary already used elsewhere for presence-adjacent information; no new visibility or
  privacy concept is introduced by this feature.
- "Changes their away status" is read broadly: it covers going from not-away to away (with a
  reason), returning from away to not-away, and changing the away reason while remaining away
  — matching the existing AWAY command's own behavior, which already confirms all three cases
  identically to the sender.
- This feature introduces the message-grouping mechanism itself but does not itself produce
  any batch — no currently-shipped behavior is changed to route through it. Its value in this
  release is being ready for a future capability (e.g. a not-yet-built history-retrieval
  capability, tracked separately) to use, per the roadmap dependency noted in that item.
- Both capabilities follow the same opt-in-only, no-effect-if-declined posture already
  established by every other optional capability in this project — a client that never
  requests either capability observes zero behavior change.
- Neither capability depends on or requires the account/authentication module (also tracked
  separately on the roadmap); both operate purely on already-existing connection and channel
  state.
