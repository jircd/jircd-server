# Contract: Client-to-Server Commands

This contract has two layers, matching the `jircd-protocol`/`jircd-core`
split in plan.md's "Domain Model & Bounded Contexts":

1. **Wire-protocol recognition** (`jircd-protocol`) — the complete
   RFC 1459/2812 command set, plus the IRCv3 framework commands this
   project uses (`CAP`, `AUTHENTICATE`, `TAGMSG`). `jircd-protocol` is
   explicitly designed to be reusable by a future IRC *client* library
   (research.md "Protocol/server boundary"), so its command model and
   parser MUST recognize the full standard set — not just the subset this
   particular server implements — the same way a client library would
   need to parse a `WHOIS` reply from *any* server, not only one that
   happens to also run this codebase. See "Full Command Catalog" below.
2. **Server behavior** (`jircd-core` and extensions) — only the commands
   below, under "Implemented in This Release", have an actual handler in
   this server. A wire-protocol-recognized command with no handler is
   rejected the same way an unrecognized one would be: `421
   ERR_UNKNOWNCOMMAND` (contracts/irc-numeric-replies.md). Recognizing a
   command's grammar and having a handler for it are independent — this
   is why the two are documented separately.

Line-based text protocol per RFC 1459/2812 semantics with IRCv3 `CAP`
negotiation layered on top. A line MUST NOT exceed 512 bytes (including
the trailing CR-LF) for its command and parameters, plus up to 4096
additional bytes for a `message-tags` tag section specifically, per the
IRCv3 message-tags specification's required server-side allowance
(FR-025, FR-049) — this server implements `message-tags`, so the
allowance applies. The two budgets are independent (005-fix-batch-conformance
FR-011): a tag section alone over 4096 bytes is rejected even when the
line's total length would fit under the old, incorrectly-combined
4608-byte figure. The tag section's own byte count includes both the
leading `@` and the trailing space separating it from the command, per
the message-tags spec (irctest `message_tags.py::testLengthLimits`) — a
tag section of exactly 4096 bytes including that trailing space is
accepted; 4097 is rejected. A line exceeding either budget MUST be
rejected with `417 ERR_INPUTTOOLONG` (contracts/irc-numeric-replies.md)
— a dedicated error distinct from `421`/`461`'s generic
malformed-message handling (FR-015, FR-049), not silently truncated or
partially processed.

A client-supplied `+`-prefixed tag (message-tags' own "client-only tag"
namespace) on a `PRIVMSG`/`NOTICE`/`TAGMSG` is forwarded verbatim to
every recipient that has `message-tags` negotiated — including the
sender's own `echo-message` echo — and dropped entirely for any
recipient that hasn't (005-fix-batch-conformance FR-010; a recipient
with nothing negotiated has no way to parse a tag section at all,
oragono/Ergo issue 754). A client-supplied tag WITHOUT the `+` prefix is
never forwarded to anyone, sender's own echo included — the bare
namespace is reserved for the message-tags spec's own defined tags
(`msgid`/`time`), not something a client may set (irctest
`message_tags.py::testBasic`). This project has not implemented the
`labeled-response`/`batch` capabilities, so the `label` tag's own
special-cased echo-to-sender-only semantics are correspondingly
unimplemented — a `label` tag is dropped by the same bare-namespace rule
above, not given the same-party exception `labeled-response` would
require (out of scope for FR-010, confirmed during the T033 irctest
re-run; see quickstart.md's "confirmed out of scope" note).

Command names are matched case-insensitively (FR-015) — every command
below is written in canonical uppercase purely for readability, not
because case is significant on the wire; `join`/`Join`/`JOIN` are the
same command. Nicknames and channel names are likewise compared
case-insensitively wherever a command names one as a target (FR-052,
research.md "IRC casemapping") — `PRIVMSG alice` and `PRIVMSG Alice`
reach the same client if that client registered as either casing.

Human-readable message text — `PRIVMSG`/`NOTICE` bodies, channel topics,
realnames, and channel names — MUST be valid UTF-8 (FR-054). A field
containing an invalid UTF-8 byte sequence is rejected as malformed
(FR-015, `421 ERR_UNKNOWNCOMMAND`-style rejection — the same generic
malformed-message handling as any other unparseable message; unlike the
line-length/channel-grammar cases, this doesn't get a dedicated numeric
like `417`/`476`, since RFC 1459/2812 predates UTF-8 entirely and no de
facto standard numeric for this exists to reuse. Not `461
ERR_NEEDMOREPARAMS` — the parameter is present, just invalidly encoded,
a different failure than a missing one)
rather than passed through, silently mistranscoded, or partially
accepted. This does not apply to nicknames or the `USER` command's
`<user>` parameter — both are protocol identifiers with their own
dedicated, ASCII-oriented grammars ("Connection Registration Grammar"
below), not human-readable content.

Numeric reply references point to [irc-numeric-replies.md](./irc-numeric-replies.md).

Every command below that includes a sender prefix on its outgoing message
(`JOIN`, `PART`, `PRIVMSG`, `NOTICE`, `QUIT`, `KICK`, `MODE`) presents that
sender in the standard `nickname!ident@hostname` form (FR-030), where
`hostname` is subject to the cloak `ServerExtension` described in
"Administration" below. Every numeric reply, by contrast, is prefixed
with the server's own name (`ServerConfiguration.serverName`, FR-050,
data-model.md) — the server-originated counterpart to a client's
hostmask prefix — not any client's identity.

## Implemented in This Release

### Connection Registration

| Command | Direction | Preconditions | Effect | Replies (see numeric-replies contract) |
|---|---|---|---|---|
| `CAP LS [version]` | C→S | Any time before registration completes | Server returns its currently-available capability list: core `cap-notify` plus every enabled provider's capability, sorted. The highest version a connection has sent is remembered (digit-only, saturating; a malformed, signed or empty version counts as none); once it is 302 or more, a later lower or omitted version does not lower it | `CAP <nick\|*> LS :<capabilities>` — the target is the client's nick once set, else `*`. A 302-or-later client gets `CAP <target> LS * :<part>` continuation lines when the list does not fit one line; an older client always gets one line, and enabling a provider whose offer would not fit that line is rejected |
| `CAP LIST` | C→S | Any time | Server returns the requesting client's own currently NEGOTIATED capabilities, distinct from `LS`'s full offered list (005-fix-batch-conformance FR-007). After a runtime disable the pruned names are gone. Explicitly negotiated `cap-notify` is listed; support implied only by `CAP LS 302` is not | `CAP <nick\|*> LIST :<negotiated capabilities>` — same target rule |
| `CAP REQ :<caps>` | C→S | After `CAP LS` | Atomic, applied to a candidate set token by token in order: an addition must be currently offered (including `cap-notify`); a `-name` removal is accepted if the client holds the name, offered or not, or if the name is a known capability it does not hold (for example one a disable already pruned, an acknowledged no-op); an unknown name the client does not hold refuses the request. `-cap-notify` is refused once the client has sent `CAP LS 302` or later; an older client may remove it. Any refusal sends one `NAK` and changes nothing; an empty request is NAKed with an empty list; a reply that would exceed 512 bytes gets `417` and changes nothing (issue #71). Validation, the size check, the change and the reply happen in one step that a concurrent runtime disable cannot interleave with | `CAP <nick\|*> ACK`/`CAP <nick\|*> NAK` — the reply echoes back the full requested list exactly as given, including repeats and `-` prefixes (005-fix-batch-conformance FR-009); the target is the client's nick once set, else `*` |
| `CAP NEW :<caps>` / `CAP DEL :<caps>` | S→C | After a runtime enable or disable (`EXTENSION`, `REHASH`, `SIGHUP`) changes the offered set, or after a provider whose hook faulted is withdrawn as `FAILED` (with its hard dependents, in one `DEL`) | Sent to every connected client that sent `CAP LS 302` or later or negotiated `cap-notify`, whether or not it held the names; never to others, and never for a toggle that changed nothing or a client's own `REQ` | `CAP <nick\|*> DEL :<names>` then `CAP <nick\|*> NEW :<names>`: names only, sorted and deduplicated, split on name boundaries into independently valid lines if too long. `NEW` restores no negotiation |
| `CAP END` | C→S | After negotiation | Ends negotiation; registration may complete | (none; unblocks registration) |
| `CAP <unrecognized subcommand>` | C→S | Any time | Rejected | `410 ERR_INVALIDCAPCMD <subcommand> :<reason>` (005-fix-batch-conformance FR-008; was previously the generic `421`) — the second param echoes back the client's own invalid subcommand token verbatim (irctest `cap.py::testInvalidCapSubcommand`), not folded into the reason text |
| `NICK <nickname>` | C→S | Session not yet holding a nickname, or changing an existing one | Atomically claims the nickname (FR-002) | `433 ERR_NICKNAMEINUSE` on conflict; for a post-registration change, a `NICK <nickname>` notification (prefixed with the OLD hostmask) is broadcast to the changing client itself and every channel it's currently a member of (005-fix-batch-conformance FR-001/FR-002) — the initial pre-registration claim stays silent, confirmed via the registration burst instead; reclaiming the exact current nickname (same case, a true no-op) broadcasts nothing at all, distinct from a case-only change which is still a real change and does broadcast (irctest `regressions.py::testCaseChanges`) |
| `USER <user> <mode> <unused> :<realname>` | C→S | Nickname claimed; this session has not already processed a `USER` command, whether or not it has reached `REGISTERED` yet (FR-001 — registration-only, strictly one-shot); `<realname>` MUST be non-empty (003-irctest-conformance-fixes FR-002) and valid UTF-8 (FR-054) | Completes registration (FR-001) | Registration Completion Burst (below); `462 ERR_ALREADYREGISTRED` if this session has already processed a `USER` command, checked before the UTF-8 validity check below; `461 ERR_NEEDMOREPARAMS` if `<realname>` is empty (003-irctest-conformance-fixes FR-002 — an empty trailing parameter is syntactically present but semantically equivalent to "not given"); `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection (FR-015) if `<realname>` isn't valid UTF-8 |

#### Connection Registration Grammar

CAP negotiation got its own dedicated grammar component
(`CapabilityNegotiationGrammar`, T018) because `CAP`'s subcommands
(`LS`/`REQ`/`ACK`/`NAK`/`END`) branch into genuinely different follow-on
syntax — that's a real sub-language within a line. `NICK`/`USER` don't
have that: `NICK` takes exactly one parameter and `USER` exactly four
fixed positional parameters (the last one trailing), so their line shape
is fully covered by the generic `COMMAND [params] [:trailing]` framing
`MessageParser` already handles, plus arity metadata from the `Command`
catalog (T014) for `461 ERR_NEEDMOREPARAMS`. No dedicated grammar class is
needed for that part — this is a deliberate asymmetry with `CAP`, not an
oversight.

What *was* missing, and does need its own definition, is the **content**
grammar for the two identifiers registration produces — what characters
and length are actually legal, independent of parameter count:

- **Nickname** (RFC 2812 §2.3.1): `( letter / special ) *N( letter /
  digit / special / "-" )` — one leading letter or `special`
  (`[`, `]`, `\`, `` ` ``, `_`, `^`, `{`, `|`, `}`), followed by up to
  `N` more letters/digits/`special`/`-`, where `N + 1` is the server's
  configured maximum nickname length (`ServerConfiguration.nicknameMaxLength`,
  FR-056; `9` characters total by default, matching RFC 2812's own
  example length). A `NICK` violating this is `432 ERR_ERRONEUSNICKNAME`
  (contracts/irc-numeric-replies.md); this is the concrete definition the
  "Edge case: nickname format" note there previously pointed at without
  ever stating.
