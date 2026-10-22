# Contract: Server-to-Client Numeric Replies

Like [irc-protocol-commands.md](./irc-protocol-commands.md), this contract
has two layers:

1. **Wire-protocol recognition** (`jircd-protocol`) — the complete RFC
   1459/2812 numeric reply catalog, plus `417 ERR_INPUTTOOLONG` — the one
   deliberate exception to "RFC 1459/2812 only," included because it's a
   widely-adopted de facto standard filling an otherwise-unused numeric
   gap in the RFC's own numbering (415→421), purpose-built for exactly
   the line-length limit this project's `message-tags` support requires
   (FR-049) — the same category of justified addition IRCv3's `CAP`
   numerics already are. `jircd-protocol`'s `NumericReply` model MUST
   represent all of these, for the same reason its command model covers
   the full command set: a future client library needs to parse
   `317 RPL_WHOISIDLE` from *any* server, not only one that happens to
   implement the idle-time part of `WHOIS`.
2. **Server behavior** (`jircd-core` and extensions) — only the numerics
   marked **Used** below are actually sent by this release. Every other
   numeric in the full catalog is defined (correct code, correct name,
   parseable/serializable) but never emitted by this server, because
   nothing in this release triggers it (its command is "Recognized only"
   per irc-protocol-commands.md, or the feature behind it doesn't exist
   yet).

Error replies MUST be worded specifically enough to satisfy
FR-002/FR-012/FR-014/FR-015's "clear error" requirements (constitution
Principle III: error messages state what went wrong and what the
client/administrator can do about it).

Every numeric reply's target field is the receiving session's current
nickname — except before one has been claimed, when it MUST be `*`
(FR-053, irc-protocol-commands.md "Connection Registration") — this
applies to `431`/`432`/`433` below, the numerics most likely to fire
during that window.

## Used in This Release

