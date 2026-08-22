# Data Model: Away-Notify and Batch Capabilities

## Away-Status Change Notice

A real-time, one-to-one server-to-client message identifying a user whose away status just
changed and their resulting state. Not persisted, not replayed — a recipient who wasn't
connected and opted in at the moment of the change simply never sees it (spec.md's Key
Entities section; consistent with how `WALLOPS` notices already work).

| Field | Description |
|---|---|
| Sender identity | The changed user's `nick!user@host` hostmask, the same presented form every other channel-mate broadcast (`NICK`, `WALLOPS`) already uses. |
| New state | Either "away, with a reason" or "no longer away." |
| Reason | Present only when the new state is "away" — the exact text the sender's own `AWAY <text>` call supplied. Absent (no trailing parameter) when the new state is "no longer away." |

**Relationships**

- **Recipients** = (every session in every channel the changed user is currently a member of,
  unioned and deduplicated by session identity) ∩ (every session whose `negotiatedCapabilities()`
  contains `away-notify`) − {the changed user's own session}.
- One notice is generated per underlying `AWAY` command invocation that actually sets, changes,
  or clears a reason — not per channel, per FR-004's one-notice-per-recipient guarantee.

**Validation rules**

- FR-004: A given recipient receives at most one notice per change, regardless of how many
  channels they share with the changed user.
- FR-005: A recipient who never negotiated `away-notify` receives zero notices, ever.
- FR-006: A recipient sharing zero channels with the changed user receives zero notices for that
  change, even if they've negotiated `away-notify`.

**Wire shape** (reuses the existing `AWAY` command token, `Command.AWAY`):

```text
:nick!user@host AWAY :reason for being away
:nick!user@host AWAY
```

The second form (no trailing parameter) signals "no longer away," matching how `AwayCommandHandler`
already distinguishes an absent/empty parameter from a present one for the sender's own
confirmation.

---

## Message Group (Batch)

A named, typed collection of otherwise-independent server-to-client messages the server has
marked as belonging together for one specific delivery. Scoped to a single client connection and
a single delivery — not a durable or shared object, and not reused across sends (spec.md's Key
Entities section).

| Field | Description |
|---|---|
| Reference tag | An opaque, unique-per-delivery string identifying this group on the wire. Generated fresh per group as `UUID.randomUUID().toString()` (research.md, Decision 3) — never reused, never meaningful beyond uniqueness. |
| Type | A caller-supplied string identifying the group's purpose — meaning is entirely defined by whatever future capability generates the group; this feature imposes no fixed vocabulary. |
| Type parameters | An ordered list of caller-supplied strings, meaning defined by `type`, carried on the opening marker. |
| Member messages | An ordered list of otherwise-normal server-to-client messages, each additionally carrying a `batch=<reference tag>` tag once delivered. |

**Relationships**

- Delivered to exactly one recipient session per `enqueueBatch` call — a group is never shared
  across recipients; a caller wanting the same logical group visible to N recipients calls
  `enqueueBatch` N times, once per recipient, each producing its own independent reference tag.
- Not linked to any other entity in this data model — this feature introduces no consumer of its
  own (spec.md's Assumptions section).

**Validation rules**

- FR-008/FR-009: Every member message is unambiguously attributable to exactly one group via its
  `batch=<reference tag>` tag; two concurrent groups to the same recipient use distinct reference
  tags and are never confused.
- FR-010: A recipient who hasn't negotiated `batch` (or hasn't also negotiated `message-tags`,
  per research.md Decision 2) receives the member messages completely unwrapped — no start/end
  markers, no `batch` tag — identical to what they'd have received without this feature existing.

**Wire shape** (a new outbound-only usage of the `BATCH` token — no incoming/client-issued form
is supported in this release, per spec.md's User Story 2, which describes only server-generated
groups):

```text
:server.name BATCH +8fcb6...  <type> [typeParams...]
@batch=8fcb6...    :sender PRIVMSG #chan :first member message
@batch=8fcb6...    :sender PRIVMSG #chan :second member message
:server.name BATCH -8fcb6...
```

For a recipient who declined `batch` and/or `message-tags`, the same delivery instead produces
just the two member lines, untagged, with no `BATCH` framing at all.