- **Username** (the `<user>` parameter of `USER`, RFC 2812 §2.3.1's
  `user` production): any character except space, `@`, and ISO control characters (C0 including NUL, CR, LF, TAB and \x01, DEL, and C1) — no
  length limit specified by the RFC itself, but this server MUST apply
  the same 9-character limit as `Hostmask`'s `ident` field for display
  consistency (contracts/irc-protocol-commands.md intro; FR-030) — an
  overlong `<user>` is truncated to 9 characters for the `ident` shown in
  the hostmask, not rejected outright (unlike an invalid nickname, which
  *is* rejected — usernames don't get their own `ERR_*` numeric in
  RFC 2812).

**Registration completion sequencing** (a `jircd-core`/`ConnectionLifecycle`
state-machine concern, not a wire-protocol grammar concern, since it's
about the order of already-parsed commands, not how to parse one line):
a session reaches `REGISTERED` once it holds a claimed nickname (`NICK`
succeeded) *and* has sent `USER`, *and* — if it sent `CAP LS` at all — has
also sent `CAP END`. `NICK` and `USER` MAY arrive in either order; `CAP`
negotiation, if used, MAY be interleaved with either. The Registration
Completion Burst below fires the moment all applicable conditions are
satisfied, regardless of which arrived last. `USER` itself, however, is
strictly one-shot per connection: it MUST NOT succeed a second time on
the same session, whether the repeat arrives before `REGISTERED` (e.g.
`USER` sent twice while still waiting on `NICK` or `CAP END`) or, far
more commonly, after (FR-001) — `462 ERR_ALREADYREGISTRED` either way,
the same "already processed a `USER`" check regardless of which side of
`REGISTERED` the repeat falls on (data-model.md `ClientSession.ident`).
`NICK` has no such restriction and remains freely re-sendable after
registration to change nickname (FR-002) — this asymmetry is
deliberate: `NICK` names an ongoing, changeable identity, `USER`
supplies one-time registration data with no "change" semantics defined
for it at all.

#### Registration Completion Burst

What "`001 RPL_WELCOME` and standard post-registration burst" actually
means, concretely (FR-051) — previously referenced without ever being
defined, the same class of gap the nickname/channel grammars closed for
`432`/`476`:

