# Phase 0 Research: irctest Conformance Fixes

No new dependencies, no new module, no architectural change — every decision here is a
small, targeted correction inside an existing handler, reusing patterns already established
in `001-ircv3-server`/`002-extended-irc-commands`.

## QUIT sends ERROR before closing (FR-001)

**Decision**: `QuitCommandHandler` calls `DisconnectCleanup.disconnectWithError(session, reason,
reason)`, which claims CLOSING first and queues the `ERROR :<reason>` line to the quitting client's
own writer as the final line of that claim (the reason cut to fit one wire line), then runs the
shared cleanup. `LivenessMonitor.timeOut()` and `KillCommandHandler` use the same claim-first
helper (#151: once a client has read its `ERROR` it no longer counts for LUSERS). This replaced the
original order, which queued `ERROR` first and then ran `cleanup(session, reason)`.

**Rationale**: `DisconnectCleanup.cleanup()` closes the session's writer as its own last
step; enqueuing `ERROR` before calling it guarantees the line is queued ahead of that close
(the same poison-pill drain-before-stop guarantee `SessionWriter.close()` already provides,
fixed this session for `KILL`'s self-target case). No new mechanism — this is applying an
already-fixed, already-tested pattern to the one disconnect path that never adopted it.

**Alternatives considered**: Sending `ERROR` from within `DisconnectCleanup.cleanup()`
itself, once, for every cause — rejected: `KILL` and the keep-alive timeout already send
their own cause-specific `ERROR` text (the abuse reason, "Ping timeout") before calling
`cleanup()`; centralizing it there would require passing that text through an extra
parameter for no benefit over each caller just sending its own line first, which is the
existing, working convention.

## Empty USER realname rejected (FR-002)

**Decision**: `UserCommandHandler` checks `realname.isEmpty()` after extracting it from
`message.params().get(3)`, replying `461 ERR_NEEDMOREPARAMS "USER" "Not enough parameters"`
(the exact reply already used just above it for a `USER` with too few parameters outright)
and returning without calling `session.setIdent`/`setRealname`/`registrationCompletion.tryComplete`.

**Rationale**: An empty trailing parameter is syntactically present but semantically equivalent
to "not given" for this field — modern-irc's own guidance (referenced in spec.md) treats it
as a `461` case, and reusing the exact same numeric/text this handler already sends for a
different `USER` malformation keeps the error surface consistent rather than inventing a new
one.

## NAMES/JOIN's 353 visibility symbol reflects actual mode (FR-003)

**Decision**: `JoinCommandHandler.sendNamesReply` (the single shared method both `JOIN` and
`NAMES` already call) computes the symbol from `channel.activeModes()`: `@` if it contains
`CoreChannelModes.SECRET`, `*` if it contains `CoreChannelModes.PRIVATE`, `=` otherwise —
replacing the hardcoded `"="` literal currently passed to `RPL_NAMREPLY`.

**Rationale**: `private`/`secret` are already mutually exclusive by validation rule
(`001-ircv3-server` data-model.md `Channel`), so the three cases are exhaustive and
non-overlapping; this is a one-line change at the single call site both commands share, no
duplication risk.

## LUSERS reply text matches the conventional shape (FR-004)

**Decision**: `LusersCommandHandler` reports the real invisible-user count (a filter over
`nicknameRegistry.all()` by `UserMode.INVISIBLE`, state already tracked per session since
`001-ircv3-server` FR-044/FR-061 — no new tracking) and a fixed server count of `1` (this
server is never more than zero hops from itself, the same non-federated assumption
`WHO`'s hopcount field already documents), producing:
`"There are <clients> users and <invisible> invisible on 1 servers"` for `251
RPL_LUSERCLIENT`, `254 RPL_LUSERCHANNELS` unchanged.

**Rationale**: The conventional sentence shape is what general-purpose IRC client-tooling
parsers expect (SC-004); computing a real invisible count instead of a placeholder costs
one extra stream filter over data this server already maintains, and reporting a fake `0`
when invisible users may actually be present would be actively misleading rather than
merely terse.

**Alternatives considered**: A literal placeholder `"0 invisible"` regardless of actual
state — rejected once it became clear the real count was already one filter away; no reason
to report a knowingly-wrong number when the correct one is free.

## WHOWAS bare-command numeric (FR-005)

**Decision**: `WhowasCommandHandler`'s no-argument case replies `431
ERR_NONICKNAMEGIVEN "No nickname given"` — the exact reply shape (single trailing param, no
target-name param) `NickCommandHandler` already uses for bare `NICK`, rather than the
generic `461 ERR_NEEDMOREPARAMS`.

**Rationale**: Matches an already-established, already-tested convention in this codebase
for the identical situation (a command that specifically requires a nickname argument,
given none at all) — no new formatting decision needed.

## WHO/WHOIS server-name field (FR-006/FR-007)

**Decision**: Two related, previously-undiscovered-in-full-detail changes fall out of the
Clarifications decision to restore this field:

1. `WhoCommandHandler.sendWhoReply` inserts `serverName.get()` as a new positional parameter
   between the hostname and nickname fields of `352 RPL_WHOREPLY`, matching RFC 2812's
   `<channel> <user> <host> <server> <nick> ...` field order exactly.
2. `WhoisCommandHandler` sends a new `312 RPL_WHOISSERVER <target-nick> <server-name>
   :<server info>` reply immediately after `311 RPL_WHOISUSER` — RFC 2812 defines the
   server-name field for `WHOIS` as its own separate numeric, not an additional parameter on
   `311` itself (`311`'s own field layout, `<nick> <user> <host> * :<real name>`, has no
   server-name slot to add one to). `<server info>` reuses this server's own descriptive
   string (`"jircd IRC server"`, already used by `VERSION`'s `351` reply,
   `002-extended-irc-commands` FR-001) as its trailing text.

**Discovered during planning, in scope of the same Clarifications decision**: `WHOIS`
currently only ever reads `message.params().getFirst()` as the target nickname, so RFC
2812's optional `WHOIS <target server> <nickname>` two-parameter form (irctest exercises
this directly) is misparsed — the server-name argument gets treated as the nickname and
fails with `401 ERR_NOSUCHNICK`. Fixed by reading the *last* parameter as the nickname
regardless of whether one or two parameters were given (the leading server-name parameter,
if present, is accepted but not used to route anywhere, since this server has no
federation to route to — `001-ircv3-server` FR-021). This isn't a new design decision: it's
the correct implementation of the same "restore the server-name field" choice already made,
just discovered mid-implementation rather than during specification.

**Rationale**: Both reuse this server's own already-known name — no new state, no new
config, no ambiguity about what value to report (there being only one server).
