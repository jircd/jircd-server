# Contract: Extended Client-to-Server Commands

Extends `specs/001-ircv3-server/contracts/irc-protocol-commands.md` — same two-layer split
(wire-protocol recognition in `jircd-protocol`, server behavior in `jircd-core`/extensions),
same numeric-reply conventions, same case-insensitive command/target matching. This document
covers only the seven commands `002-extended-irc-commands/spec.md` adds; every other command
and every general rule (line-length limits, UTF-8 enforcement, hostmask presentation) from
`001-ircv3-server`'s contract applies unchanged and is not repeated here.

This document does not modify `001-ircv3-server/contracts/irc-protocol-commands.md`'s "Full
Command Catalog" table — that file belongs to a closed feature and is not edited by this one
(the same append-only discipline `/speckit-converge` enforces on `tasks.md` applies here by
convention: a shipped feature's own contract is a historical record of what that release
implemented). The seven commands below move from that table's "Recognized only" status to
implemented, for `jircd-core`'s purposes, as of this feature.

Numeric reply references not defined below point to
[`001-ircv3-server/contracts/irc-numeric-replies.md`](../../001-ircv3-server/contracts/irc-numeric-replies.md),
whose Full Numeric Catalog already reserves every numeric this feature claims (`251`-`255`,
`301`, `305`, `306`, `314`, `351`, `369`, `391`, `406`) as previously-unused.

## Server Information Queries

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `VERSION` | C→S | `REGISTERED` session | Returns server name/version, then a fresh `RPL_ISUPPORT` burst (Clarifications, 2026-08-19) | `351 RPL_VERSION`, then one or more `005 RPL_ISUPPORT` lines — identical rendering to the registration completion burst's own `005` (`001-ircv3-server` "Registration Completion Burst"), via the shared helper research.md "VERSION + ISUPPORT reuse" describes |
| `TIME` | C→S | `REGISTERED` session | Returns the server's current local time | `391 RPL_TIME` |
| `LUSERS` | C→S | `REGISTERED` session | Returns current connected-client, connected-operator, and active-channel counts (server-wide totals only — spec.md Assumptions) | `251 RPL_LUSERCLIENT` (`"There are <clients> users and <invisible> invisible on 1 servers"` — the conventional, widely-parsed text shape, 003-irctest-conformance-fixes FR-004), `252 RPL_LUSEROP` (connected-operator count, 006-complete-core-protocol FR-011), `254 RPL_LUSERCHANNELS` (channel count), `253 RPL_LUSERUNKNOWN` (only when unregistered connections exist), then `255 RPL_LUSERME` (`"I have <clients> clients and 1 servers"`, always sent, 006-complete-core-protocol FR-012), `265 RPL_LOCALUSERS` and `266 RPL_GLOBALUSERS` |