| Numeric | Name | Triggered by | FR |
|---|---|---|---|
| `001` | `RPL_WELCOME` | Successful registration — Registration Completion Burst (irc-protocol-commands.md "Connection Registration") | FR-001, FR-051 |
| `002` | `RPL_YOURHOST` | Registration Completion Burst | FR-050, FR-051 |
| `003` | `RPL_CREATED` | Registration Completion Burst | FR-051 |
| `004` | `RPL_MYINFO` | Registration Completion Burst | FR-043, FR-044, FR-050, FR-051 |
| `005` | `RPL_ISUPPORT` | Registration Completion Burst — one or more lines (data-model.md `SupportedFeatures`); de facto meaning, not RFC 2812's `RPL_BOUNCE` | FR-051, FR-055 |
| `251` | `RPL_LUSERCLIENT` | `LUSERS` — registered users and how many are invisible | FR-003 |
| `252` | `RPL_LUSEROP` | `LUSERS` — operators online | FR-011 |
| `253` | `RPL_LUSERUNKNOWN` | `LUSERS` — connections accepted but not yet `REGISTERED` (including any that already claimed a nickname); omitted when there are none (issue #76) | FR-003 |
| `254` | `RPL_LUSERCHANNELS` | `LUSERS` — channels formed | FR-003 |
| `255` | `RPL_LUSERME` | `LUSERS` — `I have <n> clients and 1 servers` | FR-012 |
| `265` | `RPL_LOCALUSERS` | `LUSERS` — `<nick> <u> <m> :Current local users <u>, max <m>`; `u` is the registered users, `m` the high-water mark of `u` since startup, which never decreases (issue #76) | FR-003 |
| `266` | `RPL_GLOBALUSERS` | `LUSERS` — same shape and values as `265`; this is a single server, so global equals local (issue #76) | FR-003 |
| `375` | `RPL_MOTDSTART` | Registration Completion Burst and `MOTD`, when `motdPath` names a readable file: `<nick> :- <server> Message of the day - ` (issue #77) | FR-051 |
| `372` | `RPL_MOTD` | One per line of the MOTD file: `<nick> :- <line>`; a line is cut to fit 512 bytes (issue #77) | FR-051 |
| `376` | `RPL_ENDOFMOTD` | Closes the MOTD: `<nick> :End of /MOTD command` (issue #77) | FR-051 |
| `422` | `ERR_NOMOTD` | Registration Completion Burst and `MOTD` when `motdPath` is unset, or the file is missing, unreadable, empty or over 64 KiB (the last cases log a warning) | FR-051 |
| `303` | `RPL_ISON` *(Used)* | `ISON` — the online subset of the asked nicknames, in the form their owners present, space-separated; empty when none is online (issue #77) | issue #77 |
| `242` | `RPL_STATSUPTIME` *(Used)* | `STATS u` — `:Server Up <d> days <h>:<mm>:<ss>` (issue #77) | issue #77 |
| `219` | `RPL_ENDOFSTATS` | Every accepted `STATS`: `<nick> <letter> :End of /STATS report` (issue #77) | issue #77 |
| `256` | `RPL_ADMINME` *(Used)* | `ADMIN`: `<nick> <server> :Administrative info about <server>` (issue #77) | issue #77 |
| `257` | `RPL_ADMINLOC1` *(Used)* | `ADMIN` — `adminName`, or `-` when unset (issue #77) | issue #77 |
| `258` | `RPL_ADMINLOC2` *(Used)* | `ADMIN` — `adminLocation`, or `-` when unset (issue #77) | issue #77 |
| `259` | `RPL_ADMINEMAIL` *(Used)* | `ADMIN` — `adminEmail`, or `-` when unset (issue #77) | issue #77 |
| `423` | `ERR_NOADMININFO` *(Used)* | `ADMIN` when none of `adminName`, `adminLocation`, `adminEmail` is set: `<nick> <server> :No administrative info available` (issue #77) | issue #77 |
| `704` | `RPL_HELPSTART` | `HELP`/`HELPOP` — `<nick> <subject> :<first line>`; subject is the upper-cased topic, `index` when none was asked for (issue #77) | issue #77 |
| `705` | `RPL_HELPTXT` | `HELP`/`HELPOP` — one per further line of the topic (issue #77) | issue #77 |
| `706` | `RPL_ENDOFHELP` | End of a `HELP`/`HELPOP` reply: `<nick> <subject> :End of /HELP` (issue #77) | issue #77 |
| `524` | `ERR_HELPNOTFOUND` | `HELP`/`HELPOP` for a topic that does not exist: `<nick> <topic> :Help not found` (issue #77) | issue #77 |
| `402` | `ERR_NOSUCHSERVER` *(Used)* | `ADMIN`/`STATS` naming a server other than this one — there are no peers (issue #77) | issue #77 |
| `322` | `RPL_LIST` | `LIST` (one per active channel) | FR-042 |
| `323` | `RPL_LISTEND` | End of a `LIST` reply | FR-042 |
| `331` | `RPL_NOTOPIC` | `TOPIC` query on a channel with no topic set | FR-040 |
| `332` | `RPL_TOPIC` | `JOIN` or `TOPIC` query on a channel with a topic set; immediately followed by `333` | FR-014, FR-040 |
| `333` | `RPL_TOPICWHOTIME` | Immediately after `332`: `<nick> <channel> <setter> <unix-ts>`, where setter is the stored `nick!user@host` as presented when the topic was set (including cloaking), and timestamp is Unix seconds. Never sent without a topic. | FR-014, FR-040 |
| `311` | `RPL_WHOISUSER` | Successful `WHOIS` | FR-037, FR-038 |
| `312` | `RPL_WHOISSERVER` | `WHOIS`, sent immediately after `311` — this server's own name, restored per 003-irctest-conformance-fixes FR-006 (there being no federation, no other server name could ever appear here) | 003-irctest-conformance-fixes FR-006 |
| `313` | `RPL_WHOISOPERATOR` | `WHOIS` for a target currently holding the `operator` user mode — visible to any querying client, not gated by administrator privilege or self-lookup (FR-037, FR-044) | FR-037, FR-044 |
| `318` | `RPL_ENDOFWHOIS` | End of a `WHOIS` reply | FR-037 |
| `352` | `RPL_WHOREPLY` | `WHO`, one per (visible) matching user (FR-061) | FR-061 |
| `315` | `RPL_ENDOFWHO` | End of a `WHO` reply, including a zero-match one (FR-061) | FR-061 |
| `221` | `RPL_UMODEIS` | `MODE <nickname>` self-query with no mode string (FR-044) | FR-044 |
| `353` | `RPL_NAMREPLY` | `JOIN`, `NAMES` — nicknames prefixed `@` (operator) or `+` (voiced, non-operator), both (`@+`) for a `multi-prefix` requester, per irc-protocol-commands.md "Channel Operations" | FR-003, FR-041, FR-045, FR-046 |
| `366` | `RPL_ENDOFNAMES` | `JOIN`, `NAMES` | FR-003, FR-041 |
| `381` | `RPL_YOUREOPER` | Successful `OPER` | FR-034 |
| `382` | `RPL_REHASHING` | Successful `REHASH` | FR-011, FR-012 (research.md "Configuration reload mechanism") |
| `341` | `RPL_INVITING` | Successful `INVITE`, sent to the sender confirming the invitation was recorded (FR-065) | FR-065 |
| `401` | `ERR_NOSUCHNICK` | `WHOIS` for a nickname that isn't connected; `INVITE` naming a nickname that isn't connected (FR-065) | FR-037, FR-065 |
| `403` | `ERR_NOSUCHCHANNEL` | `TOPIC`/`NAMES`/`WHO` (channel-name form) for a channel that doesn't exist, or that is private/secret and the requester is neither a member nor an administrator (identical response either way, FR-047) | FR-047, FR-061 |
| `417` | `ERR_INPUTTOOLONG` | A line exceeding the 512-byte base limit or the 4096-byte `message-tags` allowance (FR-049), or a `TOPIC`-set attempt exceeding the configured `topicMaxLength` (FR-056) — not part of RFC 1459/2812 (there is no numeric defined in that gap, 416-420), but a widely-adopted de facto standard purpose-built for exactly this case, the same way this project already treats IRCv3's `CAP` numerics as belonging alongside the RFC set | FR-049, FR-056 |
| `421` | `ERR_UNKNOWNCOMMAND` | Malformed/unrecognized command, a wire-protocol-recognized command with no handler in this release, or a human-readable-content parameter (`PRIVMSG`/`NOTICE` body, topic, realname, channel name) containing invalid UTF-8 (FR-054) | FR-015, FR-054 |
| `431` | `ERR_NONICKNAMEGIVEN` | `NICK` with no argument; bare `WHOWAS` with no nickname argument (002-extended-irc-commands, 003-irctest-conformance-fixes FR-005 — same reply shape reused, not a generic `461`) | FR-001 (input validation), 003-irctest-conformance-fixes FR-005 |
| `432` | `ERR_ERRONEUSNICKNAME` | `NICK` violating the nickname grammar (invalid leading/body characters or over 9 characters) | irc-protocol-commands.md "Connection Registration Grammar" |
| `433` | `ERR_NICKNAMEINUSE` | `NICK` naming an already-claimed nickname | FR-002 |
| `441` | `ERR_USERNOTINCHANNEL` | `MODE +v`/`-v` or `+o`/`-o <nickname>` naming a nickname that isn't a current member of the target channel | FR-045, FR-046 |
| `442` | `ERR_NOTONCHANNEL` | `PART`/`KICK`/`MODE` on a channel the sender hasn't joined; `PRIVMSG`/`NOTICE` to a channel with `members-only` active that the sender hasn't joined (FR-004, FR-013/FR-043 — membership is not required for `PRIVMSG`/`NOTICE` otherwise); `SAMODE` from an administrator who isn't currently a member of the target channel (FR-058); `INVITE` from a sender who isn't currently a member of the named channel — also covers that channel not existing at all (FR-065) | FR-003, FR-004, FR-014, FR-058, FR-065 |
| `443` | `ERR_USERONCHANNEL` | `INVITE` naming a nickname that's already a member of the target channel (FR-065) | FR-065 |
| `404` | `ERR_CANNOTSENDTOCHAN` | `PRIVMSG`/`NOTICE` blocked by `moderated` or by `b` without a matching `e` exception (either presented or real identity) (#78) | FR-013, FR-062, #78 |
| `474` | `ERR_BANNEDFROMCHAN` | `JOIN` when either presented or real identity matches `b` and neither matches an `e` exception; `SAJOIN` bypasses the check (#78) | FR-062, #78 |
| `473` | `ERR_INVITEONLYCHAN` | `JOIN` naming a channel with `invite-only` active and no matching `I` exception or current invitation — `I` bypasses only `+i`; `SAJOIN` bypasses this check entirely (FR-057, FR-065, #78) | FR-065 |
| `367` | `RPL_BANLIST` | `MODE <channel> b`, one per currently-active ban mask (FR-062) | FR-062 |
| `368` | `RPL_ENDOFBANLIST` | End of a `MODE <channel> b` reply, including a zero-ban one (FR-062) | FR-062 |
| `346` | `RPL_INVITELIST` | One `MODE <channel> I` invite-exception entry (#78) | #78 |
| `347` | `RPL_ENDOFINVITELIST` | End of a `MODE <channel> I` reply, including an empty list (#78) | #78 |
| `348` | `RPL_EXCEPTLIST` | One `MODE <channel> e` ban-exception entry (#78) | #78 |
| `349` | `RPL_ENDOFEXCEPTLIST` | End of a `MODE <channel> e` reply, including an empty list (#78) | #78 |
| `478` | `ERR_BANLISTFULL` | `MODE <channel> +b/+e/+I <mask>` when the corresponding list already has 100 active entries (FR-062, #78) | FR-062 |
| `476` | `ERR_BADCHANMASK` | `JOIN` or `SAJOIN` naming a channel that violates the Channel Name Grammar (irc-protocol-commands.md "Channel Operations") — `SAJOIN` bypasses `JOIN`-gating flags, not the name grammar itself (FR-057) | FR-048, FR-057 |
| `461` | `ERR_NEEDMOREPARAMS` | Command missing required parameters (`ISON`, `PASS`, `STATS` included — issue #77) | FR-015 |
| `462` | `ERR_ALREADYREGISTRED` | `USER` from a session that has already processed one, before or after reaching `REGISTERED` (FR-001); also `PASS` after registration (issue #77) | FR-001 |
| `464` | `ERR_PASSWDMISMATCH` | Failed `OPER` (also logged as a security event, FR-019) | FR-034 |
| `491` | `ERR_NOOPERHOST` | Plaintext `OPER` when `operRequireTls` is enabled; reconnect using TLS. No credential verification or failure count. | #74 |
| `472` | `ERR_UNKNOWNMODE` | `MODE` given a flag the core moderation command set doesn't define | FR-015, FR-036 (core input validation — `MODE` is never extension-gated) |
| `481` | `ERR_NOPRIVILEGES` | `EXTENSION`/`WHOHOST`/`REHASH`/`SAJOIN`/`SAMODE` (or any admin command) attempted without administrator privilege; a non-privileged session's own `MODE <self> +o` (FR-033, FR-044) | FR-033, FR-044, FR-057, FR-058 |
| `482` | `ERR_CHANOPRIVSNEEDED` | `KICK`/`MODE` attempted by a non-operator; `INVITE` on an `invite-only` channel by a non-operator member (FR-065) | FR-014, FR-065 |
| `501` | `ERR_UMODEUNKNOWNFLAG` | `MODE <nickname>` given a flag letter no currently-recognized `UserMode` defines (FR-044) | FR-044 |
| `502` | `ERR_USERSDONTMATCH` | `MODE <nickname>` (query or set) naming a nickname other than the sender's own current one — self-only in this release (FR-044) | FR-044 |
| `CAP <nick\|*> LS` | (IRCv3, not a numeric) | `CAP LS` (target is the nick once set, else `*`) | FR-006 |
| `CAP <nick\|*> ACK` / `CAP <nick\|*> NAK` | (IRCv3) | `CAP REQ` (target is the nick once set, else `*`) | FR-007 |

## Full Numeric Catalog (`jircd-protocol` — Wire-Protocol Recognition)

Every reply/error numeric RFC 1459/2812 defines. Entries not listed above
under "Used in This Release" are **Reserved** — defined in the catalog,
never sent by this server this release (the command or feature that would
trigger them is "Recognized only" per irc-protocol-commands.md).

### Command responses (`RPL_*`)

| Numeric | Name | Numeric | Name | Numeric | Name |
|---|---|---|---|---|---|
| `001` | `RPL_WELCOME` *(Used)* | `211` | `RPL_STATSLINKINFO` | `321` | `RPL_LISTSTART` |
| `002` | `RPL_YOURHOST` *(Used)* | `212` | `RPL_STATSCOMMANDS` | `322` | `RPL_LIST` *(Used)* |
| `003` | `RPL_CREATED` *(Used)* | `219` | `RPL_ENDOFSTATS` *(Used)* | `323` | `RPL_LISTEND` *(Used)* |
| `004` | `RPL_MYINFO` *(Used)* | `221` | `RPL_UMODEIS` *(Used)* | `324` | `RPL_CHANNELMODEIS` *(Used)* |
| `005` | `RPL_BOUNCE` *(Used, as `RPL_ISUPPORT`)* | `234` | `RPL_SERVLIST` | `325` | `RPL_UNIQOPIS` |
| `200` | `RPL_TRACELINK` | `235` | `RPL_SERVLISTEND` | `329` | `RPL_CHANNELCREATED` *(Used)* |
| | | | | `331` | `RPL_NOTOPIC` *(Used)* |
| `201` | `RPL_TRACECONNECTING` | `242` | `RPL_STATSUPTIME` *(Used)* | `332` | `RPL_TOPIC` *(Used)* |
| `202` | `RPL_TRACEHANDSHAKE` | `243` | `RPL_STATSOLINE` | `341` | `RPL_INVITING` *(Used)* |
| `203` | `RPL_TRACEUNKNOWN` | `251` | `RPL_LUSERCLIENT` *(Used)* | `342` | `RPL_SUMMONING` |
| `204` | `RPL_TRACEOPERATOR` | `252` | `RPL_LUSEROP` *(Used)* | `346` | `RPL_INVITELIST` *(Used)* |
| `205` | `RPL_TRACEUSER` | `253` | `RPL_LUSERUNKNOWN` *(Used)* | `347` | `RPL_ENDOFINVITELIST` *(Used)* |
| `206` | `RPL_TRACESERVER` | `254` | `RPL_LUSERCHANNELS` *(Used)* | `348` | `RPL_EXCEPTLIST` *(Used)* |
| `207` | `RPL_TRACESERVICE` | `255` | `RPL_LUSERME` *(Used)* | `349` | `RPL_ENDOFEXCEPTLIST` *(Used)* |
| `208` | `RPL_TRACENEWTYPE` | `256` | `RPL_ADMINME` *(Used)* | `351` | `RPL_VERSION` |
| `209` | `RPL_TRACECLASS` | `257` | `RPL_ADMINLOC1` *(Used)* | `352` | `RPL_WHOREPLY` *(Used)* |
| `210` | `RPL_TRACERECONNECT` | `258` | `RPL_ADMINLOC2` *(Used)* | `353` | `RPL_NAMREPLY` *(Used)* |
| `261` | `RPL_TRACELOG` | `259` | `RPL_ADMINEMAIL` *(Used)* | `364` | `RPL_LINKS` |
| `262` | `RPL_TRACEEND` | `265` | `RPL_LOCALUSERS` *(Used)* | `365` | `RPL_ENDOFLINKS` |
| `263` | `RPL_TRYAGAIN` | `266` | `RPL_GLOBALUSERS` *(Used)* | `366` | `RPL_ENDOFNAMES` *(Used)* |
| `300` | `RPL_NONE` | `301` | `RPL_AWAY` | `367` | `RPL_BANLIST` *(Used)* |
| `302` | `RPL_USERHOST` | `303` | `RPL_ISON` *(Used)* | `368` | `RPL_ENDOFBANLIST` *(Used)* |
| `305` | `RPL_UNAWAY` | `306` | `RPL_NOWAWAY` | `369` | `RPL_ENDOFWHOWAS` |
| `311` | `RPL_WHOISUSER` *(Used)* | `312` | `RPL_WHOISSERVER` *(Used)* | `371` | `RPL_INFO` |
| `313` | `RPL_WHOISOPERATOR` *(Used)* | `314` | `RPL_WHOWASUSER` | `372` | `RPL_MOTD` |
| `315` | `RPL_ENDOFWHO` *(Used)* | `317` | `RPL_WHOISIDLE` | `374` | `RPL_ENDOFINFO` |
| `318` | `RPL_ENDOFWHOIS` *(Used)* | `319` | `RPL_WHOISCHANNELS` | `375` | `RPL_MOTDSTART` |
| `376` | `RPL_ENDOFMOTD` *(Used)* | `381` | `RPL_YOUREOPER` *(Used)* | `382` | `RPL_REHASHING` *(Used)* |
| `383` | `RPL_YOURESERVICE` | `391` | `RPL_TIME` | `392` | `RPL_USERSSTART` |
| `393` | `RPL_USERS` | `394` | `RPL_ENDOFUSERS` | `395` | `RPL_NOUSERS` |

### Error responses (`ERR_*`)

| Numeric | Name | Numeric | Name | Numeric | Name |
|---|---|---|---|---|---|
| `401` | `ERR_NOSUCHNICK` *(Used)* | `402` | `ERR_NOSUCHSERVER` *(Used)* | `403` | `ERR_NOSUCHCHANNEL` *(Used)* |
| `404` | `ERR_CANNOTSENDTOCHAN` *(Used)* | `405` | `ERR_TOOMANYCHANNELS` | `406` | `ERR_WASNOSUCHNICK` |
| `407` | `ERR_TOOMANYTARGETS` | `408` | `ERR_NOSUCHSERVICE` | `409` | `ERR_NOORIGIN` |
| `411` | `ERR_NORECIPIENT` | `412` | `ERR_NOTEXTTOSEND` | `413` | `ERR_NOTOPLEVEL` |
| `414` | `ERR_WILDTOPLEVEL` | `415` | `ERR_BADMASK` | `417` | `ERR_INPUTTOOLONG` *(Used)* |
| `421` | `ERR_UNKNOWNCOMMAND` *(Used)* | `422` | `ERR_NOMOTD` *(Used)* | `423` | `ERR_NOADMININFO` |
| `424` | `ERR_FILEERROR` |
| `431` | `ERR_NONICKNAMEGIVEN` *(Used)* | `432` | `ERR_ERRONEUSNICKNAME` *(Used)* | `433` | `ERR_NICKNAMEINUSE` *(Used)* |
| `436` | `ERR_NICKCOLLISION` | `437` | `ERR_UNAVAILRESOURCE` | `441` | `ERR_USERNOTINCHANNEL` *(Used)* |
| `442` | `ERR_NOTONCHANNEL` *(Used)* | `443` | `ERR_USERONCHANNEL` *(Used)* | `444` | `ERR_NOLOGIN` |
| `445` | `ERR_SUMMONDISABLED` | `446` | `ERR_USERSDISABLED` | `451` | `ERR_NOTREGISTERED` |
| `461` | `ERR_NEEDMOREPARAMS` *(Used)* | `462` | `ERR_ALREADYREGISTRED` *(Used)* | `463` | `ERR_NOPERMFORHOST` |
| `464` | `ERR_PASSWDMISMATCH` *(Used)* | `465` | `ERR_YOUREBANNEDCREEP` | `466` | `ERR_YOUWILLBEBANNED` |
| `467` | `ERR_KEYSET` | `471` | `ERR_CHANNELISFULL` | `472` | `ERR_UNKNOWNMODE` *(Used)* |
| `473` | `ERR_INVITEONLYCHAN` *(Used)* | `474` | `ERR_BANNEDFROMCHAN` *(Used)* | `475` | `ERR_BADCHANNELKEY` |
| `476` | `ERR_BADCHANMASK` *(Used)* | `477` | `ERR_NOCHANMODES` | `478` | `ERR_BANLISTFULL` *(Used)* |
| `481` | `ERR_NOPRIVILEGES` *(Used)* | `482` | `ERR_CHANOPRIVSNEEDED` *(Used)* | `483` | `ERR_CANTKILLSERVER` |
| `484` | `ERR_RESTRICTED` | `485` | `ERR_UNIQOPPRIVSNEEDED` | `491` | `ERR_NOOPERHOST` |
| `501` | `ERR_UMODEUNKNOWNFLAG` *(Used)* | `502` | `ERR_USERSDONTMATCH` *(Used)* | | |

**Contract notes**:
- Every error reply's trailing `:<text>` parameter MUST name the specific
  problem (e.g., "Nickname is already in use" for `433`, not a generic
  "Error") — this is the testable form of FR-002/FR-012/FR-014/FR-015's
  "clear error" requirement and of the constitution's UX Consistency
  principle. This applies to every numeric in the "Used" set; Reserved
  numerics have no wording to get right yet since this server never sends
  them.
- Reply wording for a given numeric MUST be identical regardless of which
  subsystem produced it. `482` is now always sourced from core channel
  moderation (FR-036) rather than an extension, so this mainly applies to
  `481`: it MUST read the same whether it was triggered by the core admin
  command set or by a future `jircd-server-extensions/*` extension that
  also gates an action on administrator privilege — satisfying
  FR-011/FR-020's extension-consistency intent at the protocol level.
- Moving a Reserved numeric to Used is a `jircd-core`/extension change
  (implementing the feature/command that triggers it), not a
  `jircd-protocol` change — the numeric is already correctly modeled.
- `005` is used for its de facto `RPL_ISUPPORT` meaning (FR-055,
  research.md "ISUPPORT / RPL_ISUPPORT"), not RFC 2812's original
  `RPL_BOUNCE` — the `RPL_BOUNCE` name in the catalog reflects the RFC's
  own naming for historical/parsing-completeness purposes, but this
  server never emits it with that meaning. This resolves the deferral
  an earlier version of this note carried ("revisit if a future
  capability needs to advertise server limits/features this way") —
  FR-054's UTF-8 enforcement was exactly that trigger.
