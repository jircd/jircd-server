# Contract: `away-notify` and `batch` Capabilities

Extends `specs/001-ircv3-server/contracts/irc-protocol-commands.md` — same capability-
negotiation mechanics (`CAP LS`/`CAP REQ`/`CAP ACK`/`CAP NAK`) already documented there. This
document covers only the two capabilities this feature adds; every other command, capability,
and general rule from `001-ircv3-server`'s contract applies unchanged and is not repeated here.

This document does not edit `001-ircv3-server/contracts/irc-protocol-commands.md`'s statement
that "only `message-tags`, `server-time`, and `echo-message` may appear in the `CAP LS`
response for this release" — that file belongs to a closed feature and is not edited by this
one (the same convention `010-wallops-notices/contracts/wallops-command.md` already
established for its own closed-feature catalog). For this feature's purposes: `CAP LS` MAY now
also list `away-notify` and `batch`, alongside the three pre-existing capabilities.

## `away-notify` capability

| Capability | Effect once negotiated |
|---|---|
| `away-notify` | The negotiating session receives an `AWAY` notice (see below) whenever a user sharing a channel with it changes its away status — set, changed, or cleared. |

**Notice format** — reuses the existing `AWAY` command token, sent server→client (never
client→server in this direction):

| Message | Direction | Sent when | Recipients |
|---|---|---|---|
| `:nick!user@host AWAY :<reason>` | S→C | A user sets or changes their away reason (any successful `AWAY <text>` on that user's own connection) | Every currently connected session that (a) has negotiated `away-notify`, and (b) shares at least one channel with the changed user — deduplicated to one notice per recipient regardless of shared-channel count |
| `:nick!user@host AWAY` (no trailing parameter) | S→C | A user clears their away status (a successful bare `AWAY` on that user's own connection) | Same recipient rule as above |

**Contract notes**:

- The changed user's own confirmation reply (`306 RPL_NOWAWAY` / `305 RPL_UNAWAY`) is
  unaffected by this feature — it is sent to the changed user regardless of whether they or
  anyone else has negotiated `away-notify` (unchanged, pre-existing `AwayCommandHandler`
  behavior).
- A recipient who has not negotiated `away-notify` observes zero behavior change from this
  feature — the underlying `AWAY` command's existing behavior for the sender themselves, and
  for `WHOIS`/`WHO`/`PRIVMSG`'s existing away-reply behavior, is entirely unchanged (FR-005).
- No notice is sent for a user whose away status changes if the recipient shares zero channels
  with them, even with `away-notify` negotiated (FR-006) — the same shared-channel visibility
  boundary `NICK`'s existing broadcast already respects.
- A recipient that disconnects between the fan-out's recipient snapshot and its own
  `writer().enqueueRaw(...)` call is simply skipped, the same tolerance every other
  multi-recipient send in this codebase already has for a mid-send disconnect.

## `batch` capability

| Capability | Effect once negotiated |
|---|---|
| `batch` | Server-generated groups of related messages the negotiating session receives are wrapped in `BATCH +`/`BATCH -` framing with a `batch=<ref>` tag on each member, when the server chooses to send one. Declining `batch` (or declining `message-tags` alongside it) means every message is delivered exactly as it would be without this feature — no wrapper, no tag. |

**Wire format** — a new outbound-only use of the `BATCH` command token; there is no
client-issued form of `BATCH` in this release:

```text
:<server-name> BATCH +<reference-tag> <type> [<type-param> ...]
@batch=<reference-tag> <member message, unchanged otherwise>
@batch=<reference-tag> <member message, unchanged otherwise>
:<server-name> BATCH -<reference-tag>
```

| Field | Description |
|---|---|
| `<reference-tag>` | Opaque, unique per group, generated fresh per delivery (`UUID.randomUUID().toString()`) — carries no meaning beyond identifying this one group to this one recipient for this one delivery. |
| `<type>` | Caller-supplied string identifying the group's purpose; this feature defines no fixed vocabulary of types, since it ships no consumer of its own (spec.md Assumptions). |
| `<type-param>` | Zero or more caller-supplied strings, meaning defined by `<type>`. |

**Contract notes**:

- Emitting the `batch=<reference-tag>` tag requires the recipient to have negotiated **both**
  `batch` and `message-tags` — a recipient with `batch` alone still receives the member
  messages, but completely unwrapped, with no tag and no framing (research.md, Decision 2).
  This is stricter than the bare wire format above might suggest, and is a deliberate
  defensive choice: `SessionWriter.enqueueRaw`'s tag serialization applies zero capability
  gating on its own, so a caller/tag combination that reached an unprepared client once already
  in this codebase (005-fix-batch-conformance FR-010) must not be possible to reproduce here.
- Two or more concurrent groups delivered to the same recipient use distinct reference tags and
  are never ambiguous about which member belongs to which group (FR-009).
- This feature ships the mechanism only — no currently-shipped command routes any of its
  output through `BATCH` in this release. Negotiating `batch` today produces zero visible
  behavior change beyond the capability-acknowledgment itself, until a future capability (e.g.
  a not-yet-built history-retrieval capability, tracked separately on the project roadmap)
  chooses to use it.
