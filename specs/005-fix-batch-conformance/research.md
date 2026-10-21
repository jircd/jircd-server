# Phase 0 Research: Fix Batch of Conformance Bugs

No new dependency, no new module — every decision here is a small, targeted correction inside
an existing handler, reusing patterns already established in `001`/`002`/`003`/`004`. Each
finding was already root-caused against jircd-core's actual current source before this feature
was specified (not guessed at spec time), so this phase records the *fix* decision for each,
not fresh investigation.

## Story 1 — NICK broadcast (FR-001, FR-002)

**Decision**: `NickCommandHandler.handle()` — after the existing `nicknameRegistry.claim`/
`release`/`session.setNickname(requested)` sequence — builds one `NICK <requested>` message
using the *old* hostmask (`PresentedIdentity.presentedForm`, computed before `setNickname`
mutates it) as the prefix, and enqueues it to the session's own writer plus every member of
every channel in `session.channelMemberships()`. A session with no channel memberships still
gets its own copy (FR-001) via the same enqueue.

**Rationale**: This is the exact fan-out shape `JoinCommandHandler`'s `JOIN` notification and
`KickCommandHandler`'s `KICK` notification already use (loop over `channel.members()`,
`enqueueRaw`) — reused verbatim rather than inventing a new broadcast helper. The hostmask must
be captured *before* `setNickname` runs, since the prefix on a `NICK` message is conventionally
the identity the recipients already know the sender by (the old nickname), not the new one.

**Alternatives considered**: A dedicated `NickBroadcast` helper class — rejected; the fan-out is
a five-line loop identical in shape to two other handlers' own inline loops, not complex enough
to justify extraction into a new shared abstraction this codebase doesn't otherwise have for
`JOIN`/`KICK` either.

## Story 2 — Direct-message delivery guarantees (FR-003, FR-004)

