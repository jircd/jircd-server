# Phase 1 Data Model: Modular IRCv3 Chat Server

**Input**: [spec.md](./spec.md) Key Entities | **Research**: [research.md](./research.md)

All state below is in-memory only for this release (see plan.md "Storage").
Entities that exist only to support the deferred Story 3 (`Account`) are
noted but not modeled in detail here — they are out of scope for this plan.

Each entity below is tagged with its DDD role (**Aggregate Root**, Entity,
or Value Object) and the bounded context it belongs to (see plan.md "Domain
Model & Bounded Contexts"), so the data model and the domain model stay one
document, not two that can drift apart.

## ClientSession — *Aggregate Root, Session & Messaging*

A single connected client's live state, from TCP accept to disconnect. Its
`connectionId` is its identity; everything else about it is mutable state
guarded by this aggregate.

| Field | Type | Notes |
|---|---|---|
| `connectionId` | opaque identifier | Internal correlation key; never sent on the wire. |
| `channel` (I/O) | `SocketChannel` | The underlying connection (plaintext or TLS-wrapped, FR-018). |
| `outboundQueue` | bounded queue of `OutboundMessage` (below) | The *only* path by which any other session delivers a message to this one (FR-004 fan-out); drained exclusively by this session's own writer thread, per research.md "Message fan-out concurrency model". Never written to directly from another session's thread. Queue elements are **not** pre-formatted wire lines — this session's own writer thread does that at drain time, using this session's own `negotiatedCapabilities` (see `OutboundMessage`). |
| `registrationState` | enum: `CONNECTING`, `REGISTERED`, `CLOSING` | A session must reach `REGISTERED` (nickname + user info accepted, FR-001) before most commands are valid. |
| `nickname` | string, 0..1 | Present once registration succeeds; unique across all sessions (FR-002), compared case-insensitively per FR-052's casemapping (research.md "IRC casemapping"), not stored/compared in a normalized form — the original casing a client registered with is preserved and shown to others, only the *comparison* folds case; MUST also conform to the nickname grammar (contracts/irc-protocol-commands.md "Connection Registration Grammar") — uniqueness and format are independent checks (`433` vs. `432`). |
| `negotiatedCapabilities` | set of `Capability` names | Populated via CAP negotiation (FR-006, FR-007); empty for clients that never negotiate (FR-008). |
| `channelMemberships` | set of `Channel` references | Channels this session has joined; drives cleanup on disconnect (FR-017). |
| `rateLimitBucket` | token bucket state | Per-connection (FR-016); see research.md "Rate limiting". |
| `ident` | string, 0..1 | Absent until this session's `USER` command is processed (FR-001), then derived from its username field (FR-030); not independently verified (see spec.md Assumptions re: RFC 1413). Its presence is also the source-of-truth signal for "this session has already processed a `USER` command" (FR-001's one-shot restriction, `UserCommandHandler` precondition) — no separate boolean field duplicates that fact. |
| `realHostname` | string | The connection's actual display hostname/IP; always populated regardless of cloaking; source of truth for FR-032 (`WHOHOST`) and FR-038's self-lookup/administrator `WHOIS` cases. Never sent to a non-administrator client looking up a *different* client — see `UserIdentity.presentedForm` for the *display* value that case uses instead. |
| `administratorPrivilege` | boolean | Granted via FR-034's in-band credential command; authorizes FR-032 administrative commands. Independent of `channelMemberships`/operator status. Kept in lockstep with `userModes`'s `operator` entry below — never an independent third source of truth (research.md "User mode: `operator`"). |
| `userModes` | set of `UserMode` (below) | FR-044. This release's two possible members: `operator` (`o`), added the instant `administratorPrivilege` becomes `true` and removed the instant it becomes `false` — never independently toggled; and `invisible` (`i`, FR-061), freely set/cleared by the session itself via `MODE`, with no paired field to stay in sync with. See `UserMode` below and Validation rules. |
| `lastLivenessAt` | instant | Updated whenever this connection is known to be alive — traffic received from it, or a `PONG` answering the server's own `PING`. Read by this session's `LivenessMonitor` (research.md "Connection keep-alive") to decide when to probe and when to time out (FR-039). |

The session also holds an immutable `tls` boolean and nullable `sourceAddress`
(`InetAddress`) from the accepted socket. The source address is independent of
`realHostname` and never parsed from that display string. An absent address uses
a per-connection OPER key. The session's atomic `fallbackOperFailures` counter is
used only when the shared tracker is at capacity, increments on verified failures,
and resets on successful OPER.
OPER failure counts live in the admin extension's `OperAttemptTracker`, shared
across sessions rather than in `ClientSession`. Each entry contains the normalized
source bucket (IPv4 /32 or `ipv6ConnectionLimitPrefix`, mapped IPv4 normalized),
a failure count, and a fixed expiry instant from the first failed attempt.
Authentication and accounting are serialized per source to prevent concurrent connections
from exceeding the verification threshold. Successful OPER removes the entry;
locked attempts skip credential verification and disconnect. Expired entries are
reclaimed lazily on subsequent OPER commands. The map holds at most 10,000 entries;
new sources use per-session failure counting when full, preserving live lockouts
while allowing credential verification. The threshold still disconnects that
session, and success resets its counter.
The tracker uses an injectable clock and is cleared on server restart.

**Validation rules**:
- `nickname` MUST be unique across all `ClientSession`s at the moment it is
  committed (FR-002); the commit MUST be atomic so no two sessions can hold
  the same nickname even under concurrent registration attempts.
- A session in `CONNECTING` state MUST reject channel/messaging commands
  that require `REGISTERED` (FR-001).