| Numeric | Content |
|---|---|
| `001 RPL_WELCOME` | Welcome confirmation, addressed to the newly registered nickname |
| `002 RPL_YOURHOST` | `serverName` and `serverVersion` (`ServerConfiguration`, FR-050, data-model.md) |
| `003 RPL_CREATED` | This running process's start time — not a fixed software release date |
| `004 RPL_MYINFO` | `serverName`, `serverVersion`, the currently-recognized user-mode letters (`o` this release, FR-044, data-model.md `UserMode`), and the currently-recognized channel-mode letters — read from the same server-scoped `ChannelMode` catalog snapshot `005`'s `CHANMODES` also reads (data-model.md `SupportedFeatures`), recomputed only when `ExtensionRegistry`'s state changes, not per registration, so this never drifts out of sync with what `MODE` actually recognizes or with what `005` advertises |
| `005 RPL_ISUPPORT` (one or more lines) | `SupportedFeatures` (FR-055, data-model.md — server-scoped, not computed per session) — `CASEMAPPING=rfc1459`, `CHANTYPES=#`, `NICKLEN`, `CHANNELLEN`, `TOPICLEN` (FR-056 — `9`/`50`/`390` by default, but each `ServerConfiguration`-configurable, not fixed constants), `MODES` (FR-064 — `6` by default, likewise configurable), `CHANMODES=Ibe,k,l,imnpst` (computed from the same `ChannelMode` catalog snapshot `004` reads: list modes `Ibe` in group A, `k` in B, `l` in C, and boolean modes `imnpst` in D); `EXCEPTS=e`, `INVEX=I`, `MAXLIST=Ibe:100`, `PREFIX=(ov)@+`, `UTF8ONLY`; split across additional `005` lines if the full token set wouldn't fit one line within FR-049's 512-byte limit (this release's default token set always fits in one) |
| `375`/`372`…/`376`, or `422 ERR_NOMOTD` | Closes the burst — the message of the day from the `motdPath` file (issue #77, same replies as `MOTD`); `422` when `motdPath` is unset or the file cannot be used, which gives clients a defined completion signal instead of an indefinite wait for one |

**Contract notes**:
- `CAP` negotiation MUST be usable before `NICK`/`USER` completes
  registration (FR-006), and registration MUST still succeed for clients
  that skip `CAP` entirely (FR-008).
- Only `message-tags`, `server-time`, `echo-message`, `away-notify`, `batch`,
  `multi-prefix` and core `cap-notify` may appear in the `CAP LS` response
  (FR-025, extended by 011-away-notify-batch, issue #96 and #97); the SASL
  capability referenced in the spec is deferred with Story 3 and MUST NOT
  appear. `cap-notify` is core infrastructure, not an extension: it is
  offered even when no capability provider is installed and cannot be
  toggled.
- `message-tags` itself now contributes one tag unconditionally: `msgid`,
  a server-generated unique identifier present on every message relayed
  to a recipient that has negotiated `message-tags` at all, regardless
  of whether `server-time` is also negotiated (FR-059, research.md
  "Message identifiers"). Unlike `server-time`'s `time` tag, `msgid`
  isn't gated by a second, separately-declinable capability — there is
  no way to negotiate `message-tags` without also getting `msgid`.
- **Ownership** (014-capability-plugin-spi): each capability module owns its behavior; core
  keeps `CAP` negotiation, tag syntax and size validation, authorized delivery and byte
  budgets. `message-tags` selects the client-only (`+`) tags to forward, contributes `msgid`
  and owns `TAGMSG`; `server-time` contributes `time` independently of `message-tags`;
  `echo-message` asks for the sender copy through a recipient role that core merges with the
  ordinary one, so a self-targeted message is still delivered once; `away-notify`, `batch` and
  `multi-prefix` are described in their own contract notes. Removing a module from the
  classpath removes both its `CAP LS` offer and its behavior; core and the other modules are
  unaffected.
- **Runtime changes** (014-capability-plugin-spi, #97): disabling a capability removes it from
  every connected session's negotiated set in the same step as its offer, so `CAP LIST` and any
  output prepared afterwards reflect it; output prepared before may still arrive after the
  `CAP DEL`. A batch group queued before the change keeps its closing `BATCH`; a group queued
  after `batch` or `message-tags` stopped being effective for its recipient is sent plain, and
  `batch` itself stays negotiated. See "Runtime capability changes" in
  `server-configuration.md`.
- **Runtime faults** (#102): a capability hook that faults has that one result replaced by its
  default (for example the highest status in `NAMES`, no sender copy, no provider tag, a plain
  group), so the reply or message still goes out. The provider is then withdrawn as if disabled,
  with the same `CAP DEL`; a provider-owned command it owned is answered `421` from then on. See
  "Runtime capability faults" in `server-configuration.md`.
- `005`'s token set (FR-055) is deliberately minimal: every token
  restates a value this specification already committed to elsewhere
  (casemapping, grammar limits, mode prefixes, UTF-8 enforcement), none
  is a new setting introduced by `005` itself. Tokens some real networks
  send but this release has no concrete answer for (`CHANLIMIT`,
  `NETWORK`, `TARGMAX`) are omitted rather than sent with an invented
  value — omission is the standard, correct way to say "unspecified."
  `TOPICLEN` is no longer in that omitted set: FR-056 gave this project
  a concrete, enforced answer for topic length, so it is now sent like
  `NICKLEN`/`CHANNELLEN`.
- Any numeric reply sent while a session has not yet claimed a nickname
  — most notably `431`/`432`/`433`, all reachable during `NICK`
  negotiation before registration completes — MUST address that reply
  to `*` (FR-053), the standard "no nickname yet" placeholder, not an
  empty value or a nickname the session hasn't actually claimed.

### Connection Keep-Alive

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `PING <token>` | C→S | Any time | Server replies immediately | `PONG <server-name> <token>` — two params, server's own name first (005-fix-batch-conformance FR-005); `409 ERR_NOORIGIN` if `<token>` is omitted entirely, never a fabricated substitute (FR-006) |
| `PING [token]` | S→C | This connection has been idle beyond the configured keep-alive probe interval (FR-039) | Probes whether the client is still there | Client is expected to reply `PONG [token]`; no reply within the configured timeout closes the connection (`ERROR` sent first, then the same FR-017 cleanup/notification path as any other disconnect) |
| `PONG [token]` | C→S | Sent in reply to a server-initiated `PING` | Resets this connection's keep-alive timer (FR-039) | (none; an unsolicited `PONG` with no outstanding server `PING` is accepted and ignored) |
| `ERROR :<reason>` | S→C | Server is about to forcibly close this connection (a keep-alive timeout, FR-039, or another protocol-level cause) | Final line sent before closing the socket | (none; the connection closes immediately after) |

**Contract notes**:
- `PING`/`PONG` are core connection-management behavior, like capability
  negotiation (FR-035) and channel moderation (FR-036) — always present,
  never one of the toggleable `capabilities`/`server-extensions`.
- This is symmetric, not just server-initiated: a client MAY send `PING`
  at any time (even before registration completes) and MUST receive an
  immediate `PONG`, independently of whatever keep-alive probing the
  server itself is doing on that connection.
- A keep-alive-triggered disconnect notification carries a
  server-generated reason distinct from `QUIT`'s own default (FR-060,
  research.md "Voluntary disconnect and quit reasons") — both funnel
  through the identical FR-017 cleanup path, but a channel's remaining
  members can still distinguish "this member quit" from "this member
  timed out" in their own client's disconnect log.

### Channel Operations

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `JOIN <channel>{,<channel>}[ <key>{,<key>}]` | C→S | `REGISTERED` session; each `channel` conforms to the Channel Name Grammar (below); neither the joiner's presented identity nor their real hostname/IP-based identity MUST match an active `ban-mask` entry without a matching `e` exception (FR-062, #78) | A comma-separated channel list (with an optional matching comma-separated key list, RFC1459 §4.2.1 — core JOIN grammar, not an extension, 005-fix-batch-conformance FR-013) is processed one channel at a time, independently; each creates the channel if absent (first joiner becomes operator, FR-013) or joins existing (FR-003); a channel that already has a topic set includes it in this same reply burst (FR-014) | Per channel: `JOIN` echoed to all members; if a topic is set, `332 RPL_TOPIC` immediately followed by `333 RPL_TOPICWHOTIME <nick> <channel> <setter> <unix-ts>` to the joiner (FR-014); `353 RPL_NAMREPLY` + `366 RPL_ENDOFNAMES` to joiner; `476 ERR_BADCHANMASK` if that channel violates the grammar; `474 ERR_BANNEDFROMCHAN` if banned (FR-062) — one channel failing its own check doesn't stop the others in the same command |
| `PART <channel> [:reason]` | C→S | Session is a member; `<reason>`, if given, MUST be valid UTF-8 (FR-054) | Removes membership | `PART` echoed to all (former) members, with `<reason>` if one was given, absent otherwise (unlike `QUIT`, no default is synthesized); `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection (FR-015) if a given `<reason>` isn't valid UTF-8 |
| `PRIVMSG <target> :<text>` | C→S | `REGISTERED` session; for a channel target, membership is NOT required by default — only when that channel's `members-only` restriction is active (FR-004, FR-013/FR-043); for a nickname target, it must be any registered nickname; `<text>` MUST be non-empty (005-fix-batch-conformance FR-004) and valid UTF-8 (FR-054); for a channel target, neither the sender's presented identity nor their real hostname/IP-based identity MUST match an active `ban-mask` entry without a matching `e` exception (FR-062, #78) | Delivers to all other channel members (FR-004) or the direct-message recipient (FR-005), assigning one server-generated `msgid` shared by every recipient with `message-tags` negotiated (FR-059); the sender's own client-supplied message tags, if any, are forwarded to every message-tags-negotiated recipient alongside the server-contributed ones (005-fix-batch-conformance FR-010) | `PRIVMSG` delivered to recipients; `echo-message`-negotiated senders also receive their own message back (with the same `msgid` as everyone else, letting them correlate it with what they sent) — this now genuinely works for a direct-message target too, not only channels (005-fix-batch-conformance FR-003); `412 ERR_NOTEXTTOSEND` if `<text>` is empty (FR-004); `442 ERR_NOTONCHANNEL` if `members-only` is active and the sender isn't a member (FR-013/FR-043); `404 ERR_CANNOTSENDTOCHAN` if `moderated` blocks the sender or `b` matches without an `e` exception (FR-062, #78); `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection (FR-015) if `<text>` isn't valid UTF-8 |
| `NOTICE <target> :<text>` | C→S | Same as `PRIVMSG` | Same delivery semantics as `PRIVMSG`, but MUST NOT trigger automated replies | Delivered like `PRIVMSG` |
| `QUIT [:reason]` | C→S | Any time, including before registration completes (FR-060); `<reason>`, if given, MUST be valid UTF-8 (FR-054) | Disconnects; removes all channel memberships, if any (FR-017) | `ERROR :<reason>` sent to the quitting client itself before the connection closes (RFC 2812 §3.1.7, 003-irctest-conformance-fixes FR-001 — the same enqueue-then-close order `KILL` and the keep-alive timeout already use); `QUIT` echoed to all affected channels, always carrying a reason — `<reason>` if given, a fixed server default otherwise (FR-060, research.md "Voluntary disconnect and quit reasons") — never blank; `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection (FR-015) if a given `<reason>` isn't valid UTF-8 |
| `TOPIC <channel>` | C→S | `REGISTERED` session; no membership required for a non-private/secret channel, or for a member/administrator of one (FR-040/FR-041's discovery framing, subject to FR-047) | Returns `channel`'s current topic | `332 RPL_TOPIC` immediately followed by `333 RPL_TOPICWHOTIME <nick> <channel> <setter> <unix-ts>` if a topic is set, `331 RPL_NOTOPIC` alone if not; `403 ERR_NOSUCHCHANNEL` if `channel` doesn't exist, or is private/secret and the requester is neither a member nor an administrator (FR-047 — same response either way) |
| `TOPIC <channel> :<topic>` | C→S | `REGISTERED` session; sender is a channel operator (FR-013); `<topic>` MUST be valid UTF-8 (FR-054) and MUST NOT exceed `ServerConfiguration.topicMaxLength` (FR-056) | Sets/changes `channel`'s topic (FR-040), recording the setter's `nick!user@host` via `PresentedIdentity` (including cloaking) and the current Unix timestamp in seconds. An empty topic clears the topic, setter and timestamp; neither JOIN nor a TOPIC query then sends `333` | `TOPIC` echoed to all members on success; `482 ERR_CHANOPRIVSNEEDED` if sender isn't an operator; `421 ERR_UNKNOWNCOMMAND`-style malformed-message rejection (FR-015) if `<topic>` isn't valid UTF-8; `417 ERR_INPUTTOOLONG` if `<topic>` exceeds the configured maximum length (FR-056, FR-049's numeric reused) |
| `NAMES <channel>` | C→S | `REGISTERED` session; no membership required for a non-private/secret channel, or for a member/administrator of one (FR-041, subject to FR-047) | Returns `channel`'s current membership list, the same on-demand query `JOIN` already triggers automatically | `353 RPL_NAMREPLY` + `366 RPL_ENDOFNAMES`; `461 ERR_NEEDMOREPARAMS` if `channel` is omitted; `403 ERR_NOSUCHCHANNEL` under the identical private/secret condition `TOPIC` uses (FR-047) |
| `LIST` | C→S | `REGISTERED` session | Returns every currently active channel (FR-042), except a private/secret one the requester isn't a member of and isn't an administrator for (FR-047) — silently omitted, not flagged as skipped | One `322 RPL_LIST` per (visible) channel, then `323 RPL_LISTEND` |

#### Channel Name Grammar

Mirrors "Connection Registration Grammar" above: no dedicated grammar
*class* is needed (a channel name is a single token, already covered by
the generic `COMMAND [params]` framing `MessageParser` handles), but the
**content** grammar — what characters and length are actually legal —
was previously missing and needed its own definition (FR-048).

- **Channel name** (RFC 2812 §1.3, standard-type channels only —
  `#`-prefixed; the `&`/`+`/`!` channel-type prefixes RFC 2811 also
  defines are out of scope, this server has one channel namespace):
  a leading `#`, followed by additional characters excluding space,
  comma, and control characters, up to the server's configured maximum
  channel name length (`ServerConfiguration.channelNameMaxLength`,
  FR-056; `50` characters total, including the leading `#`, by default).
  A `JOIN` naming a channel that violates this is `476 ERR_BADCHANMASK`
  (contracts/irc-numeric-replies.md) — RFC 2812's existing numeric for
  "the channel name/mask given is invalid," reused rather than inventing
  a new one, the same way `432 ERR_ERRONEUSNICKNAME` was reused (not
  invented) for nickname grammar.
- This is an *independent* check from FR-003's channel-name uniqueness,
  the same relationship nickname format (`432`) and nickname uniqueness
  (`433`) already have to each other: a syntactically valid name that's
  already claimed still succeeds (joins the existing channel, FR-003);
  a syntactically invalid name is rejected before uniqueness is even
  considered.
- A third, independent check: the name MUST also be valid UTF-8 (FR-054)
  — the byte-exclusion grammar above doesn't by itself guarantee
  well-formed UTF-8, so a name can pass it and still fail this check.
  Also `476 ERR_BADCHANMASK`, the same numeric a grammar violation uses —
  both are "this isn't a legal channel name" from the client's point of
  view, not two different failure classes worth distinguishing.

**Contract notes**:
- `TOPIC`-viewing, `NAMES`, and `LIST` deliberately do not require channel
  membership — they are discovery operations (FR-041/FR-042), the same
  role `WHOIS` (below) plays for user information, not moderation-gated
  like `KICK`/`MODE` (Story 5) — **except** for a private/secret channel
  (FR-047), where all three treat a non-member, non-administrator
  requester exactly as if the channel didn't exist. `TOPIC`/`NAMES` use
  `403 ERR_NOSUCHCHANNEL` (the same error a genuinely nonexistent channel
  already produces); `LIST` simply omits the channel from its output.
  Neither response is distinguishable from the channel not existing at
  all — a weaker, "access denied"-style error would defeat the entire
  point of `private`/`secret`.
- `TOPIC`-setting is the one operator-gated action in this section; it
  reuses `Channel.operators` (FR-013), already established at `JOIN` — no
  separate authorization mechanism is introduced for it.
- `353 RPL_NAMREPLY`'s visibility-symbol parameter (the one preceding the
  channel name) MUST reflect the channel's actual `private`/`secret`
  state — `@` for `secret`, `*` for `private`, `=` for public
  (003-irctest-conformance-fixes FR-003), matching RFC 2812; `private` and
  `secret` are already mutually exclusive by validation rule
  (data-model.md `Channel`), so the three cases are exhaustive.
- `353 RPL_NAMREPLY`'s nickname list MUST prefix each operator's nickname
  with `@` and each voiced (non-operator) member's nickname with `+`,
  the standard IRC convention every client already expects — without it,
  `voice` (FR-045) would be invisible in practice even though it's
  correctly enforced server-side: a client showing no visual difference
  between a voiced and a non-voiced member defeats the point of granting
  voice at all. A member who is both an operator and voiced is prefixed
  `@` only for a requester that has not negotiated `multi-prefix` (below),
  matching standard client expectations (operator implies the
  ability to speak; the `+` prefix communicates *additional* information
  a plain `@` doesn't).
- **`multi-prefix`** (issue #96): to a requester that has negotiated it, `353`
  lists every status the member holds, in ISUPPORT `PREFIX` order (`@` before
  `+`), so an operator who is also voiced is `@+nick`, and `352`'s `<status>`
  flags likewise end in `@+` (`H@+`, `G*@+`). A requester without it gets
  today's single highest prefix (`@nick`, `H@`). The choice follows the client
  receiving the reply, never the member listed, and applies to `NAMES`
  (targeted and bare), the `353` sent on `JOIN`/`SAJOIN`, and the
  channel-scoped `WHO`. Ownership (014-capability-plugin-spi): core keeps the
  member facts — every held status, highest first in `PREFIX` order — and renders the
  highest status by default; the `jircd-capabilities/multi-prefix` module replaces the status
  slot of the `353` token and the `352` flags with all of them for a requester that negotiated
  it, and declares the two-symbol bound the reply budget reserves. Without the module every
  requester sees the highest status only.

### User Queries (Story 7)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `WHOIS [target-server] target` | C→S | `REGISTERED` session | Looks up `target` (the sender's own session if omitted entirely) and returns its nickname, ident, hostname, and real name (FR-037). An optional leading `target-server` parameter (RFC 2812's two-parameter form) is accepted but not used to route anywhere — this server has no federation to route to (FR-021) — the nickname is always the *last* parameter, whichever form was sent (003-irctest-conformance-fixes FR-006/FR-007). The returned hostname follows FR-038's three-tier resolution: real value for a self-lookup or an administrator, otherwise the same presented value the target's message hostmask already shows to this sender (FR-030/031). If `target` currently holds the `operator` user mode, an operator-status line is included — visible to any querying client, not gated like the hostname resolution (FR-037, FR-044) | `311 RPL_WHOISUSER`, then `312 RPL_WHOISSERVER` (this server's own name — restored per 003-irctest-conformance-fixes FR-006, there being no other server it could ever name), then `313 RPL_WHOISOPERATOR` if `target` holds `operator`, then `318 RPL_ENDOFWHOIS` on success; `401 ERR_NOSUCHNICK` then `318 RPL_ENDOFWHOIS` if `target` isn't connected (005-fix-batch-conformance FR-020 — the reply sequence always closes with `318` regardless of outcome; the not-found path previously omitted it) |
| `WHO [mask]` | C→S | `REGISTERED` session | Four forms dispatched on `mask`'s shape (FR-061): a channel name lists that channel's current members (same visibility as `NAMES`, including `invisible` members — FR-041/FR-047, never gated by invisibility or `whoMaskEnabled`); an exact nickname always matches if connected, bypassing `invisible` entirely (005-fix-batch-conformance FR-021 — previously incorrectly gated the same as the mask form); a wildcard (`*`/`?`) pattern matches against nicknames; omitted matches every connected user. Only the latter two (mask, no-argument) exclude an `invisible`-holding match (FR-044) unless the requester shares a channel membership with it or holds administrator privilege (FR-032/FR-047's transparency pattern, reused); independently, if `ServerConfiguration.whoMaskEnabled` is `false`, a non-administrator's mask/no-argument form returns no matches at all, administrators exempt (FR-061) — the exact-nickname form is unaffected by either gate. Each match's hostname follows the same FR-038 resolution `WHOIS` uses | One `352 RPL_WHOREPLY` per (visible) match — its `<server>` field restored to this server's own name (003-irctest-conformance-fixes FR-006) — then `315 RPL_ENDOFWHO` — zero matches (whether from a genuine non-match, `invisible` exclusion, or `whoMaskEnabled: false`) is not an error, still closes with `315`, all three indistinguishable to the requester; `403 ERR_NOSUCHCHANNEL` for the channel-name form under the identical private/secret condition `TOPIC`/`NAMES` use (FR-047) |
| `USERHOST <nickname> [<nickname> ...]` | C→S | `REGISTERED` session | Looks up host information for up to 5 space-separated nicknames (RFC1459 §4.9, 005-fix-batch-conformance FR-018 — completes a command previously recognized but unhandled); the hostname follows the same FR-038 resolution `WHOIS`/`WHO`/`WHOWAS` already use, never a second independent computation | `302 RPL_USERHOST`, one space-separated `nick[*]=[+\|-]user@host` entry per found nickname — `*` marks an IRC operator, `+`/`-` marks present/away; nicknames not found are silently omitted, not an error |
| `LUSERS` | C→S | `REGISTERED` session | Server-wide counts. Users are the `REGISTERED` sessions only (a connection that has claimed a nickname but not sent `USER` is *unknown*, not a user). `253` counts connections accepted but not yet `REGISTERED` and is omitted when there are none; a connection leaves that count the moment it starts closing (registration timeout, `QUIT`, I/O failure). `265`/`266` carry `<current> <max>`: `max` is the most registered users seen at once, raised at each registration, and never decreases. Local and global are equal on this single server | `251 RPL_LUSERCLIENT`, `252 RPL_LUSEROP`, `253 RPL_LUSERUNKNOWN` (conditional), `254 RPL_LUSERCHANNELS`, `255 RPL_LUSERME`, `265 RPL_LOCALUSERS`, `266 RPL_GLOBALUSERS` |
| `INFO` | C→S | `REGISTERED` session | Returns a short, fixed server-information burst (005-fix-batch-conformance FR-019 — completes a command previously recognized but unhandled), reusing the same `serverVersion` source `VERSION`'s `351` reply already uses | `371 RPL_INFO`, then `374 RPL_ENDOFINFO` |

**Contract notes**:
- `WHOIS`/`WHO` are core protocol behavior (FR-037/FR-061), like
  moderation (FR-036) and capability negotiation (FR-035) — neither is
  one of the toggleable `jircd-capabilities/*`/`jircd-server-extensions/*`
  extensions, and an administrator cannot disable either.
- The hostname field's real-vs-presented resolution (FR-038) MUST reuse
  the exact same computation `UserIdentity.presentedForm` (data-model.md)
  already uses for message hostmasks, and the exact same
  `ClientSession.realHostname` source of truth `WHOHOST` (Administration,
  below) already reads for the administrator case — neither `WHOIS` nor
  `WHO` MUST reimplement this resolution independently. Two independent
  implementations of "who gets to see the real value" is exactly the kind
  of divergence that turns into a privacy bug later.
- `WHO`'s `352 RPL_WHOREPLY` fields: `<channel-or-*> <ident>
  <presented-hostname> <server> <nickname> <status> :0 <realname>` —
  `<channel>` for the channel-scoped form, `*` otherwise; `<server>` is
  this server's own name (003-irctest-conformance-fixes FR-006 restored
  it — previously omitted per FR-021's reasoning that there being no
  federation, no *other* server name could ever appear there; restoring
  it costs nothing and improves positional-parser compatibility with
  strict tooling, since it always repeats the same known value); the
  trailing `0` is a fixed hopcount (this server is never more than zero
  hops from itself, having no server-to-server links); `<status>` is
  always `H` (no `AWAY` in this release, so never `G`) followed by `*` if
  the target holds `operator` (mirroring `313`'s visibility) and, for the
  channel-scoped form only, `@`/`+` following the exact same
  operator/voice precedence `353 RPL_NAMREPLY` already uses
  (FR-045/FR-046). `WHOIS`'s `311` still omits it — RFC 2812's `311` field
  layout (`<nick> <user> <host> * :<real name>`) has no server-name slot
  to add one to; the server name is reported via a separate `312
  RPL_WHOISSERVER` reply instead (003-irctest-conformance-fixes FR-006).
- `WHO`'s mask matching (FR-061) is nickname-only, narrower than RFC
  2812's full host/server/real-name matching — this server has no
  server-name-federation concept to match against (FR-021), and nothing
  requires matching on host or real name yet. A wildcard character
  (`*`/`?`) anywhere in the argument selects the mask form; its absence
  and a leading `#` selects the channel form; its absence and no
  leading `#` selects the exact-nickname form.
- `whoMaskEnabled` (contracts/server-configuration.md, FR-061) and
  `invisible` (FR-044) are two independent gates on the same two forms
  (mask, no-argument), checked separately — either one alone is enough
  to exclude a match, and both are bypassed entirely by administrator
  privilege. Neither ever applies to the channel-name or exact-nickname
  forms.

### Moderation (Story 5)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `KICK <channel> <nickname> [:reason]` | C→S | Sender is a channel operator (FR-013) | Removes the target from the channel; an omitted `<reason>` defaults to the kicking operator's own nickname (005-fix-batch-conformance FR-015 — the conventional Modern IRC default; previously omitted the trailing parameter entirely) | `KICK` echoed to all (former) members; `482 ERR_CHANOPRIVSNEEDED` if sender lacks privilege (FR-014) |
| `MODE <channel> <+/->m\|n` | C→S | Sender is a channel operator | Sets/clears `moderated` (`m`) or `members-only` (`n`), independently (FR-013; "Full Channel Mode Catalog" below) | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized; `472 ERR_UNKNOWNMODE` for any other flag letter (FR-043) |
| `MODE <channel>` (no flag argument) | C→S | None — no operator privilege required, a read-only query (007-bare-mode-query FR-005, mirrors `TOPIC`'s own view-vs-set split) | Reports the channel's current settings (007-bare-mode-query FR-001 through FR-004) | `324 RPL_CHANNELMODEIS` (every active `BOOLEAN`/value-carrying flag as one mode string — `bare +` if none active — `LIST`/`MEMBER`-kind flags excluded, already covered by `MODE +b`/`NAMES`), then `329 RPL_CHANNELCREATED` (the channel's creation time, FR-007/FR-008); `403 ERR_NOSUCHCHANNEL` if the channel is hidden from a non-member requester (private/secret, FR-006) or doesn't exist — previously always `472 ERR_UNKNOWNMODE`, treated as an unrecognized flag |
| `MODE <channel> <+/->v <nickname>` | C→S | Sender is a channel operator; `<nickname>` is a current member of `<channel>` | Grants (`+v`) or revokes (`-v`) `<nickname>`'s voice privilege (FR-045) — while `moderated` (`m`) is active, `<nickname>` may now send in addition to operators | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if sender isn't an operator; `401 ERR_NOSUCHNICK` if `<nickname>` isn't connected to the server at all, `441 ERR_USERNOTINCHANNEL` if it's connected but not a current member of `<channel>` (005-fix-batch-conformance FR-017 distinguishes these; previously both cases returned `441`) |
| `MODE <channel> <+/->o <nickname>` | C→S | Sender is a channel operator; `<nickname>` is a current member of `<channel>` | Grants (`+o`) or revokes (`-o`) `<nickname>`'s operator status (FR-046) — in addition to, not a replacement for, first-join-gets-operator (FR-013); `<nickname>` MAY be the sender themselves (self-revocation is permitted, even if it leaves the channel with zero operators) | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if sender isn't an operator; `401 ERR_NOSUCHNICK` if `<nickname>` isn't connected to the server at all, `441 ERR_USERNOTINCHANNEL` if it's connected but not a current member of `<channel>` (005-fix-batch-conformance FR-017 distinguishes these; previously both cases returned `441`) |
| `MODE <channel> <+/->p\|s` | C→S | Sender is a channel operator | Sets/clears `private` (`p`) or `secret` (`s`) (FR-047) — setting either one clears the other if it was active (mutually exclusive, data-model.md `Channel` validation rules); while active, hides the channel from `TOPIC`/`NAMES`/`LIST` for non-members ("Channel Operations" above) | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized; `472 ERR_UNKNOWNMODE` for any other flag letter |
| `MODE <channel> b` / `MODE <channel> +b` | C→S | `REGISTERED` session; no operator/membership requirement — querying the ban list is not itself a moderation action | Returns every currently-active ban mask on `<channel>` (FR-062) — both the bare-letter and `+`-prefixed forms are accepted (005-fix-batch-conformance FR-016; only the bare form was recognized previously) | One `367 RPL_BANLIST` per active mask, then `368 RPL_ENDOFBANLIST` — zero bans is not an error, still closes with `368` |
| `MODE <channel> +b <mask>` | C→S | Sender is a channel operator | Adds `<mask>` to the ban list, normalizing a partial mask by filling missing `user`/`host` segments with `*` (FR-062); no-op if the (normalized) mask is already present | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if sender isn't an operator; `478 ERR_BANLISTFULL` if the channel already has 100 active bans |
| `MODE <channel> -b <mask>` | C→S | Sender is a channel operator | Removes `<mask>` (after the same normalization) from the ban list; no-op if not present | `MODE` echoed to all members; `482 ERR_CHANOPRIVSNEEDED` if sender isn't an operator |
| `MODE <channel> e` / `MODE <channel> +e` | C→S | `REGISTERED` session; no operator/membership requirement to query | Returns every active ban-exception mask; bare and `+` forms query the same list | One `348 RPL_EXCEPTLIST` per entry, then `349 RPL_ENDOFEXCEPTLIST`, including for an empty list |
| `MODE <channel> I` / `MODE <channel> +I` | C→S | `REGISTERED` session; no operator/membership requirement to query | Returns every active invite-exception mask; bare and `+` forms query the same list | One `346 RPL_INVITELIST` per entry, then `347 RPL_ENDOFINVITELIST`, including for an empty list |
| `MODE <channel> +e <mask>` / `MODE <channel> +I <mask>` | C→S | Sender is a channel operator | Adds a normalized mask to the corresponding exception list; matching/normalization and RFC1459 casemapping match `b`; each list has its own 100-entry cap | Applied `MODE` changes echoed to channel members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized; `478 ERR_BANLISTFULL` if that list is full |
| `MODE <channel> -e <mask>` / `MODE <channel> -I <mask>` | C→S | Sender is a channel operator | Removes the normalized mask from the corresponding list; absent masks are a no-op | Applied `MODE` changes echoed to channel members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized |
| `MODE <channel> <+/->i` | C→S | Sender is a channel operator | Sets/clears `invite-only` (`i`) (FR-065) — while active, a `JOIN` needs a currently-held matching invitation or matching `I` mask; `I` bypasses this gate only, not `+k` or `+l` (#78) | Applied `MODE` changes echoed to channel members; `482 ERR_CHANOPRIVSNEEDED` if unauthorized; `472 ERR_UNKNOWNMODE` if a mode letter is unknown |
| `INVITE <nickname> <channel>` | C→S | `REGISTERED` session; sender is a current member of `<channel>`; `<nickname>` is a currently-connected client not already a member of `<channel>`; if `invite-only` is currently active on `<channel>`, sender must additionally be an operator (FR-065) | Records an invitation for `<nickname>` on `<channel>` (`Channel.invited`), letting that client's next `JOIN` of `<channel>` satisfy `invite-only`'s gate regardless of who issues it — consumed the moment that `JOIN` succeeds | `341 RPL_INVITING <nick> <channel>` to the sender; an `INVITE` message relayed to `<nickname>`; `442 ERR_NOTONCHANNEL` if the sender isn't a member of `<channel>` (also covers `<channel>` not existing); `401 ERR_NOSUCHNICK` if `<nickname>` isn't connected; `443 ERR_USERONCHANNEL` if `<nickname>` is already a member; `482 ERR_CHANOPRIVSNEEDED` if `invite-only` is active and the sender isn't an operator |

**Contract notes**:
- This release's channel `MODE` implements six `BOOLEAN`-kind flags —
  the two FR-013 defines (`moderated`, `members-only`), `private`/
  `secret` (FR-047), `invite-only` (FR-065), and `topic-lock` (006) — directly,
  unconditionally (never gated by an `Extension`, FR-036), plus the two
  `MEMBER`-kind flags FR-045/FR-046 define (`voice`, `operator`), two
  value-carrying flags (`channel-key`, `user-limit`), and three `LIST`
  modes (`ban-mask`, `ban-exception`, `invite-exception`). `topic-lock`
  is also implemented; see the Full Channel Mode Catalog below.
- **Every row above may be combined into a single `MODE` command**
  (FR-064): `MODE #chan +ov-b nick1 nick2 mask1` sets `+o` on `nick1`,
  `+v` on `nick2`, and removes `mask1` from the ban list, all in one
  invocation, applied left-to-right — this is RFC 2812's own `MODE`
  grammar (`*( (+/-) *modes *modeparam )`, research.md "MODE command
  grouping"), not project-specific syntax. Each parameter-consuming
  flag (`MEMBER`/`LIST`-kind) consumes the next unused parameter in
  order; `BOOLEAN`-kind flags never consume one. A single
  `ServerConfiguration.maxModesPerCommand`-worth (default `6`,
  advertised as `MODES`, FR-055) of parameter-consuming flags is
  applied per command — flags beyond that limit are silently not
  applied, with no error reply (see research.md for why this is a
  deliberate exception to this project's usual explicit-rejection
  posture); flags up to an unknown flag, a target that isn't a current
  member, or a missing parameter are applied, and processing stops
  there (`472`/`441`/`461` respectively) — a later flag's failure never
  undoes an earlier flag's already-applied change within the same
  command. The `MODE` echo always reflects only what was actually
  applied.
- `ban-mask` gates `{SEND, JOIN}`; `ban-exception` (`e`) exempts a match
  against either presented or real identity from that ban for both
  actions. A client already in the channel whose
  presented identity (FR-030/FR-031) **or** real hostname/IP (FR-032)
  matches an active ban without an `e` match has their `PRIVMSG`/`NOTICE` to that channel
  rejected with `404 ERR_CANNOTSENDTOCHAN` — muted, not removed from the
  channel. This matches the modern IRC client protocol specification's
  own definition of `ban-mask` ("banned from joining or speaking in the
  channel"), not just RFC 1459/2811's older, `JOIN`-only wording; a
  client not currently in the channel whose presented identity or real
  hostname/IP matches without an `e` match has `JOIN` rejected with `474
  ERR_BANNEDFROMCHAN`. `SAJOIN` (FR-057) bypasses this the same way it
  bypasses any other `JOIN`-gating flag. Checking both identities keeps
  a ban resistant to evasion via `cloak` (FR-031): matching only the
  presented form would let a client dodge a mask targeting their real,
  underlying network identity simply by having a different value
  displayed. This never exposes a real hostname/IP to the operator who
  wrote the mask — they still only ever see a match/no-match outcome,
  the same opaque signal any ban already produces; FR-032's restriction
  on *viewing* real hostnames/IPs is unrelated to and unaffected by
  this.
- `invite-only` (`i`, FR-065) and `invite-exception` (`I`) affect only
  the invite gate: a matching `I` mask or `INVITE`-granted invitation
  satisfies `+i`, while `ban-mask` is checked independently and `e`
  exempts only its own ban check. `I` never bypasses `+k` or `+l`.
  `SAJOIN` (FR-057) bypasses all JOIN gates.
- `INVITE <nickname> <channel>` — any current member of `<channel>`
  may issue one; only when `invite-only` is currently active does the
  sender additionally need to be an operator, matching modern IRC
  client protocol documentation's own wording (not RFC 2812's
  narrower, operator-only-always reading). Issuing an invitation on a
  channel that isn't (or isn't yet) invite-only still succeeds and is
  still recorded — it has no observable effect unless `invite-only` is
  active by the time the target attempts to `JOIN`. `341 RPL_INVITING`
  uses modern IRC client protocol documentation's `<client> <nick>
  <channel>` parameter order, not RFC 2812's own, older `<channel>
  <nick>` ordering — the same "modern documentation wins where it
  disagrees with a frozen RFC" precedent `ban-mask`'s dual-gating
  already established (research.md "Channel invitations").
- `operator` status has two independent paths to acquiring it in this
  release: first-join-gets-operator (FR-013, how a channel's very first
  operator is established) and `MODE +o` from an existing operator
  (FR-046, how it subsequently spreads). Neither supersedes the other —
  first-join only ever applies at channel creation, and `MODE +o` only
  ever applies to an already-existing channel with at least one current
  operator to issue it.
- `moderated` (`+m`) means "operators **and** voiced members may send,"
  matching classic IRC's full `+m` semantic — not "operators only." A
  member who is voiced but not an operator may send while `moderated` is
  active; an operator never needs to also be voiced.
- Unlike the "recognized only" commands elsewhere in this catalog, the
  boundary above isn't meant to be permanent for `BOOLEAN`-kind flags:
  FR-043 requires the `MODE` mechanism itself to support an optional
  server extension contributing additional `BOOLEAN` flags later
  (research.md "Channel/user mode extensibility", data-model.md
  `ChannelMode`) without a core protocol change — no extension does so in
  this release; every `BOOLEAN` flag implemented so far, `invite-only`
  included, turned out to be `CORE`-defined instead (research.md
  "Channel invitations" — "Core vs. extension, revisited"). `VALUE`-kind
  flags (channel-key, user-limit) remain a narrower promise: cataloged
  below, but `Channel`'s shape can't hold one yet, so adding one is a
  bigger change than adding another `BOOLEAN` flag would be — `ban-mask`
  (FR-062) already crossed that bar for `LIST`-kind flags specifically,
  with its own purpose-built `Channel.bans` storage; `invite-only`
  (FR-065) needed the identical treatment for its own `Channel.invited`
  bookkeeping, despite being `BOOLEAN`-kind itself.
- User-level `MODE` is implemented for exactly two flags this release
  (`operator`/`o`, `invisible`/`i`, FR-044/FR-061) — see "User Mode"
  below — using the same extension-contribution mechanism as channel
  `MODE` (FR-044), narrower
  in scope than channel `MODE` in the ways that section describes.

### User Mode

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `MODE <nickname>` | C→S | `REGISTERED` session; `<nickname>` is the sender's own current nickname | Returns the sender's currently-set user modes | `221 RPL_UMODEIS`; `502 ERR_USERSDONTMATCH` if `<nickname>` isn't the sender's own |
| `MODE <nickname> +o` | C→S | `REGISTERED` session; `<nickname>` is the sender's own current nickname | No-op if the sender already holds `operator`; otherwise rejected — `+o` can only be acquired via `OPER` (FR-034), never set directly (FR-044) | `481 ERR_NOPRIVILEGES` if the sender does not already hold `operator`; `502 ERR_USERSDONTMATCH` if `<nickname>` isn't the sender's own |
| `MODE <nickname> -o` | C→S | `REGISTERED` session; `<nickname>` is the sender's own current nickname | Clears `operator` from the sender's user modes and clears `ClientSession.administratorPrivilege` in the same act (FR-044) — a full revocation, not just a display change. No-op if the sender doesn't currently hold it | `MODE` confirmation echoed to the sender; `502 ERR_USERSDONTMATCH` if `<nickname>` isn't the sender's own |
| `MODE <nickname> +i` / `-i` | C→S | `REGISTERED` session; `<nickname>` is the sender's own current nickname | Sets or clears `invisible` on the sender's user modes, always, with no privilege check (FR-044/FR-061) — affects visibility in `WHO`'s search forms (see "User Queries" above) | `MODE` confirmation echoed to the sender; `502 ERR_USERSDONTMATCH` if `<nickname>` isn't the sender's own |

**Contract notes**:
- User `MODE` is self-only in this release: a `<nickname>` other than
  the sender's own current nickname is always `502 ERR_USERSDONTMATCH`,
  for query or set alike — mirroring `SAMODE`'s self-only scope
  (FR-058) — regardless of the sender's privilege level. There is no
  administrator override for looking up or changing a *different*
  session's user modes.
- A mode letter other than `o`/`i` is `501 ERR_UMODEUNKNOWNFLAG` — the
  user-mode counterpart to channel `MODE`'s `472 ERR_UNKNOWNMODE`.
- Like channel `MODE`, `MODE <self> +oi` (or any other combination) is
  valid — the underlying grammar is the same (`ModeStringParser`,
  research.md "MODE command grouping"), reused here even though no
  user-mode flag consumes a parameter, so `ServerConfiguration
  .maxModesPerCommand` (FR-064) has nothing to limit in this context.
  Processing still stops at the first flag that fails (e.g., `+oi` from
  a non-privileged session applies `+i` then stops at `+o` with `481`,
  never reaching anything after it in the same modestring), the same
  left-to-right, non-atomic model channel `MODE` uses.
- `+o`/`-o` here is a narrower surface than it looks: the only way to
  transition from not holding `operator` to holding it is `OPER`
  (FR-034) succeeding, never `MODE` — `MODE <self> +o` can only ever
  confirm a state the sender already has (a no-op) or fail (`481`). Only
  `-o` can actually change state, and only in the revoking direction.
  `+i`/`-i` has no such asymmetry — either direction always succeeds,
  the same way an operator may always clear their own `+o`, just without
  needing to already hold anything first.
- `WHOIS`'s `313 RPL_WHOISOPERATOR` ("User Queries" above) is the
  *other* place `operator` status surfaces — visible to any client, not
  just the operator's own session, unlike `221 RPL_UMODEIS` which is
  self-only per the self-only restriction above. `invisible` has no
  `WHOIS` equivalent — it affects `WHO` only (FR-061), not `WHOIS`,
  which remains ungated by it (research.md "WHO and invisibility").
- `MODE` is one wire command with two independent targets and two
  independent handlers, not two commands that happen to share a name:
  the server determines which applies by inspecting whether `<target>`
  matches the channel-name prefix (`CHANTYPES`, `#`, FR-048) — a
  channel-shaped target routes to channel `MODE` ("Moderation" above), a
  nickname-shaped target routes to this section. A client never has to
  disambiguate explicitly; the target itself already does.

### Administration (Story 6)

| Command | Direction | Preconditions | Effect | Replies |
|---|---|---|---|---|
| `OPER <username> <password>` | C→S | `REGISTERED` session; TLS when `operRequireTls` is true | Checks the shared source bucket before credential verification. Below threshold, verifies `administratorCredentials`; success grants administrator privilege and clears that bucket's failures. Failure increments its count in a fixed `operLockoutSeconds` window from the first failure. Buckets span reconnects/usernames: normalized IPv4 /32 or configured IPv6 prefix. | `381 RPL_YOUREOPER` on success; `464 ERR_PASSWDMISMATCH` on failure (also logged). Reaching `operFailureThreshold` or attempting OPER while locked returns `464` and runs FR-017 disconnect cleanup without another credential check. Plaintext when TLS is required returns `491 ERR_NOOPERHOST` (`OPER requires TLS; reconnect using TLS`) without verification/counting; an already locked source still receives `464` and disconnects. |
| `EXTENSION <ENABLE\|DISABLE> <extension-id>` | C→S | Sender holds administrator privilege | Toggles the named `CapabilityExtension` or `ServerExtension`'s state, in effect immediately for all clients (FR-011, FR-032) | Confirmation notice on success; `481 ERR_NOPRIVILEGES` if sender lacks administrator privilege (FR-033); `421 ERR_UNKNOWNCOMMAND`-style error naming the extension id if it doesn't exist |
| `WHOHOST <nickname>` | C→S | Sender holds administrator privilege | Returns the target's real, unobfuscated hostname/IP regardless of any active cloaking (FR-031, FR-032) | Notice containing the real hostname on success; `481 ERR_NOPRIVILEGES` if unauthorized; standard "no such nickname" error if the target isn't connected |
| `REHASH` | C→S | Sender holds administrator privilege | Manually re-reads and re-validates the Server Configuration file and reconciles it against live state — the in-band equivalent of a `SIGHUP` (research.md "Configuration reload mechanism", contracts/server-configuration.md "Live reload") | `382 RPL_REHASHING` on success; on validation failure, the same specific, actionable error startup validation would report (FR-012, SC-008), and the previously-active configuration remains untouched; `481 ERR_NOPRIVILEGES` if unauthorized |
| `SAJOIN <channel>` | C→S | Sender holds administrator privilege | Joins the sender's own session to `channel` via the same create-or-join path as `JOIN` (FR-003), but skips the `JOIN`-gate check point (FR-043's `gates` mechanism) entirely — bypassing both currently-defined `JOIN`-gating flags, `ban-mask` (FR-062) and `invite-only` (FR-065), the same way it would bypass any future one (FR-057, research.md "Administrator channel override") | Same success replies as `JOIN` (`JOIN` echoed to all members; `353`/`366` to the joiner); `481 ERR_NOPRIVILEGES` if sender lacks administrator privilege; `476 ERR_BADCHANMASK` if `channel` violates the Channel Name Grammar (grammar is NOT bypassed) |
| `SAMODE <channel> <+o\|-o>` | C→S | Sender holds administrator privilege; sender is currently a member of `channel` | Adds (`+o`) or removes (`-o`) the sender's own nickname from `channel`'s `operators`, bypassing FR-046's "sender must already be an operator" precondition — self-targeting only (FR-058, research.md "Administrator channel override") | `MODE` change echoed to all members on success, the same as an operator-granted `MODE +o`/`-o`; `481 ERR_NOPRIVILEGES` if sender lacks administrator privilege; `442 ERR_NOTONCHANNEL` if sender isn't currently a member |

**Contract notes**:
- `OPER` privilege is server-wide and independent of any channel-operator
  status (FR-033) — holding it does not grant channel-operator privileges
  in channels the administrator hasn't joined, and vice versa.
- These six commands are the FR-032/FR-057/FR-058 minimum; additional
  administrative commands MAY be added by future extensions without
  changing this contract.
- OPER lockouts are shared across connections and usernames for a source bucket.
  Success below threshold clears it; expiration permits authentication again.
  Attempts do not extend the fixed deadline. Entries expire lazily and tracking
  is capped at 10,000 sources. At capacity, new sources use per-session failure
  counting: passwords are still verified, success clears the session counter,
  and reaching the threshold disconnects. Existing lockouts are preserved. Source
  keys use the accepted socket address independently of the display hostname;
  an absent address uses a per-connection key. Skipped checks during lockout log
  `OPER source locked out`, while verified failures log `invalid OPER credentials`.
  `REHASH`/`SIGHUP` apply the current TLS requirement and threshold; existing window
  deadlines retain their original duration. Server restart clears the tracker.
- Command names (`EXTENSION`, `WHOHOST`, `REHASH`, `SAJOIN`, `SAMODE`)
  are illustrative for this plan; the tasks phase may finalize different
  verbs as long as the preconditions/effects/replies contract above
  holds.
- `SAJOIN`/`SAMODE` are deliberately separate, explicit commands, not a
  silent privilege bypass folded into ordinary `JOIN`/`MODE` — an
  administrator's own regular `JOIN`/`MODE` is checked exactly like any
  other client's (research.md "Administrator channel override" —
  Alternatives considered). `SAMODE` grants/revokes operator status on
  the sender only; it is not a general mode-override command and MUST
  NOT accept a different target nickname or an arbitrary mode string —
  granting operator status to someone *else* remains exclusively
  FR-046's operator-to-operator mechanism. There is deliberately no
  `SABAN`: administrator-issued channel bans were considered and
  withdrawn in favor of making every operator-issued ban resistant to
  cloak evasion instead (FR-062, "Channel Operations" above, research.md
  "Administrator channel override" — Alternatives considered).
- `REHASH` and `EXTENSION` are not interchangeable: `REHASH` reloads the
  whole configuration file (extensions, rate limit, listeners,
  administrator credentials) and requires the file to be valid;
  `EXTENSION` changes a single extension's state directly in memory and
  never touches the file (contracts/server-configuration.md "Path
  equivalence").
- **Self-lockout**: `EXTENSION DISABLE admin`, issued by a privileged
  session through the `admin` extension itself, MUST succeed (an
  administrator is allowed to disable in-band administration) and takes
  effect immediately — the issuing session's own subsequent admin commands
  are then rejected with `481 ERR_NOPRIVILEGES` like any other
  non-privileged session, same as `contracts/server-configuration.md`'s
  "path equivalence" note. This is not an error state: the
  configuration-file path (Story 4) remains available to re-enable
  `admin` from outside the protocol.

## Full Command Catalog (`jircd-protocol` — Wire-Protocol Recognition)

Every command RFC 1459/2812 defines, plus the IRCv3 framework commands
this project uses. `jircd-protocol`'s parser and `Command` model
(data-model.md-adjacent, defined in `jircd-protocol`) MUST represent all
of these — parsing a line into a command name + parameters is generic
regardless of which commands `jircd-core` has handlers for, and a future
client library needs to recognize server output using the same commands
regardless of *this* server's feature scope. "Status" says whether
`jircd-core` has an actual handler *in this release*; "Recognized only"
commands still parse correctly and, if a client sends one, get `421
ERR_UNKNOWNCOMMAND` — the same reply an actually-unrecognized word would
get, since the wire protocol having a name for something doesn't obligate
any given server to implement it.

| Command | RFC 2812 § | Status in this release |
|---|---|---|
| `PASS` | 3.1.1 | **Implemented** (issue #77) — valid before registration, where it is accepted and ignored (this server has no connection password; the value is never stored or logged); `462 ERR_ALREADYREGISTRED` once registered; `461` without a parameter |
| `NICK` | 3.1.2 | **Implemented** — see "Connection Registration" above |
| `USER` | 3.1.3 | **Implemented** — see "Connection Registration" above |
| `OPER` | 3.1.4 | **Implemented**, but with this project's own privilege model, not RFC 2812's O-line concept — see "Administration" above |
| `MODE` (user) | 3.1.5 | **Implemented**, for exactly two flags (`operator`/`o`, `invisible`/`i`) — see "User Mode" above (FR-044/FR-061) |
| `SERVICE` | 3.1.6 | Recognized only — this server has no services-framework concept |
| `QUIT` | 3.1.7 | **Implemented** — see "Connection Registration" above |
| `SQUIT` | 3.1.8 | Recognized only — server-to-server command; this release has no server-to-server interface at all (FR-021) |
| `JOIN` | 3.2.1 | **Implemented** — see "Channel Operations" above |
| `PART` | 3.2.2 | **Implemented** — see "Channel Operations" above |
| `MODE` (channel) | 3.2.3 | **Implemented** — see "Moderation" above; every core flag in the Full Channel Mode Catalog below is implemented (006-complete-core-protocol completed `t`/`l`/`k`), and a bare mode query (007-bare-mode-query FR-001 through FR-006) now returns `324 RPL_CHANNELMODEIS`/`329 RPL_CHANNELCREATED` instead of `472 ERR_UNKNOWNMODE` |
| `TOPIC` | 3.2.4 | **Implemented** — see "Channel Operations" above |
| `NAMES` | 3.2.5 | **Implemented** — see "Channel Operations" above; a bare, argument-less `NAMES` (006-complete-core-protocol FR-010) lists every channel visible to the requester, applying the same private/secret visibility rule as the single-channel form, closed by one `366` targeted at `*`. An invalid or nonexistent single channel name gets only the closing `366`, never an error (006-complete-core-protocol Polish — RFC1459 §4.2.5/RFC2812 §3.2.5's own "there is no error reply for bad channel names," a pre-existing gap fixed alongside the bare form) |
| `LIST` | 3.2.6 | **Implemented** — see "Channel Operations" above |
| `INVITE` | 3.2.7 | **Implemented** — see "Moderation" above (FR-065); paired with the `invite-only` (`i`) flag in the Full Channel Mode Catalog below, the same way both were previously Reserved/Recognized-only together. Inviting to a channel name that doesn't exist anywhere on the server succeeds (006-complete-core-protocol FR-013 — RFC 2812 §3.2.7's own error set has no not-found-channel case) as a one-time notification only, never creating the channel; an existing channel the inviter isn't a member of is still rejected with `442` |
| `KICK` | 3.2.8 | **Implemented** — see "Moderation" above |
| `PRIVMSG` | 3.3.1 | **Implemented** — see "Channel Operations" above |
| `NOTICE` | 3.3.2 | **Implemented** — see "Channel Operations" above |
| `MOTD` | 3.4.1 | **Implemented** (issue #77) — `375`, one `372` per line of the `motdPath` file, `376`; `422` when unset or unusable. The file is re-read when it changes; reload picks up a new `motdPath` |
| `LUSERS` | 3.4.2 | **Implemented** — `251`, `252`, `253`, `254`, `255`, `265`, `266` in that order (issue #76); see the `LUSERS` row under "Information Queries" |
| `VERSION` | 3.4.3 | Recognized only |
| `STATS` | 3.4.4 | **Implemented** (issue #77) — `STATS u` sends `242 RPL_STATSUPTIME`, then every query ends with `219 RPL_ENDOFSTATS` carrying the letter; other letters send only `219`; no letter is `461`; a server name other than this one is `402` |
| `LINKS` | 3.4.5 | Recognized only — server-to-server; not applicable to a standalone release (FR-021) |
| `TIME` | 3.4.6 | Recognized only |
| `CONNECT` | 3.4.7 | Recognized only — server-to-server; not applicable to a standalone release (FR-021) |
| `TRACE` | 3.4.8 | Recognized only |
| `ADMIN` | 3.4.9 | **Implemented** (issue #77) — `256`, `257`, `258`, `259` from `adminName`, `adminLocation`, `adminEmail` (`-` for an unset one); `423 ERR_NOADMININFO` when none is set; a server name other than this one is `402` |
| `INFO` | 3.4.10 | **Implemented** (005-fix-batch-conformance FR-019) — a short, fixed server-information burst (`371 RPL_INFO`, then `374 RPL_ENDOFINFO`), reusing the same `serverVersion` source `VERSION`'s `351` reply already uses |
| `SERVLIST` | 3.5.1 | Recognized only — this server has no services-framework concept |
| `SQUERY` | 3.5.2 | Recognized only |
| `WHO` | 3.6.1 | **Implemented** — see "User Queries" above (FR-061) |
| `WHOIS` | 3.6.2 | **Implemented** — see "User Queries" above |
| `WHOWAS` | 3.6.3 | Recognized only |
| `KILL` | 3.7.1 | Recognized only — no forced-disconnect admin command in this release (an administrator can approximate this via a future `EXTENSION`-adjacent command, but none exists yet) |
| `PING` | 3.7.2 | **Implemented** — see "Connection Keep-Alive" above |
| `PONG` | 3.7.3 | **Implemented** — see "Connection Keep-Alive" above |
| `ERROR` | 3.7.4 | **Implemented** — see "Connection Keep-Alive" above |
| `AWAY` | 4.1 | Recognized only — no away-status feature in this release |
| `REHASH` | 4.2 | **Implemented** — see "Administration" above (this project's version is IRC-command-only, not also a `DIE`/`RESTART`-style local-admin command) |
| `DIE` | 4.3 | Recognized only — no remote-shutdown admin command in this release |
| `RESTART` | 4.4 | Recognized only |
| `SUMMON` | 4.5 | Recognized only — this legacy command (notify a local Unix user) has no meaning for this server |
| `USERS` | 4.6 | Recognized only |
| `WALLOPS` | 4.7 | Recognized only — no server-wide operator broadcast in this release |
| `USERHOST` | 4.8 | **Implemented** (005-fix-batch-conformance FR-018) — host information for up to 5 nicknames (`302 RPL_USERHOST`), one `nick[*]=[+\|-]user@host` entry per found nickname; the host follows the same FR-038 three-tier resolution `WHOIS`/`WHO`/`WHOWAS` already use |
| `ISON` | 4.9 | **Implemented** (issue #77) — `303 RPL_ISON` with the registered nicknames among those asked (case-folded lookup, presented form, no duplicates); `461` without a parameter |
| `HELP`, `HELPOP` | Modern IRC | **Implemented** (issue #77) — a short built-in topic list: `704`, `705`…, `706` with the topic as subject (`index` for a bare `HELP`), `524 ERR_HELPNOTFOUND` for an unknown topic. Both commands behave identically |
| `CAP` | IRCv3 | **Implemented** — see "Connection Registration" above |
| `AUTHENTICATE` | IRCv3 (SASL) | Recognized only — deferred with Story 3 (FR-009/FR-010); MUST NOT be offered as a capability (contracts note above) |
| `TAGMSG` | IRCv3 | **Implemented** by the `message-tags` module (002-extended-irc-commands FR-020–FR-023; ownership per 014-capability-plugin-spi) — a tag-only message that reuses core's target resolution and send gates and reaches only recipients that negotiated `message-tags`. Without that module the command is unknown (`421`) |
| `EXTENSION` | This project | **Implemented** — see "Administration" above; not an RFC 1459/2812 command |
| `WHOHOST` | This project | **Implemented** — see "Administration" above; not an RFC 1459/2812 command |
| `SAJOIN` | Common IRCd convention | **Implemented** — see "Administration" above; not an RFC 1459/2812 command, but a well-known real-world administrator-override convention (research.md "Administrator channel override"), reused rather than inventing a project-specific name |
| `SAMODE` | Common IRCd convention | **Implemented**, self-op-only form — see "Administration" above; not an RFC 1459/2812 command, and narrower than some real networks' general-target `SAMODE` (FR-058 scopes it to self-targeting only) |

**Contract notes**:
- A "Recognized only" command still parses without error at the
  wire-protocol layer (correct command name, correct generic
  `COMMAND [params] [:trailing]` framing) — `jircd-core` is the layer that
  decides it has no handler and replies `421`. This split is what lets a
  future client library depend on `jircd-protocol` alone and get a
  complete, standard-compliant parser, independent of which commands any
  particular server (this one or another) has chosen to implement.
- Numeric replies associated with "Recognized only" commands (e.g.,
  `317`/`319` — the idle-time/channel-list parts of a fuller `WHOIS` reply
  this release doesn't implement; `312`/`313` are sent, see "User Queries"
  above) are still defined in the full numeric catalog
  (irc-numeric-replies.md) for the same reason — a client library needs to
  be able to parse them from *any* server's responses, not only ones this
  server currently sends.
- This table does not change what any FR requires the server to *do* —
  it documents what the wire-protocol layer can *parse and represent*.
  Moving a "Recognized only" command to "Implemented" in a future release
  is a `jircd-core`/extension change, not a `jircd-protocol` change.

## Full Channel Mode Catalog (`jircd-protocol` — Wire-Protocol Recognition)

Unlike commands and numerics, an individual `MODE` flag isn't its own
grammar element — `jircd-protocol` parses any `<+/-letter>` generically,
so nothing here is about wire-level parsing. This table exists for the
reason FR-043 requires the `MODE` mechanism to be extension-friendly
(research.md "Channel/user mode extensibility"): it gives a future core
release or `ServerExtension` a canonical `id`/`kind` to align a new flag
with, instead of each independently reinventing one for the same RFC
2811 concept — and it makes explicit which flags this release's
`Channel.activeModes: Set<ChannelMode>` (data-model.md) shape can even
represent.

| Flag | RFC 2811 §4 | `id` | `kind` | `gates` | Status |
|---|---|---|---|---|---|
| `n` | 4.2.5 | `members-only` | `BOOLEAN` | `SEND` | **Implemented** (`CORE`, FR-013) |
| `m` | 4.2.6 | `moderated` | `BOOLEAN` | `SEND` | **Implemented** (`CORE`, FR-013) |
| `p` | 4.2.1 | `private` | `BOOLEAN` | `DISCOVER` | **Implemented** (`CORE`, FR-047) — see "Moderation" above; treated identically to `secret` in this release (mutually exclusive with it, data-model.md `Channel` validation rules), not the softer "listed but obscured" variant some historical networks gave `private` alone |
| `s` | 4.2.2 | `secret` | `BOOLEAN` | `DISCOVER` | **Implemented** (`CORE`, FR-047) — see "Moderation" above |
| `i` | 4.2.3 | `invite-only` | `BOOLEAN` | `JOIN` | **Implemented** (`CORE`, FR-065) — see "Moderation" above; state lives in `Channel.activeModes` like every other `BOOLEAN` flag, with its own dedicated bookkeeping field, `Channel.invited` (data-model.md), for which nicknames currently hold a usable invitation — paired with the `INVITE` command (above), matching RFC 2812 §3.2.7/§4.2.3's own treatment of the two as one feature |
| `t` | 4.2.4 | `topic-lock` | `BOOLEAN` | — | **Implemented** (`CORE`, 006-complete-core-protocol FR-007 through FR-009) — toggleable; `TOPIC`-setting requires operator privilege only while active, any member may set it otherwise; consulted directly by `TopicCommandHandler`, not through `gates` (topic-setting isn't a modeled `GateAction`) |
| `l` | 4.2.7 | `user-limit` | `VALUE_SET_ONLY` | `JOIN` | **Implemented** (`CORE`, 006-complete-core-protocol FR-001 through FR-003) — state lives in `Channel.memberLimit`, not `activeModes`; a parameter is present only when setting, per its `CHANMODES` category |
| `k` | 4.2.9 | `channel-key` | `VALUE_ALWAYS` | `JOIN` | **Implemented** (`CORE`, 006-complete-core-protocol FR-004 through FR-006) — state lives in `Channel.key`, not `activeModes`; a parameter is present on both setting and unsetting, per its `CHANMODES` category — the removed value on `-k` is never checked against the current key. A `+k` value that's empty or contains a space is silently ignored rather than applied (never checked back via `JOIN`'s own space-delimited key grammar) — one of the outcomes `irctest`'s own `testKeyValidation` explicitly accepts |
| `b` | 4.2.8 | `ban-mask` | `LIST` | `SEND`, `JOIN` | **Implemented** (`CORE`, FR-062) — see "Moderation" above; state lives in `Channel.bans` (data-model.md `BanEntry`), not `activeModes`. `gates: {SEND, JOIN}` is wider than RFC 2811's `JOIN`-only wording, but matches the modern IRC client protocol specification's own definition of `ban-mask` (masks "banned from joining or speaking in the channel") — an already-present matching member is muted (`SEND` blocked, `404`), not just future joiners blocked (`JOIN` blocked, `474`); this is the current spec-aligned reading, not an RFC-compliance gap |
| `e` | 4.3.1 | `ban-exception` | `LIST` | — | **Implemented** (#78) — empty gates (exempts rather than restricts); same normalized mask representation and casemapping as `b`; either presented or real identity match exempts `b` on JOIN and channel SEND. Own 100-entry cap. |
| `I` | 4.3.2 | `invite-exception` | `LIST` | — | **Implemented** (#78) — empty gates (exempts rather than restricts); same normalized mask representation and casemapping as `b`; bypasses `i` only, never `k` or `l`. Own 100-entry cap. |
| `o` | 4.1 | `operator` | `MEMBER` | — | **Implemented** (`CORE`, FR-013/FR-046) — see "Moderation" above; state lives in `Channel.operators` (data-model.md), not `activeModes`; first-join-gets-operator (FR-013) is the only path to a channel's *first* operator, `MODE +o`/`-o` (FR-046) is how it spreads afterward |
| `v` | 4.1 | `voice` | `MEMBER` | — | **Implemented** (`CORE`, FR-045) — see "Moderation" above; state lives in `Channel.voiced` (data-model.md), not `activeModes` |

**Contract notes**:
- Every flag above is `Status: Reserved` except the seven this release
  implements — "Reserved" here means the identical thing it means in the
  Full Numeric Catalog (irc-numeric-replies.md): a canonical `id` exists
  for future use, but nothing in this release emits or enforces it.
- `private`/`secret`'s `DISCOVER` gate validates that `ChannelMode.gates`
  generalizes beyond a simple permit/deny of an action: its failure mode
  is deliberately "respond as if the channel doesn't exist," not a
  distinguishable error, for the reason FR-047 states (research.md
  "Channel/user mode extensibility"). `moderated`/`members-only`/
  `ban-mask`'s `SEND` gate, `ban-mask`'s `JOIN` gate, and `private`/
  `secret`'s `DISCOVER` gate now cover this release's two different
  failure-mode conventions — a permission-style error (`404`/`474`) for
  `SEND`/`JOIN` failures, "as if it doesn't exist" for `DISCOVER`
  failures. A future `JOIN`-gating flag (e.g., invite-only) would follow
  `ban-mask`'s precedent: its own explicit error, distinct from
  `DISCOVER`'s hidden-failure convention — its failure is a permission
  problem the requester should be told about, not something to hide.
- The `gates` column validated FR-043's extensibility promise against a
  concrete future case once already, hypothetically (research.md
  "Channel/user mode extensibility", "Validating the extensibility
  promise against a future `JOIN`-gating flag") — `ban-mask` (FR-062) is
  that promise's first *real* fulfillment: a flag gating `JOIN` (and
  `SEND` too, wider than the hypothetical case considered) that required
  no change to `JoinCommandHandler`'s or `MessageCommandHandler`'s own
  shape to add, only a `ChannelMode` catalog entry and the flag's own
  matching logic — confirming the mechanism holds for a real case, not
  just a plausible-sounding one. `invite-only` remains a still-
  unimplemented but structurally-identical case: a future extension
  defining it would define a `ChannelMode` with `gates: {JOIN}` and
  register its own `JOIN`-attempt hook, the same shape `ban-mask` uses,
  and would also typically claim the wire-recognized-but-unimplemented
  `INVITE` command (3.2.7, Full Command Catalog above) via the same
  extension-registers-its-own-command mechanism `admin`/`cloak` already
  use (research.md "Extension system") — nothing new required there
  either.
- `operator` and `voice` share `kind: MEMBER` and, as of FR-046, both
  have a `MODE`-based mutator, following the identical pattern: resolve
  the target to a current member (`441` if not), operator-gate the
  request (`482` if not), then add/remove from the relevant dedicated
  `Channel` field. `operator` additionally has the first-join path
  `voice` never had — it's the only way a channel's very first operator
  is ever established, since `MODE +o` always requires an existing
  operator to issue it.
- `topic-lock` is now a real, toggleable flag (006-complete-core-protocol
  FR-007 through FR-009) — `TopicCommandHandler`'s topic-set path checks
  `activeModes` for it directly, only requiring operator privilege while
  it's active; a channel defaults to `-t` (any member may set the topic)
  the same way a freshly-created channel defaults to every other
  `BOOLEAN` flag being off.
- `user-limit` and `channel-key` are now implemented
  (006-complete-core-protocol FR-001 through FR-006), the first two
  `VALUE`-kind modes this release has — split into `VALUE_SET_ONLY`
  (`user-limit`: a parameter only when setting) and `VALUE_ALWAYS`
  (`channel-key`: a parameter on both setting and unsetting), matching
  `SupportedFeatures.formatChanModes()`'s own `CHANMODES` category split.
  A pending invitation (`Channel.invited`) exempts a join from both, the
  same exemption `invite-only` already grants — peeked once per join
  attempt and consumed at most once, not once per gate a single
  invitation happens to satisfy (006-complete-core-protocol research.md
  "Story 1").

## Full User Mode Catalog (`jircd-protocol` — Wire-Protocol Recognition)

The `UserMode` counterpart to the Full Channel Mode Catalog above
(data-model.md `UserMode`, research.md "User mode: `operator`") — same
purpose (a canonical `id` for a future core release or `ServerExtension`
to align with), same "Reserved means recognized but not enforced"
convention, and the same namespace-independence note: a `flag` letter
here MAY coincide with a `ChannelMode` flag (as `o` does) without
conflict, since `MODE`'s target (a nickname vs. a channel name) already
disambiguates which catalog applies.

| Flag | RFC 2812 §3.1.5/§4.1 | `id` | Status |
|---|---|---|---|
| `o` | 3.1.5 | `operator` | **Implemented** (`CORE`, FR-034/FR-044) — see "User Mode" above; set only via `OPER` (FR-034), self-clearable via `MODE -o`, never directly settable via `MODE +o` |
| `i` | 3.1.5 | `invisible` | **Implemented** (`CORE`, `clientSettable: true`, FR-044/FR-061) — see "User Mode" and "User Queries" above; affects `WHO`'s exact-nickname and mask/no-argument forms only — `WHOIS`/`NAMES`/`LIST`/`WHO`'s channel-scoped form remain unaffected by it, deliberately |
| `w` | 3.1.5 | `wallops` | Reserved — pairs with the wire-recognized-but-unimplemented `WALLOPS` command (Full Command Catalog above); no server-wide operator broadcast exists in this release |
| `s` | 3.1.5 | `server-notices` | Reserved — no server-notice stream exists in this release; distinct from the `secret` *channel* mode, which also uses `s` in its own, independent namespace |
| `O` | not in RFC 2812; classic IRC convention | `local-operator` | Reserved — this project has no "local vs. global operator" distinction (`administratorPrivilege` is a single, server-wide level, FR-033); cataloged for completeness since real clients may still send it |

**Contract notes**:
- Every flag above is `Status: Reserved` except `o` and `i`, the only
  two this release implements or enforces.
- Unlike the Full Channel Mode Catalog, no `kind`/`gates` columns appear
  here — `UserMode` doesn't have them (research.md "User mode:
  `operator`" — Alternatives considered explains why not: neither of
  this release's two flags needs to gate a second command the way
  `ChannelMode` flags gate `SEND`/`JOIN`/`DISCOVER`; `UserMode` does have
  a `clientSettable` field, unlike `ChannelMode`, capturing the one
  per-flag distinction this release's mechanism actually needs — see
  data-model.md `UserMode`).
- A client sending `MODE <self> +w`/`+s`/`+O` (or any other Reserved
  flag) receives `501 ERR_UMODEUNKNOWNFLAG`, the same as any
  genuinely unrecognized letter — "Reserved" is a documentation
  convention for this catalog, not a wire-visible distinction from
  "unrecognized."

## Explicitly Out of Scope for This Plan

This section is about **server behavior scope** (what `jircd-core` and
its extensions implement) — distinct from the Full Command Catalog above,
which is about **wire-protocol recognition** and includes all of these by
name.

- `AUTHENTICATE` / SASL exchange (Story 3, FR-009/FR-010) — deferred.
- Any `NickServ`/`ChanServ`-style registration command (FR-023/FR-024/
  FR-026/FR-027) — deferred.
- Server-to-server commands (`SQUIT`, `LINKS`, `CONNECT`, federation
  generally — FR-021/FR-022/FR-028/FR-029) — deferred; this server has no
  server-to-server interface at all in this release, though their command
  grammar is still recognized per the Full Command Catalog above.