**Decision (FR-003)**: `MessageCommandHandler.handle()`'s fan-out loop (`for (ClientSession
recipient : recipients)`) currently skips `session` unless it's already a member of the
resolved `recipients` set — structurally true for channels, structurally false for a DM (whose
`recipients` set is `Set.of(target)`, never containing the sender). Fix: when `echoToSender` is
true and the target isn't a channel, separately enqueue the same `outbound` to `session` after
the main loop (which still only iterates the real recipient).

**Decision (FR-004)**: Add `|| body.isEmpty()` to the existing `params().size() < 2` guard at
the top of `handle()`, replying `412 ERR_NOTEXTTOSEND` (not currently used anywhere in this
codebase, but already defined in the full numeric catalog per `001-ircv3-server`'s "wire-protocol
recognition MUST represent the full RFC set").

**Rationale**: Both fixes stay inside `MessageCommandHandler`'s existing structure — FR-003 adds
one conditional enqueue after the existing loop rather than restructuring the loop itself;
FR-004 extends an existing parameter-count guard to also check content, the same
"empty-string-as-effectively-absent" pattern `004`'s own `USER`-realname fix already used.

## Story 3 — Connection and capability-negotiation precision (FR-005 through FR-012)

**Decision (FR-005/FR-006)**: `PingPongCommandHandler.ping()` changes its `PONG` reply from
`List.of(token)` to `List.of(serverName.get(), token)`. Separately, when
`message.params().isEmpty()`, reply `409 ERR_NOORIGIN "No origin specified"` (defined in the
full catalog, unused today) instead of substituting `session.connectionId()` as a fabricated
token.

**Decision (FR-007)**: `CapCommandHandler`'s `case LS, LIST -> handleLs(session);` splits into
two branches — `LS` keeps calling `handleLs` (the full offered list); `LIST` sends a new reply
built from `session.negotiatedCapabilities()` (already tracked per-session by
`CapabilityNegotiator.request()`, just never read back out for `LIST` specifically) via the same
`CapabilityNegotiationGrammar` reply-building path `handleLs` already uses.

**Decision (FR-008)**: `CapCommandHandler.handle()`'s `if (subcommand == null)` branch changes
its reply from `421 ERR_UNKNOWNCOMMAND` to `410 ERR_INVALIDCAPCMD` (defined, unused today).

**Decision (FR-009)**: `CapabilityNegotiator.NegotiationResult`'s `accepted`/`declined` fields
change from `Set<String>` to `List<String>`, built by iterating `requested` once and appending
to the appropriate list in order (duplicates included) — `session.negotiatedCapabilities()`
(a `Set`, used for actual state tracking elsewhere) is unaffected and keeps deduplicating
correctly; only the *reply-echo* path needs the raw list preserved.

**Decision (FR-010)**: Two-part fix, both required — (1) `MessageCommandHandler.handle()`
switches from `OutboundMessage.now(presentedForm, commandName, target, body)` (the 4-arg
overload, which defaults `clientTags` to `Map.of()`) to the 5-arg overload, passing
`message.tags()` through; (2) `CapabilityTagRenderer.render()` — which today only merges each
enabled `CapabilityExtension`'s own `contributeTags(message)` output — additionally seeds its
`tags` map with `message.clientTags()` *before* the capability loop, so a capability-contributed
tag (`msgid`, reserved) can still take precedence on key collision, but a client's own tag
otherwise survives to the wire.

**Decision (FR-011)**: `ConnectionHandler.processLine()`'s single `lineBytes.length + 2 >
MAX_LINE_LENGTH_BYTES` check (against the combined 512+4096 constant) splits into two
independent checks against the raw line's already-delimited tag section (`@...` up to the first
unescaped space, capped at 4096 bytes) and the remaining command+params section (capped at
512 bytes) — both still checked before the line is decoded to a `String`, preserving this
method's existing "validate raw bytes first" structure.

**Decision (FR-012)**: The existing invalid-UTF8 branch in `processLine()`
(`!Utf8Validator.isValidUtf8(lineBytes)`) changes from "reply `421` and return, connection stays
open" to enqueuing `ERROR :Malformed message (invalid UTF-8)` and calling
`disconnectCleanup.cleanup(...)` — the same enqueue-then-cleanup shape `QuitCommandHandler`,
`KillCommandHandler`, and `LivenessMonitor.timeOut()` already use for a definitive,
client-visible connection end, rather than leaving the client to guess whether to retry.

**Rationale (all of Story 3)**: Every fix reuses an already-defined numeric (`409`/`410`/`412`)
already reserved in the full catalog per `001-ircv3-server`'s completeness requirement, or an
already-established enqueue-then-cleanup/fan-out shape from an earlier feature — none of these
invent a new reply code or a new connection-termination mechanism.

**Alternatives considered (FR-012)**: Keeping the connection open and relying on the client to
resend — rejected; this is exactly the currently-observed failure mode (irctest's client sits
until its own timeout), and every other malformed/fatal pre-registration condition in this
codebase already resolves definitively rather than leaving the socket in limbo.

## Story 4 — Channel-membership grammar completeness (FR-013 through FR-017)

**Decision (FR-013)**: `JoinCommandHandler.handle()` splits `message.params().getFirst()` on
`,` for channel names and (if present) `message.params().get(1)` on `,` for keys, then loops
the existing single-channel logic (grammar validation → `getOrCreate` → gates → membership →
notification → `sendNamesReply`) once per named channel, pairing each channel with its
positional key if one exists (fewer keys than channels leaves the remainder keyless, per
RFC1459 §4.2.1). A channel that fails its own validation/gate check is skipped with its own
existing error reply; other channels in the same command are still processed (no
all-or-nothing rollback, consistent with `MODE`'s own already-documented non-atomic
left-to-right application).

**Decision (FR-014)**: `JoinCommandHandler.handle()`, after the existing `JOIN` notification
fan-out and before `sendNamesReply`, checks `channel.topic()` and — if present — sends
`332 RPL_TOPIC`/`333 RPL_TOPICWHOTIME` to the joining session only, the same two-numeric pair
`TopicCommandHandler`'s own query path already sends.

**Decision (FR-015)**: `KickCommandHandler.handle()`'s `reason = params.size() > 2 ?
params.get(2) : null` changes its `: null` branch to `: session.nickname()` — the kicking
operator's own current nickname becomes the default comment instead of omitting the parameter.

**Decision (FR-016)**: `ModeCommandHandler.handle()`'s bare-ban-list-query condition
(`modeStringArg.equals("b")`) becomes `modeStringArg.equals("b") ||
modeStringArg.equals("+b")` — both accepted RFC forms now reach `sendBanList`.

**Decision (FR-017)**: `ModeCommandHandler` gains a `NicknameRegistry` constructor parameter
(Technical Context). In `applyChanges`'s `MEMBER`-kind branch, before the existing
`channel.findMember(rawParam)` check, look up `rawParam` in `nicknameRegistry` first — if it
isn't connected at all, reply `401 ERR_NOSUCHNICK` (the same reply `WHOIS`/`KILL` already send
for this exact condition) instead of falling through to `441 ERR_USERNOTINCHANNEL`; `441` is
now reached only when the nickname *is* connected but isn't a member of *this* channel.

**Rationale**: FR-013 is the largest single change in this batch but stays a pure
extend-to-a-loop of already-correct single-channel logic — no new validation rule, no new gate.
FR-017 reuses `WHOIS`/`KILL`'s exact existing "check the registry before assuming
not-in-channel" pattern rather than inventing a new distinction.

## Story 5 — Information-query completeness (FR-018 through FR-022)

**Decision (FR-018/FR-019)**: New `UserhostCommandHandler`/`InfoCommandHandler` classes,
structurally matching the smallest existing query handlers in this codebase (e.g.
`TimeCommandHandler`) — constructor takes only what's needed (`NicknameRegistry`+`serverName`
for `USERHOST`; just `serverName` for `INFO`), `handle()` sends one reply. `USERHOST` looks up
each of up to 5 space-separated nicknames given (RFC1459 §4.9) via the existing
`NicknameRegistry`, replying `302 RPL_USERHOST` with one space-separated
`nick[*]=[+|-]user@host` entry per found nickname (the `*` marks an operator, `+`/`-` marks
present/away — both already computable from existing `ClientSession` state). `INFO` sends a
short, fixed multi-line `371 RPL_INFO` burst (server name/version, already available via the
same `serverVersion` source `VERSION`'s `351` reply already uses) followed by `374
RPL_ENDOFINFO`.

**Decision (FR-020)**: `WhoisCommandHandler.handle()`'s not-found branch sends `318
RPL_ENDOFWHOIS` immediately before its existing `return`, instead of returning without it —
using the same `target.nickname()`... except there is no `target` on this path, so the reply
uses the originally-requested `targetNickname` string instead (the only value available), the
same substitution `ERR_NOSUCHNICK` on the same branch already makes.

**Decision (FR-021)**: `WhoCommandHandler.handle()`'s exact-nickname branch (the `else`
alongside `arg == null` and `isMask`) stops calling the shared `isVisibleTo` gate — an exact
lookup always calls `sendWhoReply` for its single candidate if found, regardless of the
`invisible` mode. The mask and no-argument forms keep the existing `isVisibleTo` filter
unchanged.

**Decision (FR-022)**: `AwayCommandHandler.handle()`'s `message.params().isEmpty()` branch
(already correctly clearing away status) becomes `message.params().isEmpty() ||
message.params().getFirst().isEmpty()` — an empty trailing argument now takes the same
clear-away path a fully-absent argument already does, instead of falling through to the
set-away branch with an empty reason string.

**Rationale**: FR-018/FR-019 complete two commands `001-ircv3-server` already declared in the
wire-protocol layer as part of "the full RFC/IRCv3 command+numeric catalog regardless of
handler support" — this is finishing an already-committed-to surface, not adding a new one.
FR-020/FR-021/FR-022 are each a single-branch correction reusing an already-established
sibling pattern (`ERR_NOSUCHNICK`'s existing substitution; the mask-vs-exact distinction
`WHO`'s own class javadoc already documents as intentional for `whoMaskEnabled`, just not yet
applied to invisibility the same way).

## Story 6 — Operator self-notification (FR-023)

**Decision**: `OperCommandHandler.handle()`'s success branch, after the existing `381
RPL_YOUREOPER` send, additionally enqueues an unsolicited `MODE <nick> +o` message (prefix =
server name, matching every other server-originated `MODE` echo in this codebase, e.g.
`UserModeCommandHandler`'s own self-mode-change echo) directly to `session.writer()`.

**Rationale**: Reuses `UserModeCommandHandler`'s existing self-directed `MODE` echo shape
exactly — `OPER` granting the `operator` user mode is functionally the same kind of state
change `MODE <self> +o` already produces an echo for when self-initiated (blocked by privilege
today, but the echo shape for a *successful* operator-mode grant already exists in that
handler and is reused here verbatim).