- Before a session is even admitted to `CONNECTING` (i.e., before it can
  send any command at all), the connection-acceptance path MUST consult
  the `connection-admission` `extensionPoint` (FR-066, above) — a claiming
  extension's decision to reject closes the connection immediately, with
  no `ClientSession` ever created for it; with no extension currently
  claiming that point (this release's actual state), every connection is
  admitted, unconditionally.
- `USER` MUST be rejected (`462 ERR_ALREADYREGISTRED`,
  contracts/irc-protocol-commands.md "Registration completion
  sequencing") if `ident` is already set — regardless of whether this
  session has reached `REGISTERED` yet, since `ident` is set the moment
  the *first* `USER` is processed, before the other registration
  conditions (`NICK`, `CAP END`) necessarily hold (FR-001). A rejected
  `USER` MUST NOT alter `ident`/`UserIdentity.realname` or re-trigger the
  Registration Completion Burst.
- `realHostname` MUST NOT be overwritten or cleared by a cloak extension —
  see research.md "Cloak extension boundary" for why the real value's
  source of truth lives on `ClientSession` itself, not in the cloak
  extension.
- `administratorPrivilege` MUST only be *granted* (`false` → `true`) via
  FR-034's credential verification, never inferred from channel-operator
  status or any other field. It MAY be *revoked* (`true` → `false`) by
  the session's own `MODE <self> -o` (FR-044), which is the only other
  path allowed to change it — and doing so MUST also remove `operator`
  from `userModes` in the same act, never leaving the two out of sync
  (research.md "User mode: `operator`").
- OPER failures MUST accrue in the shared source bucket across reconnects and
  usernames. At threshold, OPER sends `464` and invokes normal FR-017 cleanup.
  Subsequent OPER commands during the window skip password verification and
  disconnect even with correct credentials. Success below threshold clears the
  bucket. The fixed deadline is never extended by attempts or configuration reload.
- With `operRequireTls`, plaintext OPER returns `491` without checking credentials
  or incrementing the counter; an already locked source still disconnects.
- `outboundQueue` reaching capacity (a member too slow to keep up with
  fan-out) MUST transition that session to `CLOSING` and run the same
  FR-017 cleanup as any other connection loss — a sender MUST NOT block
  waiting for a slow recipient's queue to drain (research.md "Message
  fan-out concurrency model").
- A `LivenessMonitor`-detected timeout (no traffic and no `PONG` within
  the configured window since `lastLivenessAt`) MUST transition that
  session to `CLOSING` and run the same FR-017 cleanup as any other
  connection loss (FR-039, research.md "Connection keep-alive") — a
  silently dead connection is not a special case distinct from a `QUIT`
  or a TCP-level close as far as cleanup is concerned, only in how it's
  detected.

**Lifecycle**: `CONNECTING` → `REGISTERED` → `CLOSING` (terminal; triggers
FR-017 cleanup: membership removal + notification to affected channels).
There is no path back from `CLOSING`.

## UserIdentity — *Value Object, Session & Messaging*

The nickname-level identity a session presents. Has no identity of its own
separate from its owning `ClientSession` — it's a computed presentation of
that session's state, not a thing you look up independently, which is what
makes it a Value Object rather than an Entity. For this release (Story 3
deferred), nothing survives a disconnect/reconnect except by the client
re-registering the same nickname (which succeeds only if no other session
currently holds it).

| Field | Type | Notes |
|---|---|---|
| `nickname` | string | See FR-002 uniqueness rule above. |
| `username` | string | Supplied at registration (FR-001); not independently unique; subject to `Hostmask`'s username content rule (contracts/irc-protocol-commands.md "Connection Registration Grammar"), not FR-054's UTF-8 requirement — it becomes `ident` in the wire hostmask, a protocol identifier, not human-readable content. |
| `realname` | string | Supplied at registration (FR-001); not independently unique; MUST be valid UTF-8 (FR-054) — rejected as malformed (FR-015) if not, the same as an invalid `PRIVMSG` body or topic. |
| *(computed)* `presentedForm` | string | `nickname!ident@displayHostname` (FR-030) — `displayHostname` is `ClientSession.realHostname` unless the `cloak` `ServerExtension` is currently enabled — live-checked against `ExtensionRegistry` state at computation time, never cached — in which case it is that extension's obfuscated value (FR-031, research.md "Cloak extension boundary"). Never persisted; computed at send time so a mid-session extension toggle is reflected immediately. |

*(Deferred, not modeled here: linking a `UserIdentity` to a persistent
`Account` — see spec.md's Account entity, FR-023/FR-024/FR-026/FR-027.)*

## OutboundMessage — *Value Object, Session & Messaging*

The element type of `ClientSession.outboundQueue` (research.md "Message
fan-out concurrency model"). Represents one message on its way to one
recipient, holding only the parts that are the same for every recipient of
that message — never a finished wire line. It has no identity of its own
and is immutable once created, so the same instance can be safely shared
across every recipient's queue for a single fan-out (e.g., every member of
a channel a `PRIVMSG` was sent to).

| Field | Type | Notes |
|---|---|---|
| `senderPresentedForm` | string | The sender's `UserIdentity.presentedForm` at send time — resolved by live-checking the current `cloak` `ServerExtension` state at that moment (FR-031), never cached. Computed once by the sender's thread, not per recipient, since cloaking is a uniform display transform applied identically to every viewer — unlike a negotiated capability, no recipient-specific input ever factors into this value (see Validation rules below). |
| `command` | string | e.g., `PRIVMSG`, `NOTICE`, `JOIN`, `KICK` — which wire command this delivery represents. |
| `target` | string | Channel name or nickname the original command targeted. |
| `body` | string, 0..1 | The message text, where applicable — a `PRIVMSG`/`NOTICE` body, a `PART` reason if one was given (absent if not — `PART`'s reason stays genuinely optional, unlike `QUIT`'s), or a `QUIT` reason, always present for `QUIT` since FR-060 requires a default when the client didn't supply one (absent for a bare `JOIN` notification, which carries no text at all). MUST be valid UTF-8 (FR-054, which now also covers `QUIT`/`PART` reasons) — an invalid byte sequence in a client-supplied value is rejected as malformed (FR-015) before an `OutboundMessage` is ever constructed for it, so this field never holds one; a server-supplied default reason is, by construction, always valid UTF-8. |
| `sentAt` | instant | Captured once, by the sender's thread, at the moment of sending — the value the `server-time` capability's `time` tag reflects for every recipient (not each recipient's own drain time). |
| `messageId` | `UUID` | FR-059. Generated once, by the sender's thread, at construction — every `OutboundMessage` gets one, regardless of `command`. The value the `message-tags` capability's `msgid` tag reflects for every recipient that has negotiated `message-tags` at all (research.md "Message identifiers"); never sent to a recipient that hasn't. |

**Validation rules**: `OutboundMessage` MUST NOT contain a `message-tags`
prefix, a `time` tag, a `msgid` tag, or any other capability-dependent
decoration — those are computed by each recipient's own writer thread at
drain time (research.md "Message fan-out concurrency model"), by
re-checking that recipient's `ClientSession.negotiatedCapabilities`
against the live state of the corresponding `CapabilityExtension`
(`Capability` validation rules, above). `messageId` itself is NOT
capability-dependent — it's generated unconditionally for every
`OutboundMessage`, the same as `sentAt` — only whether it's *rendered as
a tag* depends on the recipient's negotiated capabilities; the id's
existence and value never do. An `OutboundMessage` is capability-agnostic
by construction — that is what makes sharing one instance across
recipients with different negotiated capabilities safe and correct.
Symmetrically, `senderPresentedForm`
MUST be resolved solely from the live `cloak` `ServerExtension` state at the
sender thread's send time — never from a cached hostname value, and never
influenced by any individual recipient's state (capabilities or otherwise).
That is what makes baking it once into a shared immutable `OutboundMessage`
correct rather than merely convenient: server-extension state is uniform
across every recipient, so resolving it once is equivalent to resolving it
per recipient, which is exactly the property capability state lacks.

## Channel — *Aggregate Root, Session & Messaging*

A named, joinable group through which members exchange messages. Its
`name` is its identity; membership and moderation state are guarded by
this aggregate (e.g., "first joiner becomes operator" is an invariant
enforced at channel-creation time, not left to callers).

| Field | Type | Notes |
|---|---|---|
| `name` | string | Unique across the server (FR-003), compared case-insensitively per FR-052's casemapping (research.md "IRC casemapping") — `#Foo` and `#foo` are the same channel, not two; the casing of whichever `JOIN` created the channel is preserved and shown to others. MUST conform to FR-048's grammar *and* be valid UTF-8 (FR-054) — FR-048's byte-level exclusions (space, comma, control characters) don't by themselves guarantee well-formed UTF-8, so this is an additional, independent check, not implied by the grammar alone. First JOIN of a name creates it. |
| `members` | set of `ClientSession` references | Current membership; drives message fan-out (FR-004). |
| `operators` | set of `ClientSession` references (subset of `members`) | Who may perform moderation actions (FR-013, FR-014). |
| `voiced` | set of `ClientSession` references (subset of `members`, independent of `operators`) | Who, in addition to `operators`, may send while `MODERATED` is active (FR-045). Granted/revoked only by an operator via `MODE +v`/`-v <nickname>` (FR-013, FR-045) — unlike `operators`, nothing grants this at JOIN time; a channel MAY have any number of voiced members, none of whom need to be operators. |
| `activeModes` | set of `ChannelMode` (below) | Which recognized *channel-scoped* mode flags are currently set on this channel. `MEMBERS_ONLY` and `MODERATED` (FR-013) are the only two flags any code defines in this release, but the type is an open set, not a closed enum — see `ChannelMode` below for why (FR-043, research.md "Channel/user mode extensibility"). The two flags are independent: a channel MAY have neither, either, or both set at once (matches standard IRC's per-flag `MODE` semantics — this replaces an earlier, incorrect single-mutually-exclusive-state design). Set/cleared only by an operator via `MODE` (FR-013, FR-014). `voice` (FR-045) is a *`MEMBER`-scoped* `ChannelMode` (see `ChannelMode.kind` below) — its state lives in `voiced` above, not here, the same way `operator` status lives in `operators` rather than `activeModes`. |
| `topic` | string, 0..1 | Absent (no topic set) by default. Visible to any client via `TOPIC` regardless of membership (FR-041's discovery framing applies here too); settable only by an `operator` (FR-040). Distinct from `activeModes` — viewing/setting the topic is not a "who may send a `PRIVMSG`" concern. MUST be valid UTF-8 (FR-054) — a `TOPIC`-set attempt with an invalid byte sequence is rejected as malformed (FR-015), leaving the previous topic (or absence of one) unchanged. MUST NOT exceed `ServerConfiguration.topicMaxLength` (FR-056, default `390`) — a `TOPIC`-set attempt exceeding it is rejected with `417 ERR_INPUTTOOLONG`, leaving the previous topic unchanged, the same "reject, don't apply partially" treatment as the UTF-8 check. |
| `topicSetter` | string, 0..1 | Setter's `nick!user@host` resolved through `PresentedIdentity` when the topic is set, respecting cloaking. Stored unchanged across subsequent nickname or cloak changes. Absent when there is no topic. |
| `topicSetAt` | Unix timestamp, 0..1 | Seconds since the Unix epoch, captured with the topic and setter on each accepted topic change. Absent when there is no topic. |
| `bans` | list of `BanEntry` (below) | The `ban-mask` `ChannelMode`'s `LIST`-kind state (FR-062) — unlike `activeModes`, this is its own dedicated field, the same "each `kind` gets appropriate storage" pattern `operators`/`voiced` already established for `MEMBER`-kind flags (research.md "Channel/user mode extensibility" — "`LIST`-kind flags in practice"). Set/cleared only by an operator via `MODE +b`/`-b <mask>` (FR-013, FR-062). Bounded to a fixed maximum of 100 entries — `478 ERR_BANLISTFULL` rejects an addition beyond it. |
| `invited` | set of string (casefolded nicknames) | The `invite-only` `ChannelMode`'s bookkeeping (FR-065) — a purpose-built field of its own, the same pattern `bans` established for `ban-mask` rather than a generic reusable invite-list mechanism (research.md "Channel invitations"). Populated by `INVITE`; consumed (entry removed) the moment the named nickname successfully `JOIN`s this channel, whether or not `invite-only` was active at that moment. No size cap and no expiration — unlike `bans`, this is self-bounding, transient state (research.md "Channel invitations" — Rationale). Not reset on a member's `PART`/disconnect the way `voiced`/`operators` are — an invitation targets a nickname a client hasn't joined under yet, so there is no membership to reset; it IS reset (cleared entirely), like every other per-channel field, when the channel is recreated from zero members. |

**Validation rules**:
- Topic text, setter and timestamp are published as one immutable snapshot.
  Every accepted nonempty TOPIC set replaces all three; an empty topic clears
  all three. Rejected changes preserve the previous snapshot. JOIN and TOPIC
  queries send `333 RPL_TOPICWHOTIME` immediately after `332 RPL_TOPIC`, using
  the same snapshot, and never send `333` without a topic.
- `name` uniqueness is enforced the same way as nickname uniqueness (single
  atomic namespace, FR-003).
- The first session to join a not-yet-existing channel is added to
  `operators` (classic first-join-gets-operator default, FR-013); this is
  the **only** *initial* operator-assignment rule in this release
  (FR-026/FR-027's account-based override is deferred) — an existing
  operator MAY subsequently grant `operators` membership to another
  session via `MODE +o`/`-o <nickname>` (FR-046), the same way `voiced`
  is granted (FR-045). `operators` MAY become empty if every current
  operator revokes their own status (or another's, down to zero) or
  leaves without granting a successor first — this release has no
  automatic reassignment for an already-created channel (unlike the
  first-join rule, which only applies to *creating* one); the channel
  simply has no operator until one is granted again or the channel is
  recreated from zero members (below).
- A channel with zero members is not a durable entity — the next JOIN of
  that name creates a fresh channel with default (empty) `operators` and
  `voiced`, per FR-003 (this release keeps no channel history/state after
  last-member-leaves, since Story 3's chathistory-adjacent capabilities
  are deferred). `topic` is reset along with everything else — a
  recreated channel starts with no topic set, same as a brand-new one.
- `topic` MUST only be set by a session in `operators` (FR-040); a
  non-operator's attempt MUST be rejected with the same `482
  ERR_CHANOPRIVSNEEDED` error FR-014's other operator-gated actions use,
  not a new error of its own.
- Two administrator-only exceptions exist to the rules above, each its
  own dedicated command rather than a silent bypass on `JOIN`/the
  `operators`-membership rule (FR-057/FR-058, research.md "Administrator
  channel override"): `SAJOIN` performs the same create-or-join `JOIN`
  does but skips the `JOIN`-gate check point entirely; `SAMODE` adds the
  sender to `operators` (or removes them) without requiring the sender
  to already be in `operators` first, self-targeting only. Neither
  exception applies to ordinary `JOIN` or to FR-046's operator-granting
  `MODE` path — an administrator using those normally is checked
  exactly like anyone else.
- Any command whose semantics a `ChannelMode` can gate (currently `SEND`
  — `PRIVMSG`/`NOTICE` — `JOIN`, and `DISCOVER` — `TOPIC`-viewing,
  `NAMES`, `LIST` — `ChannelMode.gates` above) MUST reject the attempt
  unless it passes every currently-recognized flag whose `gates`
  includes that command's action, checked independently per flag, not
  as alternative states of one variable (FR-013, FR-043's "not limited
  to... sending" clause). "Currently-recognized" is deliberately not
  "currently in `activeModes`" — that was this check's original,
  narrower framing, correct only as long as every gating flag was
  `BOOLEAN`-kind; `ban-mask` (`LIST`-kind, FR-062) is always "in effect"
  in the sense that its restriction is always live, so the check point
  asks *every* recognized gating flag for its own kind-appropriate
  pass/fail predicate rather than first filtering by `activeModes`
  membership (research.md "Channel/user mode extensibility" — "`LIST`-kind
  flags in practice"). The pass/fail decision for each flag is provided
  by whoever defines it — `CORE`'s own logic for its built-in flags, or
  a future extension's own logic for one it contributes — not hardcoded
  per-flag-id inside the command handler; this is what makes it possible
  to add a new gating flag without editing the handler for whichever
  command it gates.
  - For `SEND` today: `MEMBERS_ONLY` requires the sender to be in
    `members`; `MODERATED` requires the sender to be in `operators` **or**
    `voiced` (FR-045) — matching classic IRC's `+m` semantics in full, not
    just the operator half of it; `operators`-or-`voiced` *is* the one
    condition for `MODERATED`, not two independent ones — an operator
    does not additionally need to be voiced. `ban-mask`'s predicate
    (FR-062): neither the sender's current `UserIdentity.presentedForm`
    **nor** `nickname!ident@ClientSession.realHostname` MUST match any
    entry in `bans` — checking both independently, not just the
    presented form, so a mask targeting a member's real, underlying
    identity still applies even if a cloaking extension currently
    presents something else (research.md "Channel/user mode
    extensibility" — "`LIST`-kind flags in practice"). A match on either
    mutes (rejects the send) without removing the sender from `members`;
    `442`/`404` are otherwise unrelated failure classes checked
    independently (a banned member who also isn't a member at all,
    impossible by construction since a ban-mute only applies to someone
    already in `members`, is not a case that can arise).
  - For `JOIN` today: two currently-defined `ChannelMode`s gate
    `{JOIN}`, each checked independently — a `JOIN` succeeds only if
    both pass. `ban-mask`'s predicate (FR-062) is the same dual check as
    `SEND`'s: neither the joiner's `UserIdentity.presentedForm` nor
    their real-hostname-based identity may match any entry in `bans`,
    rejected with `474 ERR_BANNEDFROMCHAN` if either does. `invite-only`'s
    predicate (FR-065): pass automatically if `invite-only` isn't
    currently in `activeModes`; otherwise pass only if the joiner's
    current casefolded nickname is present in `Channel.invited`
    (`BanEntry`'s sibling, above), rejected with `473
    ERR_INVITEONLYCHAN` if not — and, when it does pass this way, the
    matching `Channel.invited` entry is consumed (removed) as part of
    the same successful `JOIN`. Because the two predicates are checked
    independently rather than combined into one condition, a held
    invitation never overrides an active ban targeting the same client
    — it only ever satisfies `invite-only`'s own check (research.md
    "Channel invitations"). `ban-mask` was the first flag validated
    against this check point's design (research.md "Validating the
    extensibility promise against a future `JOIN`-gating flag");
    `invite-only` is the flag that promise was originally validated
    *for*, hypothetically, before either one existed — no change to
    `JoinCommandHandler`'s shape was required to add either, only a
    `ChannelMode` catalog entry and each flag's own matching logic,
    confirming the promise held twice over. `SAJOIN` (FR-057) is wired
    to this exact check point and skips it unconditionally, including
    both flags' — an administrator's force-join bypasses a ban and
    `invite-only` alike, the same way it would bypass any future
    `JOIN`-gating flag.
  - For `DISCOVER` today: `private` and `secret` (FR-047) both require
    the requester to either be in `members` or hold
    `administratorPrivilege`; unlike `SEND`/`JOIN`'s gate failures, a
    failed `DISCOVER` check MUST produce the exact same response
    `TOPIC`/`NAMES`/`LIST` would give for a channel that doesn't exist at
    all (`ChannelMode.gates` above) — the point of `private`/`secret` is
    that a non-member can't distinguish "doesn't exist" from "exists but
    hidden," which a permission-denied-style error would defeat.
- `voiced` MUST only be granted or revoked by a session in `operators`
  (FR-045); a non-operator's attempt MUST be rejected with the same `482
  ERR_CHANOPRIVSNEEDED` error every other operator-gated action uses.
  Targeting a nickname that isn't currently a member of the channel MUST
  be rejected with a specific "not on channel" error rather than
  silently granting voice to no one.
- `voiced` is reset the same way `operators` and `topic` are: a member
  who parts (or otherwise leaves) is removed from `voiced` immediately,
  and a channel recreated after reaching zero members (below) starts with
  `voiced` empty, same as `operators` — rejoining does not restore a
  member's prior voice status; an operator must grant it again.
- `operators` membership MAY likewise be granted or revoked by an
  existing operator via `MODE +o`/`-o <nickname>` (FR-046), subject to
  the identical rules `voiced` grant/revoke uses: operator-only, `482` on
  an unauthorized attempt, a specific "not on channel" error for a
  non-member target, and reset (removed from `operators`) on that
  member's part/leave. An operator MAY revoke their own status; the
  server MUST NOT reject a self-revocation or treat it specially, even if
  it leaves the channel with zero operators (see above).
- `bans` MUST only be modified by a session in `operators` via `MODE
  +b`/`-b <mask>` (FR-013, FR-062); a non-operator's attempt MUST be
  rejected with the same `482 ERR_CHANOPRIVSNEEDED` error every other
  operator-gated action uses. A supplied mask missing its `user` and/or
  `host` segment MUST have the missing segment(s) filled with `*` before
  being stored or matched against `bans` (standard IRC ban-mask
  convention) — `MODE +b alice` is stored as `alice!*@*`. Adding a mask
  already present (after this normalization), or removing one not
  present, MUST be treated as a harmless no-op, not an error — the same
  idempotent-change posture every other mode change in this
  specification uses. `bans` is NOT reset on membership changes the way
  `operators`/`voiced` are — a ban persists whether or not its target,
  or anyone else, is currently a member; it IS reset when the channel
  itself is recreated from zero members (above), the same as every other
  per-channel state. Adding a mask that would bring `bans` above 100
  entries MUST be rejected with `478 ERR_BANLISTFULL`, the previous
  contents left untouched — removing a mask is never subject to this
  limit. Each `BanEntry`'s `setBy`/`setAt` (below) MUST reflect the
  acting operator's nickname and the current time at the moment `+b`
  succeeds — never retroactively altered by anything other than a
  further `MODE +b` re-adding the same (already-normalized) mask after
  it was removed, which creates a fresh `BanEntry`, not a
  mutation of a prior one.
- `MEMBERS_ONLY` and `MODERATED` are defined by core and MUST always be
  recognized, unconditionally, never gated by `Extension` state (FR-036).
  A `ServerExtension` MAY additionally contribute further `ChannelMode`
  definitions (research.md "Channel/user mode extensibility") — unlike
  the two core flags, an extension-contributed flag is only recognized
  while its owning extension is `ENABLED`; a channel's `activeModes` MUST
  NOT contain a flag whose defining extension is currently disabled.
  Exactly what happens to a channel that already had such a flag set at
  the moment its extension is disabled is unresolved in this release,
  since no extension contributes a mode yet — to be settled when the
  first one (e.g., a future account module's registered-channel flag)
  actually exists.

**Lifecycle**: created on first JOIN → members join/part → removed when
membership reaches zero (no persistence across recreation, per above).

## BanEntry — *Value Object, Session & Messaging*

One active ban-mask entry on a `Channel` (FR-062) — the element type of
`Channel.bans`. Immutable once created; removing a ban deletes the
entry rather than mutating it, and re-adding the same mask later
creates a fresh one with a new `setBy`/`setAt`.

| Field | Type | Notes |
|---|---|---|
| `mask` | string | A `nick!user@host` pattern, `*`/`?` wildcards permitted in any segment — always fully normalized (no segment omitted) before storage; a partial mask supplied to `MODE +b` has its missing segment(s) filled with `*` first (`Channel` validation rules, above). Matched against a client's `UserIdentity.presentedForm` (FR-030/FR-031) **and**, independently, against `nickname!ident@ClientSession.realHostname` (FR-032) — a match on either applies the ban, case-insensitively (research.md "Channel/user mode extensibility" — "`LIST`-kind flags in practice"). |
| `setBy` | string | The nickname of the operator whose `MODE +b` created this entry, captured at that moment — not updated if that operator later changes their own nickname. |
| `setAt` | instant | When this entry was created. |

**Validation rules**: `mask` uniqueness within one `Channel.bans` is
enforced after normalization — two `MODE +b` calls that normalize to
the same string MUST NOT produce two entries (`Channel` validation
rules' idempotent-add behavior, above).

## ChannelMode — *Value Object, Session & Messaging*

One recognized channel-mode flag *definition* — not a flag being on or
off for a particular channel (that's `Channel.activeModes` membership),
but the flag's stable identity, wire representation, shape, and who
defines it. Exists so the set of recognized flags can grow (a
`ServerExtension` contributing one) without changing `Channel`'s shape,
the same role `Capability` plays for `CAP` (data-model.md "Capability").

| Field | Type | Notes |
|---|---|---|
| `id` | string | Stable, human-readable identifier (e.g. `moderated`, `members-only`), independent of `flag` — the same role `Extension.id`/`Capability.name` play elsewhere in this data model. Unique among every currently-recognized `ChannelMode`. Exists separately from `flag` because the wire-letter namespace is far scarcer (52 possible characters, shared across every current and future flag) than the id namespace, and because error messages and administrator-facing output need something more legible than a single letter (constitution Principle III). |
| `flag` | character | The wire-protocol mode letter (`m` for `moderated`, `n` for `members-only` — RFC 2811 §4.2.6/§4.2.5). Unique among every currently-recognized flag — core's plus every currently-`ENABLED` extension's — independently of `id` uniqueness; a conflicting registration (either kind) is rejected the same way `Extension.extensionPoint` ownership conflicts are (research.md "Extension-point ownership"). |
| `kind` | enum: `BOOLEAN`, `VALUE`, `LIST`, `MEMBER` | Classifies the flag's shape, per RFC 2811's own mode taxonomy (contracts/irc-protocol-commands.md "Full Channel Mode Catalog"). `BOOLEAN`: a per-channel on/off flag, represented in `Channel.activeModes`. `VALUE` (e.g. a channel key) still carries data no field on `Channel` holds — not implemented this release. `LIST` (ban-mask) is implemented for exactly the one `CORE` flag that needs it, state in `Channel.bans` (FR-062) — not a generic LIST-storage mechanism a future extension-contributed `LIST`-kind flag could reuse as-is (see Validation rules). `MEMBER` (operator, voice) is a per-nickname privilege, not a per-channel flag — its state lives in its own dedicated `Channel` field (`operators` for `operator`, FR-046; `voiced` for `voice`, FR-045), not in `activeModes`. |
| `gates` | set of `GateableAction` (`SEND`, `JOIN`, `DISCOVER`), 0..* | Which command(s) this flag restricts, independent of `kind` — a `BOOLEAN` flag isn't assumed to gate `PRIVMSG`/`NOTICE` just because that's what this release's first two happen to do (FR-043's "Critically, the guarantee is not limited to..." clause). `moderated`/`members-only` gate `{SEND}`. `private`/`secret` gate `{DISCOVER}` (FR-047) — `TOPIC`-viewing, `NAMES`, and `LIST` for a non-member. `ban-mask` gates `{SEND, JOIN}` (FR-062) — the first flag to gate more than one action at once, muting an already-present match's `SEND` and rejecting a not-yet-present match's `JOIN`. `invite-only` gates `{JOIN}` alone (FR-065) — checked independently of `ban-mask`'s own `{JOIN}` predicate, never merged into one condition, so a held invitation never overrides an active ban. `voice`/`operator` gate `{}` (empty) — they're privileges other flags' gate checks *consult*, not gates in their own right; nothing directly requires having voice or being an operator to perform an action, except as an input to `moderated`'s `SEND` check or FR-014's operator-gated actions (which aren't `ChannelMode`-driven at all). `DISCOVER`'s gate-failure convention differs from `SEND`/`JOIN`'s: a failed `DISCOVER` check MUST produce the same response as "this channel does not exist," never a distinguishable permission error (FR-047) — the whole point is that a non-member can't tell the two apart. See `Channel.activeModes` validation rules for how a command handler uses this to decide which flags apply to it. |
| `definedBy` | `CORE` or an `Extension` id | `CORE`-defined flags (`moderated`, `members-only`, `voice`, `operator`, `private`, `secret`, `ban-mask`, `invite-only`) are always recognized (FR-036). An extension-defined flag is only recognized while that extension is `ENABLED` — see `Channel.activeModes` validation rules above. No extension currently defines one; every implemented channel-mode flag so far has turned out to belong in `CORE` (research.md "Channel invitations" — "Core vs. extension, revisited"). |

**Validation rules**:
- `flag` uniqueness and `id` uniqueness are independent requirements, both
  enforced at all times: two flags MUST NOT share a `flag` character, and
  two flags MUST NOT share an `id`, regardless of whether one, both, or
  neither is core-defined (research.md "Channel/user mode extensibility").
- This release populates exactly eight `ChannelMode`s, all `CORE`-defined:
  five `BOOLEAN` (`id: moderated, flag: m`, `gates: {SEND}` and
  `id: members-only, flag: n`, `gates: {SEND}`, FR-013/FR-043;
  `id: private, flag: p` and `id: secret, flag: s`, both `gates:
  {DISCOVER}`, FR-047; `id: invite-only, flag: i`, `gates: {JOIN}`,
  FR-065, set/cleared via `MODE +i`/`-i` like the other four, bookkeeping
  in `Channel.invited` above), two `MEMBER`, `gates: {}` (`id: voice,
  flag: v`, FR-045, granted/revoked via `MODE +v`/`-v <nickname>`, state
  in `Channel.voiced`; `id: operator, flag: o`, FR-046, granted/revoked
  via `MODE +o`/`-o <nickname>`, state in `Channel.operators`), and one
  `LIST` (`id: ban-mask, flag: b`, `gates: {SEND, JOIN}`, FR-062,
  granted/revoked via `MODE +b`/`-b <mask>`, state in `Channel.bans`); no
  extension in this release contributes one. The mechanism exists now so
  a future `BOOLEAN`, `gates: {SEND}` or `gates: {JOIN}` one (e.g., a
  registered-channel flag once the account module exists) doesn't
  require a `Channel`/`ChannelMode` data-model change to add — only a
  new extension defining the flag and its gate logic; `invite-only`
  itself, once a concrete case rather than a hypothetical one, turned
  out to belong in `CORE` instead (research.md "Channel invitations" —
  "Core vs. extension, revisited"), the same placement `ban-mask`
  already has.
- `private` and `secret` are mutually exclusive, per RFC 2811: setting
  one via `MODE` MUST clear the other if it was active, rather than
  allowing both simultaneously — the one deviation in this release from
  `activeModes`' otherwise-independent-flags rule (above). This release
  treats their `DISCOVER`-gate effect identically (FR-047) rather than
  implementing the softer, less consistently-defined "listed but
  obscured" variant some historical networks gave `private` alone — a
  deliberate simplification, not an oversight.
- A `DISCOVER` check against `private`/`secret` MUST pass automatically
  for a session holding `administratorPrivilege` (FR-047), regardless of
  channel membership — the same transparency guarantee FR-032 already
  gives administrators over a cloaked member's real hostname
  (research.md "Cloak extension boundary"), extended here to channel
  visibility rather than identity.
- A `VALUE`-kind `ChannelMode` (e.g. a channel key) MUST NOT be
  contributed in this release, by `CORE` or any extension:
  `Channel`'s shape has nowhere to hold a value, and no mechanism here
  defines one. This is a real, currently-unfilled gap, not an oversight
  masked by convenient scoping — the first consumer that needs one
  (e.g., a channel-key feature) requires a `Channel` shape change
  alongside it, which this data model deliberately does not attempt to
  pre-design without a concrete consumer driving the actual
  requirements, the same judgment call that was made for `LIST`-kind
  flags until `ban-mask` (FR-062) became that concrete consumer.
- A `LIST`-kind `ChannelMode` MUST NOT be contributed by a
  `ServerExtension` in this release — only `CORE`'s single `ban-mask`
  flag is implemented, backed by the purpose-built `Channel.bans`
  field (`BanEntry`, above), not a generic, reusable LIST-storage
  mechanism. A future extension-contributed `LIST`-kind flag (e.g., an
  invite-exception or ban-exception list) would need its own
  purpose-built field the same way `ban-mask` got `bans`, designed
  against that flag's actual requirements when it exists — this data
  model does not attempt to generalize `bans` into a reusable
  "any `LIST`-kind flag's storage" shape speculatively, the same
  "don't guess the right general shape before a second concrete
  consumer exists" discipline this project applies elsewhere (e.g.,
  research.md "Alternatives considered" — general-purpose extension
  data bags, rejected for the identical reason).

## UserMode — *Value Object, Session & Messaging*

One recognized user-mode flag *definition* — the `ClientSession`-scoped
counterpart to `ChannelMode` above, not a reuse of that type (research.md
"User mode: `operator`" explains why: no `UserMode` this release needs a
`kind` or a `gates` field, since every flag here is a plain boolean with
one fixed setting rule, not several shapes gating several different
commands the way `ChannelMode` does).

| Field | Type | Notes |
|---|---|---|
| `id` | string | Stable, human-readable identifier (e.g. `operator`, `invisible`), the same role `ChannelMode.id` plays. Unique among every currently-recognized `UserMode` — a separate namespace from `ChannelMode.id`; nothing prevents a `ChannelMode` and a `UserMode` sharing an `id` or, as `operator` does, a `flag`, since a `MODE` command's target (a channel name vs. a nickname) already disambiguates which catalog applies. |
| `flag` | character | The wire-protocol mode letter (`o` for `operator`, `i` for `invisible`). Unique among every currently-recognized `UserMode` — again, independently of `ChannelMode`'s own `flag` uniqueness; `o` is legitimately reused across both catalogs, the way real IRC servers do. |
| `definedBy` | `CORE` or an `Extension` id | Both `operator` and `invisible` are `CORE`-defined, always recognized. An extension-defined flag would only be recognized while that extension is `ENABLED`, mirroring `ChannelMode.definedBy`. |
| `clientSettable` | boolean | Whether a client may set (`+`) this flag on itself directly via `MODE`, with no privilege check. `true` for `invisible` (FR-061) — any registered session may set/clear it freely. `false` for `operator` (FR-034) — only a successful `OPER` may transition it `false`→`true`; a client's own `MODE +o` is rejected regardless of this field's value for *clearing* (see Validation rules below — clearing is always allowed, independent of `clientSettable`). |

**Validation rules**:
- This release populates exactly two `UserMode`s: `id: operator, flag:
  o, definedBy: CORE, clientSettable: false` and `id: invisible, flag:
  i, definedBy: CORE, clientSettable: true` (FR-061). `operator`'s
  membership in a `ClientSession.userModes` set is never independently
  settable — it tracks `ClientSession.administratorPrivilege` exactly,
  added the instant that field becomes `true` (FR-034's `OPER` grant)
  and removed the instant it becomes `false` (including a session's own
  `MODE <self> -o`, which MUST clear `administratorPrivilege` too, not
  merely the flag — research.md "User mode: `operator`"). `invisible`'s
  membership has no such paired field to sync with — it is purely
  `ClientSession.userModes` state, set and cleared directly by the
  session's own `MODE <self> +i`/`-i`.
- `MODE <nickname> ...` (query or set) targeting a nickname other than
  the sender's own current one MUST be rejected outright
  (`502 ERR_USERSDONTMATCH`) — this release has no mechanism for a
  session to query or change a *different* session's user modes, not
  even for an administrator (mirrors `SAMODE`'s self-only scope,
  FR-058). This applies identically to both flags.
- Setting (`+`) a `clientSettable: false` flag (`operator`) from a
  session that does not already hold it MUST be rejected
  (`481 ERR_NOPRIVILEGES`) — setting `operator` this way is equivalent
  to self-granting administrator privilege, which only FR-034's
  credential path may do. Setting (`+`) a `clientSettable: true` flag
  (`invisible`) MUST always succeed, with no privilege check
  (research.md "WHO and invisibility"). *Clearing* (`-`) any flag a
  session already holds is always permitted regardless of
  `clientSettable` — mirroring FR-046's channel-operator
  self-revocation allowance. A session re-asserting a flag it already
  holds, or clearing one it doesn't hold, MUST be treated as a harmless
  no-op, not an error.
- `MODE <self>` with no mode string is a query, answered with
  `221 RPL_UMODEIS` listing currently-set `userModes` — never an error,
  regardless of whether the set is empty.
- A mode letter naming no currently-recognized `UserMode` MUST be
  rejected with `501 ERR_UMODEUNKNOWNFLAG` — the user-mode counterpart to
  `ChannelMode`'s `472 ERR_UNKNOWNMODE` rejection, a different numeral
  because they are different commands (`MODE <nickname>` vs.
  `MODE <channel>`).
- `WHO`'s exact-nickname and mask/no-argument forms (FR-061) MUST
  exclude a session whose `userModes` currently contains `invisible`
  unless the requester's `channelMemberships` (`ClientSession`, above)
  intersects that session's `channelMemberships` at all, or the
  requester's `administratorPrivilege` is `true` — no new field is
  needed for the "shares a channel" check, it's a set intersection
  over data both sessions already have. `WHO`'s channel-scoped form
  MUST NOT apply this exclusion at all — it uses exactly the membership
  visibility `NAMES` (FR-041/FR-047) already defines, so the two
  commands can never disagree about a channel's roster.
- Independently of `invisible`, `WHO`'s mask and no-argument forms MUST
  short-circuit to an empty result (bare `315`, no `352` lines) for a
  non-administrator requester when `ServerConfiguration.whoMaskEnabled`
  is `false` (FR-061) — checked before, and independent of, the
  `invisible` exclusion above; a requester with `administratorPrivilege`
  is exempt from this check entirely (checked first). The channel-name
  and exact-nickname forms are unaffected by this setting.

## SupportedFeatures — *Value Object, Server Extensibility (computed, server-scoped)*

The `RPL_ISUPPORT` token set (FR-055) sent as part of every session's
Registration Completion Burst (FR-051) — not a stored entity, and
**not** computed per session. Every token is either a fixed constant or
derived from server-wide state (the `ChannelMode` catalog, or
`ServerConfiguration`'s configurable length limits, FR-056); nothing
here depends on anything about the particular session receiving it, so
one instance is shared by all of them, the same way `ServerConfiguration`
itself is one shared instance, not something recomputed per connection.
This is a different relationship to its source state than
`UserIdentity.presentedForm` has to *its* — `presentedForm` genuinely
varies per session (a session's own nickname, its own cloak-affected
hostname) and is deliberately never cached; `SupportedFeatures` varies
only with server-wide extension state that changes far less often than
new sessions register, so caching it (recomputed on that state's own
transitions, not on every read) is the correct shape, not merely a
convenient one.

| Token | Derived from |
|---|---|
| `CASEMAPPING` | Fixed: `rfc1459` (FR-052, research.md "IRC casemapping") |
| `CHANTYPES` | Fixed: `#` (`ChannelName`'s grammar, FR-048 — this server has one channel-name prefix) |
| `NICKLEN` | `ServerConfiguration.nicknameMaxLength` (FR-056; default `9`), recomputed whenever `ServerConfiguration` is (re)loaded — not a fixed constant |
| `CHANNELLEN` | `ServerConfiguration.channelNameMaxLength` (FR-056; default `50`), same recomputation trigger as `NICKLEN` |
| `TOPICLEN` | `ServerConfiguration.topicMaxLength` (FR-056; default `390`), same recomputation trigger as `NICKLEN` — newly introduced alongside `NICKLEN`/`CHANNELLEN` (research.md "Configurable protocol length limits"); no equivalent existed before FR-056 |
| `MODES` | `ServerConfiguration.maxModesPerCommand` (FR-064; default `6`), recomputed whenever `ServerConfiguration` is (re)loaded — the same trigger `NICKLEN`/`CHANNELLEN`/`TOPICLEN` already use — not a fixed constant |
| `CHANMODES` | Recomputed from the `ChannelMode` catalog whenever `ExtensionRegistry`'s state changes (FR-011/FR-012 — an `EXTENSION` command or config reload enabling/disabling a mode-contributing extension), not on every registration: every currently-recognized (core plus enabled-extension) flag, grouped into ISUPPORT's four parameter-behavior categories (`A,B,C,D`) by `kind` — `LIST` flags populate `A` (`ban-mask`/`b`, FR-062 — this release's one `A`-group member); `VALUE` flags would populate `B`/`C`, but none exist this release (`ChannelMode` validation rules); `BOOLEAN` flags populate `D` (`moderated`/`members-only`/`private`/`secret`/`invite-only` — `m`,`n`,`p`,`s`,`i`) — this release's value is `b,,,imnps` |
| `PREFIX` | Same recomputation trigger as `CHANMODES`, from the `MEMBER`-kind `ChannelMode` entries and their established prefix characters: `(ov)@+` this release (`operator`→`@`, `voice`→`+`, FR-045/FR-046, contracts/irc-protocol-commands.md "Channel Operations" `@`/`+` convention) — ordered highest-privilege first, the same order `353 RPL_NAMREPLY` already prefixes with |
| `UTF8ONLY` | Fixed: present, no value (FR-054 — this server always enforces it, so the token is unconditional) |

**Validation rules**: `CHANMODES`'s `A` group (`ban-mask`/`b`) and `D`
group (and `B`/`C`, once either is non-empty) MUST list exactly the same
flags `004 RPL_MYINFO`'s channel-mode-letter list already does (FR-051)
— both read the same `ChannelMode` catalog, recomputed on the same
`ExtensionRegistry` state transitions, so they cannot disagree by
construction, not by convention two independent code paths have to
remember to keep in sync. Similarly,
`NICKLEN`/`CHANNELLEN`/`TOPICLEN`/`MODES` MUST always equal the value
actually enforced by
`NickCommandHandler`/`JoinCommandHandler`/`TopicCommandHandler`/`ModeCommandHandler`
at that moment (FR-056, FR-064) — all read the same `ServerConfiguration`
fields, recomputed on the same load/reload transition, so these too
cannot disagree by construction. A new session's registration burst MUST
read the current, already-computed `SupportedFeatures` value, never
trigger its own recomputation — sending `005` to 1,000
concurrently-registering clients (SC-003) MUST NOT mean 1,000 redundant
walks of the same, unchanged `ChannelMode` catalog or re-reads of the
same, unchanged `ServerConfiguration` fields.

## Capability — *Value Object, Capability Negotiation*

A named, independently negotiable IRCv3 protocol enhancement. Identified
by `name` alone within the capability catalog; carries no state of its own
beyond that name and its current availability, both derived from its
owning `CapabilityExtension` — it is not something with its own lifecycle
independent of that extension.

| Field | Type | Notes |
|---|---|---|
| `name` | string | One of `message-tags`, `server-time`, `echo-message` for this release (FR-025). |
| `available` | boolean | Derived from whether the owning `CapabilityExtension` is currently `ENABLED` (FR-007, FR-025). |

**Validation rules**: A capability's `available` state MUST be sourced from
its owning `CapabilityExtension`'s enabled/disabled state at request time
(FR-007's accept/decline response must reflect current, not stale,
availability). This live-check applies at **every** point the capability's
effect is used, not only at `CAP REQ` time: a
`ClientSession.negotiatedCapabilities` entry is not a permanent grant once
negotiated — each outgoing message is formatted by re-checking whether the
corresponding `CapabilityExtension` is currently `ENABLED` (`Extension`,
below), so disabling it stops its effect for already-connected sessions
immediately (SC-005), not only for future negotiations.
`negotiatedCapabilities` records what the client *asked for and was
granted at negotiation time* (so it isn't re-offered or silently
re-added); whether it's still honored on the wire is a separate, live
check.

## Extension — *Entity, Server Extensibility* (base type)

An independently enableable/disableable unit of optional server
functionality, per FR-011. Has identity (`id`) and mutable lifecycle state,
which is why it's an Entity rather than a Value Object. Two role
specializations exist — see research.md "Extension system" for why the
split is a compiler-enforced domain distinction, not just documentation:

- **`CapabilityExtension`**: also provides exactly one `Capability` and is
  therefore visible to clients via `CAP LS`. Lives under
  `jircd-capabilities/`. This release's set: `message-tags`, `server-time`,
  `echo-message`.
- **`ServerExtension`**: no client-negotiable `Capability`; administrator-
  only, a client never learns it exists. Lives under
  `jircd-server-extensions/`. This release's set: `cloak` (FR-031),
  `admin` (FR-032).

Channel moderation and the capability-negotiation mechanism are core,
always-present behavior (FR-035, FR-036) and are deliberately **not**
modeled as an `Extension` at all — there is no `Extension` instance for
them and no `id` an administrator could use to disable them. This applies
to the *mechanism* (the fact that `MODE` and `CAP` exist and work), not to
every individual flag or capability it can carry: core's two `ChannelMode`
flags (`MEMBERS_ONLY`, `MODERATED`) are unconditional for the same reason,
but an *additional* flag MAY come from a `ServerExtension`'s
`contributedChannelModes` — that flag, unlike the two core ones, stops
being recognized while its extension is `DISABLED`, exactly like any other
extension-provided behavior (FR-020).

| Field | Type | Notes |
|---|---|---|
| `id` | string | Stable identifier used in Server Configuration (FR-012). This release's full set: `message-tags`, `server-time`, `echo-message` (`CapabilityExtension`), `cloak`, `admin` (`ServerExtension`) — one Gradle subproject each. |
| `state` | enum: `ENABLED`, `DISABLED`, `FAILED` | `FAILED` = failed to start/errored at runtime without affecting other extensions (FR-020). |
| `providedCapability` | `Capability` name, 0..1 | Present only for `CapabilityExtension`; absent for `ServerExtension` (e.g., cloak, admin). |
| `extensionPoint` | string, 0..1 | Set only for extensions that supply a value or decision core code consumes rather than just adding a capability (e.g., `cloak` claims `hostname-display`). `null` for extensions with no such claim. `connection-admission` (FR-066, research.md "Connection-admission extension point") is a second recognized point — unclaimed by any extension in this release, since the future "G-line"-style connection-blocking extension it's reserved for isn't built yet; `jircd-core` still calls out to it on every accepted connection, always permitting the connection through while nothing claims it, the same default-permissive fallback `hostname-display` has when `cloak` is disabled. |
| `contributedChannelModes` | set of `ChannelMode`, 0..* | `ServerExtension`-only (research.md "Channel/user mode extensibility"). Empty for every extension in this release — no extension contributes a mode yet — but the field exists now so a future one (e.g., a registered-channel flag) is an extension change, not a `Channel`/`ChannelMode` data-model change. Unlike `extensionPoint`, this isn't exclusive single-owner claim: multiple extensions may each contribute different flags, only conflicting if two claim the same `flag` character (`ChannelMode` validation rules). |
| `contributedUserModes` | set of `UserMode`, 0..* | `ServerExtension`-only, the `UserMode` counterpart to `contributedChannelModes` (FR-044, research.md "User mode: `operator`"). Empty for every extension in this release — only `CORE`'s `operator` flag exists — but the field exists now so a future extension-contributed user-mode flag is an extension change, not a `ClientSession`/`UserMode` data-model change. Same non-exclusive-claim behavior as `contributedChannelModes`; conflicts only if two claim the same `flag` character within the `UserMode` namespace (independent of `ChannelMode`'s namespace, `UserMode` validation rules). |

**Validation rules**: A transition to `DISABLED` or back to `ENABLED` MUST
NOT require restarting the server process (FR-011) and MUST take effect for
already-connected clients, not only new connections (SC-005). At most one
`ENABLED` extension MAY claim a given non-null `extensionPoint` at a time;
attempting to enable an extension whose `extensionPoint` is already claimed
by another currently-`ENABLED` extension MUST be rejected as a
configuration error naming both conflicting extension ids (FR-012), not
silently allowed to override the existing claim (research.md "Extension
system" — "Extension-point ownership"). Disabling an extension releases
any `extensionPoint` it held, in the same quiesced-then-release sequence
as any other disable (research.md "Extension system" — "Quiesce before
unload").

**Lifecycle**: `ENABLED` ⇄ `DISABLED` (administrator-driven, either
direction, no restart, quiesced per research.md); `ENABLED` → `FAILED`
(runtime error, isolated per FR-020) — a `FAILED` extension does not
auto-recover; it requires an administrator action (out of scope to specify
the exact recovery UX here).

## ServerConfiguration — *Aggregate Root, Server Extensibility (config) / Administration*

The administrator-controlled settings loaded at startup and re-applied on
configuration reload.

| Field | Type | Notes |
|---|---|---|
| `capabilityStates` | map of `CapabilityExtension` `id` → desired `state` | Config-file section `capabilities` (contracts/server-configuration.md). Source of truth the matching `Extension.state` is reconciled against (FR-011, FR-012). |
| `serverExtensionStates` | map of `ServerExtension` `id` → desired `state` | Config-file section `server-extensions`. Same reconciliation contract as `capabilityStates`, kept as a separate field because the two id spaces are validated against different `Extension` specializations (see Validation rules). |
| `listeners` | list of {port, tlsEnabled, bindAddress?, backlog?, certPath?, keyPath?, keystorePath?, keystorePassword?} | Plaintext and/or TLS listeners (FR-018 — both may coexist). `bindAddress` is an optional literal IPv4/IPv6 address the listener binds instead of the wildcard address (contracts/server-configuration.md). `backlog` is an optional positive integer (1 through 2147483647), defaulting to `1024`; changes require a server restart to apply because reload does not rebind existing sockets (contracts/server-configuration.md). A `tls: true` listener MUST have exactly one certificate form configured — `certPath`+`keyPath` (PEM certificate/chain and private key) or `keystorePath`(+`keystorePassword`, PKCS12) — validated and loaded at startup, never both, never neither (004-fix-tls-certificate FR-001 through FR-006); a `tls: false` listener ignores any of the four fields if present. No certificate is ever generated automatically. |
| `rateLimit` | {bucketSize, refillRate} | FR-016; see research.md "Rate limiting". |
| `maxConnections` | positive integer | YAML `maxConnections`, defaults to `10000`, range `1`-`2147483647`. Total live registered/unregistered connections shared by every plaintext/TLS listener. Reload gates new accepts only. |
| `maxConnectionsPerIp` | positive integer | YAML `maxConnectionsPerIp`, defaults to `20`, range `1`-`2147483647`. Default threshold per client bucket: IPv4 /32 or the configured IPv6 prefix. Reload gates new accepts only; existing counts remain. |
| `ipv6ConnectionLimitPrefix` | integer, `1`-`128` | Same YAML key; defaults to `64`. IPv6 counting prefix (`128` means per address). IPv4-mapped IPv6 clients normalize to IPv4 before matching and bucketing. Reload affects new reservations; each existing connection releases its original bucket key. |
| `connectionLimitOverrides` | list of {cidr, maxConnectionsPerIp} | Same YAML key; defaults to `[]`. Each CIDR must be an unscoped/unbracketed IP literal with zero host bits and a valid IPv4 /0-/32 or IPv6 /0-/128 prefix; each limit is an integer in `1`-`2147483647`. Duplicate normalized ranges are invalid, including equivalent IPv6 spellings and mapped IPv6 /96-/128 aliases of IPv4 ranges. Most specific match against the client address wins regardless of order; its threshold applies to the client bucket, not the entire range. A large limit is a per-bucket exemption; the global limit still applies. Reload replaces matching policy for new accepts only. |
| `registrationTimeoutSeconds` | positive integer | YAML `registrationTimeoutSeconds`, defaults to `60`, range `1`-`3600`. Seconds from accept within which a connection must reach `REGISTERED` (including `CAP END` when negotiation was started); otherwise it receives `ERROR :Registration timeout` and is closed. `PING`/`PONG` does not extend it. Enforced by `LivenessMonitor`; reload applies to new connections only. |
| `administratorCredentials` | list of {username, hashedPassword} | Verified by FR-034's in-band privilege command; hashed per research.md "Administrator credential storage" — never stored or logged in plain text. |
| `serverName` | string, 0..1 | FR-050. The source/prefix on every server-originated message (numeric replies, `RPL_WELCOME`, etc.) — the server-side counterpart to a client's `nickname!ident@hostname` (FR-030). Absent (not set by the administrator) is valid; `jircd-server` MUST then fall back to the deployment host's network hostname, appending a fixed synthetic suffix if that hostname itself has no `.` (research.md "Server identity"), never an empty prefix. MUST contain at least one `.` in either case — nicknames (FR-002's grammar) never can, so this is what keeps a server-originated prefix unambiguous from a client one when a message has no `!`/`@` component. |
| `serverVersion` | string | FR-051. Not administrator-configurable — sourced at startup from a Gradle-generated `net/jircd/server/version.properties` classpath resource, itself fed by the root build's `project.version` (research.md "Server identity"), included in the registration-completion burst (`RPL_YOURHOST`/`RPL_MYINFO`) alongside `serverName`. |
| `nicknameMaxLength` | positive integer, 0..1 | FR-056. Optional; defaults to `9` if unset. Enforced by `NickCommandHandler`'s grammar check (`432 ERR_ERRONEUSNICKNAME`) and advertised as `NICKLEN` (`SupportedFeatures`). MUST NOT exceed `400` (research.md "Configurable protocol length limits"). |
| `channelNameMaxLength` | positive integer, 0..1 | FR-056. Optional; defaults to `50` if unset (includes the leading `#`). Enforced by `JoinCommandHandler`'s grammar check (`476 ERR_BADCHANMASK`) and advertised as `CHANNELLEN` (`SupportedFeatures`). MUST NOT exceed `400`. |
| `topicMaxLength` | positive integer, 0..1 | FR-056. Optional; defaults to `390` if unset. Enforced by `TopicCommandHandler` on a `TOPIC`-set attempt (`417 ERR_INPUTTOOLONG`, not `421` — reuses FR-049's length-violation numeric) and advertised as `TOPICLEN` (`SupportedFeatures`). MUST NOT exceed `400`. |
| `whoMaskEnabled` | boolean, 0..1 | FR-061. Optional; defaults to `true` if unset. Gates `WHO`'s wildcard-mask and no-argument forms for non-administrator sessions only — `false` makes both return a bare `315 RPL_ENDOFWHO` (no `352` lines), indistinguishable from a real zero-match search. An administrator's `WHO` is never affected, checked before this setting (research.md "WHO and invisibility"). The channel-name and exact-nickname forms are never affected by it either way. |
| `maxModesPerCommand` | positive integer, 0..1 | FR-064. Optional; defaults to `6` if unset. The maximum number of parameter-consuming channel-mode flags (`MEMBER`/`LIST`-kind) a single `MODE` command applies — flags beyond it within the same command are silently not applied (no error; the `MODE` echo reflects only what was applied). Advertised as `MODES` (`SupportedFeatures`). MUST NOT exceed `20` (research.md "MODE command grouping"). |
| `operFailureThreshold` | integer, `1`–`20` | Default `5`. Maximum failures per source bucket across connections and usernames within a window; applies immediately on reload. |
| `operLockoutSeconds` | integer, `1`–`86400` | Default `300`. Fixed failure/lockout window starting at first failure. Reload applies to new windows only. |
| `operRequireTls` | boolean | Default `false`. Plaintext OPER returns `491 ERR_NOOPERHOST` without credential verification. Reload applies to existing connections. |

Admission settings are validated together at startup and `REHASH`/`SIGHUP`; invalid
integers, out-of-range prefixes, malformed override entries, host bits, or duplicate
ranges reject the complete configuration with an indexed field error. The compact
`ConnectionLimits` value also enforces positive global/per-bucket limits, IPv6 prefix
`1`-`128`, and a non-null immutable override list for direct programmatic construction.
A successful reload affects only new accepts and never disconnects existing clients;
a failed reload leaves all settings intact. Every admitted connection retains its
bucket key until teardown. Changing the IPv6 prefix does not transfer its reservation
to a new bucket; old and new bucket keys coexist during the transition.

**Validation rules**: An invalid configuration (unknown extension id,
conflicting listener ports, malformed rate-limit values, a
`nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` that isn't a
positive integer or exceeds `400` (FR-056), a `maxModesPerCommand`
that isn't a positive integer or exceeds `20` (FR-064), or an
`operFailureThreshold` that isn't a positive integer or exceeds `20`
(FR-034), or a `serverName`/`nicknameMaxLength`/`channelNameMaxLength`
combination that cannot fit a complete `353 RPL_NAMREPLY` entry and `366
RPL_ENDOFNAMES` within the 512-byte wire limit — with `S` the UTF-8 byte
length of the resolved `serverName` (explicit or defaulted),
`S + 2*nicknameMaxLength + channelNameMaxLength + 14 > 512` for `353` or
`S + nicknameMaxLength + channelNameMaxLength + 29 > 512` for `366` — each
field may be individually within range and the set still rejected; the
message reports both totals and the remaining channel byte budget) MUST be rejected
with a specific, actionable error identifying the problem field (FR-012,
SC-008) rather than falling back to a partially-applied state. An id MUST
appear in the field matching its actual kind: a `CapabilityExtension` id
listed in `serverExtensionStates`, or a `ServerExtension` id listed in
`capabilityStates`, is itself an invalid-configuration case (a
section/kind mismatch, contracts/server-configuration.md), rejected the
same way — not silently accepted into the wrong field. The in-band
`EXTENSION` administrative command (FR-032) addresses `Extension.id`
directly and MAY resolve to either field without the caller having to
know which one; only the configuration *file* has two sections.
`serverVersion` MUST NOT appear in the configuration file schema at all
— unlike every other field here, it is not administrator input, so there
is no "invalid value" case for it to participate in load-time
validation. An administrator-supplied `serverName` containing no `.`
MUST be rejected at load time with the same specific-error treatment
every other invalid configuration value gets (FR-012) — it is one of
the values this validation set covers, not exempt from it the way
`serverVersion` is.

## Entity Relationships

```text
ClientSession *---1 UserIdentity      (nickname, scoped to the session)
ClientSession *---* Channel           (via channelMemberships / members)
Channel        1---* ClientSession    (via operators, subset of members)
Channel        1---* ClientSession    (via voiced, subset of members, independent of operators)
Channel        *---* ChannelMode      (activeModes)
ServerExtension 0---* ChannelMode     (optionally contributed; 0 in this release)
ClientSession  *---* UserMode         (userModes; tracks administratorPrivilege
                                        exactly, FR-044)
ServerExtension 0---* UserMode        (optionally contributed; 0 in this release)
ExtensionRegistry 1---1 SupportedFeatures (server-scoped, one shared instance;
                                            CHANMODES/PREFIX recomputed on
                                            ExtensionRegistry state changes, FR-055)
ServerConfiguration 1---1 SupportedFeatures (NICKLEN/CHANNELLEN/TOPICLEN
                                            recomputed on ServerConfiguration
                                            load/reload, FR-056 — every
                                            ClientSession's burst reads this same
                                            shared instance, never computes its own)
CapabilityExtension 1---1 Capability  (providedCapability)
ClientSession  *---* Capability       (negotiatedCapabilities)
ServerConfiguration 1---* CapabilityExtension (capabilityStates)
ServerConfiguration 1---* ServerExtension     (serverExtensionStates)
ServerConfiguration 1---* administratorCredentials (FR-034)
ClientSession   1---1 realHostname    (always on core; FR-030/031's display
                                        value is computed, not a separate entity)
ClientSession   1---* OutboundMessage (outboundQueue; one shared instance may
                                        appear in many recipients' queues at once)
```