**Contract notes**:
- `LUSERS` sends `253 RPL_LUSERUNKNOWN` (`<nick> <n> :unknown connection(s)`) when `n > 0`, where
  `n` counts accepted connections not yet `REGISTERED` (issue #76); it is omitted when there are
  none. A connection stops counting the moment it starts closing. `265 RPL_LOCALUSERS` and `266
  RPL_GLOBALUSERS` follow `255` as `<nick> <u> <m> :Current local|global users <u>, max <m>`, `u`
  being the registered-user count and `m` its high-water mark; the reply order is 251, 252, 253,
  254, 255, 265, 266. `252`/`255` were once reserved for the same reason `253` originally was, but
  that blocking reasoning did not hold for them — `UserMode.OPERATOR` is already tracked
  per-session (used throughout `WHOIS`/`WHO`/`MODE +o`), and `RPL_LUSERME`'s client count needs no
  new concept at all.
- `252`'s operator count is a real, live filter over connected sessions holding the `operator`
  user mode, the identical filtering shape `251`'s `<invisible>` count already uses against
  `UserMode.INVISIBLE`.
- `251`'s `<invisible>` count is a real, live filter over connected sessions holding the
  `invisible` user mode (`001-ircv3-server` `UserMode.INVISIBLE`, FR-044) — not a placeholder
  zero (003-irctest-conformance-fixes FR-004, research.md "LUSERS reply text matches the
  conventional shape": reporting a knowingly-wrong `0` when invisible users may actually be
  present would be actively misleading). `<servers>` is a fixed `1` — this server is never
  more than zero hops from itself, the same non-federated assumption `WHO`'s hopcount field
  documents (`001-ircv3-server` contracts/irc-protocol-commands.md).
- `VERSION`'s `RPL_ISUPPORT` burst is byte-for-byte the same content `002` (registration)
  would send if the client re-registered right now — not a filtered or summarized subset.

## Presence (Away Status)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `AWAY :<reason>` | C→S | `REGISTERED` session; `<reason>` non-empty | Sets `ClientSession.awayReason` (replacing any existing value, FR-006) | `306 RPL_NOWAWAY` |
| `AWAY` (no parameter) / `AWAY :` (empty trailing argument) | C→S | `REGISTERED` session | Clears `ClientSession.awayReason` — an empty trailing argument is treated the same as an entirely absent one (005-fix-batch-conformance FR-022; previously set an empty-string away reason instead of clearing) | `305 RPL_UNAWAY` |

**Contract notes**:
- `PRIVMSG`/`NOTICE` to a target with `awayReason` present MUST additionally send
  `301 RPL_AWAY <target-nick> :<reason>` to the sender, alongside — not instead of —
  normal delivery (FR-007); `NOTICE`'s "MUST NOT trigger automated replies" rule
  (`001-ircv3-server` contract) does not apply here, since `301` is a direct reply to the
  sender's own command, not a message routed to the away target.
- `WHOIS` output includes `301 RPL_AWAY` (using the same numeric as the messaging-time
  notice above) immediately after `311 RPL_WHOISUSER`, only when the target is currently
  away — omitted entirely otherwise, the same "no line sent" convention `313
  RPL_WHOISOPERATOR` already uses for a non-operator target
  (`001-ircv3-server` "User Queries").
- `WHO`'s `<status>` field (`001-ircv3-server` "User Queries" contract notes) now sends `G`
  ("gone") instead of `H` ("here") for an away match — the letter that contract's own note
  already reserved for exactly this. Operator (`*`) and, for the channel-scoped form,
  `@`/`+` suffixes still append after `G`/`H` unchanged.

## Administration (extends `001-ircv3-server` "Administration")

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `KILL <nickname> [:reason]` | C→S | Sender holds administrator privilege | Disconnects the target session through the same cleanup path any other disconnection uses (`001-ircv3-server` FR-017), reason defaulting to a fixed server string if none given | Confirmation notice to the sender on success; target receives `ERROR :<reason>` before the socket closes, the same final-line convention every other server-initiated disconnect uses (`001-ircv3-server` "Connection Keep-Alive"); `481 ERR_NOPRIVILEGES` if sender lacks administrator privilege; `401 ERR_NOSUCHNICK` if `<nickname>` isn't connected |

**Contract notes**:
- `KILL` joins the existing six administration commands
  (`001-ircv3-server` "Administration" contract notes: "these six commands are the
  FR-032/FR-057/FR-058 minimum; additional administrative commands MAY be added by future
  extensions without changing this contract") — this is that extension, addressed to
  `jircd-server-extensions/admin` alongside `OPER`/`EXTENSION`/`WHOHOST`/`REHASH`/
  `SAJOIN`/`SAMODE`.
- The disconnect notification channel members see (via the FR-017 cleanup path's existing
  broadcast) MUST carry a `KILL`-specific reason distinguishable from a voluntary `QUIT` or
  a keep-alive timeout (research.md "KILL disconnect path reuse") — the same
  three-way distinguishability `001-ircv3-server`'s keep-alive contract note already
  establishes between `QUIT` and a timeout, now extended to a third cause.
- An administrator MAY `KILL` their own nickname; this is not treated as a distinct case
  from killing any other connected client (spec.md Edge Cases).

## Last-Known Identity Lookup

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `WHOWAS [nickname] [count]` | C→S | `REGISTERED` session | Returns the most recently retained `WhowasEntry` values for `<nickname>`, most recent first, if any (data-model.md `WhowasHistory`) — up to `[count]` of them when `[count]` is a positive number; every retained one when `[count]` is omitted, zero, negative, or non-numeric (006-complete-core-protocol FR-014/FR-015) | `314 RPL_WHOWASUSER` once per returned entry, then `369 RPL_ENDOFWHOWAS` on a match; `406 ERR_WASNOSUCHNICK`, then `369 RPL_ENDOFWHOWAS`, if no history exists for `<nickname>`; `431 ERR_NONICKNAMEGIVEN` if `<nickname>` is omitted entirely (003-irctest-conformance-fixes FR-005 — the same reply shape `NICK` already uses for a bare `NICK`, replacing the previously-generic `461 ERR_NEEDMOREPARAMS`) |

**Contract notes**:
- `369 RPL_ENDOFWHOWAS` always closes the response, on both the success and no-history
  paths — the same "always send a defined completion signal" pattern `LIST`'s `323
  RPL_LISTEND` and `WHO`'s `315 RPL_ENDOFWHO` already use.
- A bare `WHOWAS` does NOT also send `369` — `431` is the sole reply, matching `NICK`'s own
  bare-argument convention (`001-ircv3-server` contracts/irc-protocol-commands.md), not the
  match/no-match two-reply shape above.
- `406 ERR_WASNOSUCHNICK` is distinct from `401 ERR_NOSUCHNICK` (used by `WHOIS`/`KILL`
  above) specifically because the two questions are different: `401` means "not currently
  connected," `406` means "no retained history at all" — a nickname can fail one, the other,
  or both, and the distinction is meaningful to a client deciding whether to keep trying
  later.
- `WHOWAS` accepts an optional count parameter (006-complete-core-protocol FR-014/FR-015) — a
  positive number returns up to that many entries; an omitted count, zero, a negative number, or a
  non-numeric value all mean "do a full search" (every retained entry for that nickname), per
  RFC1459 §4.5.3/RFC2812 §3.6.3's own rule for a non-positive count (confirmed against irctest's
  own non-deprecated `testWhowasMultiple`, which omits the count entirely and still expects every
  retained entry), extended leniently to a malformed
  one too. No server parameter is accepted (this server has no server-to-server interface,
  `001-ircv3-server` FR-021).
- `314 RPL_WHOWASUSER`'s hostname field follows the same FR-038 resolution `WHOIS`/`WHO`
  already use — the real hostname/IP for an administrator requester, the presented
  (cloaked, if `cloak` was active) value snapshotted at disconnect time for everyone else.
  `WHOWAS` is not admin-gated like `WHOHOST`, so it MUST NOT default to always showing the
  real value the way `WHOHOST` deliberately does — that would leak a disconnected client's
  real hostname/IP to any registered user, defeating `cloak` for anyone who happens to
  disconnect.

## Tag-Only Messages

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `TAGMSG <target>` | C→S | `REGISTERED` session; sender has negotiated `message-tags`; at least one tag present (FR-023); same targeting/gating preconditions as `PRIVMSG`/`NOTICE` (`001-ircv3-server` "Channel Operations") | Delivers the tags (no text body) to every recipient that has itself negotiated `message-tags`; silently drops the message for any recipient that hasn't | No reply to the sender beyond what `PRIVMSG`/`NOTICE` would send for the identical failure case (`442`/`404`/`401`-class errors, per FR-022); no numeric reply on success (matches `PRIVMSG`/`NOTICE`'s own silent-success convention, echo-message aside) |
| `TAGMSG` with no tags | C→S | — | Rejected | `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection, the same convention `001-ircv3-server` FR-015 already uses for other structurally-invalid messages |

**Contract notes**:
- A sender with `echo-message` negotiated receives their own `TAGMSG` back, the same
  `echo-message` behavior `PRIVMSG`/`NOTICE` already provide (`001-ircv3-server` FR-025) —
  `TAGMSG` is a message for this capability's purposes, not a separate category.
- `msgid` (`001-ircv3-server` FR-059) is attached to a `TAGMSG` the same way it's attached to
  any other message-tags-negotiated delivery — no special case.
