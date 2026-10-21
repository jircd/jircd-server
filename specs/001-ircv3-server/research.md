# Phase 0 Research: Modular IRCv3 Chat Server

**Input**: [plan.md](./plan.md) Technical Context | **Spec**: [spec.md](./spec.md)

All items below were flagged in Technical Context as needing a documented
decision before Phase 1 design. Each follows Decision / Rationale /
Alternatives Considered.

## Protocol/server boundary

**Decision**: A standalone `jircd-protocol` Gradle subproject owns wire
format concerns only — message line framing, command/reply parsing and
serialization, hostmask (`nickname!ident@hostname`, FR-030) formatting, and
the CAP negotiation grammar. It has no dependency on `jircd-core`, no
socket/connection code, and no server-side state (registries, sessions).
`jircd-core` depends on `jircd-protocol`; the dependency never goes the
other way.

**Rationale**: This is a direct requirement, not just good layering —
protocol code needs to be reusable by a future IRC *client* library, which
must not have to pull in server engine code (connection lifecycle,
nickname registry, rate limiting) it has no use for. Enforcing the
one-directional dependency at the Gradle level (not just by convention)
makes an accidental server-logic leak into the protocol layer a build
failure, not a code-review judgment call.

**Alternatives considered**: Keep protocol parsing inside `jircd-core`
alongside server logic (simplest short-term option, but reuse for a client
library would require extracting it later anyway, and nothing would stop
server-specific concerns from creeping into the parsing code in the
meantime).

**Consequence for FR-056's configurable length limits**: `Hostmask`'s
nickname grammar and `ChannelName`'s grammar (both `jircd-protocol`) MUST
NOT read `ServerConfiguration` directly — that would violate this
boundary, since `ServerConfiguration` is a `jircd-core` concept and the
dependency never goes protocol-ward. Instead, both validators take the
maximum length as a caller-supplied parameter (e.g., `Hostmask.isValidNickname(String,
int)`, `ChannelName.isValid(String, int)`); `jircd-core`'s
`NickCommandHandler`/`JoinCommandHandler` read the actual configured
value from `ServerConfiguration` and pass it in on every call. The
*shape* grammar (leading letter/`#`, allowed character set) stays a pure,
config-independent `jircd-protocol` fact; only the numeric ceiling is
externalized — a future client library depending on `jircd-protocol`
still gets a fully self-contained grammar check, just parameterized
instead of hardcoded, and still has no reason to know `ServerConfiguration`
exists.

## Wire-protocol command & numeric completeness

**Decision**: `jircd-protocol`'s `Command` and `NumericReply` catalogs
cover the complete RFC 1459/2812 command and numeric-reply sets (plus the
IRCv3 framework commands this project uses — `CAP`, `AUTHENTICATE`,
`TAGMSG`), regardless of which commands `jircd-core` actually implements a
handler for in this release. contracts/irc-protocol-commands.md and
contracts/irc-numeric-replies.md each carry the full catalog, with a
"Used in this release" subset called out separately from the
"Recognized/Reserved" remainder.

**Rationale**: This is a direct consequence of the "Protocol/server
boundary" decision above, not a separate scope expansion — if
`jircd-protocol` is genuinely meant to be reusable by a future IRC
*client* library, that library needs to parse `311 RPL_WHOISUSER` or a
`WHOIS` line from *any* server it connects to, not only from this one.
Scoping the catalog down to "whatever `jircd-core` currently implements"
would silently couple a supposedly-generic library to this release's
feature set, and would force a breaking model change every time a future
release adds a command this server didn't originally implement (e.g.,
`TOPIC` or `WHOIS`) — versus just flipping that command from
"Recognized only" to "Implemented" against a catalog that already has it.
The cost of building the catalog complete now is low (it's enum-style
data, not behavior); the cost of discovering it's incomplete later, after
a client library already depends on it, is not.

**Alternatives considered**: Scope the catalog to only the commands/
numerics this release's stories actually use (the original approach) —
rejected per above: it undermines the entire stated reason
`jircd-protocol` exists as a separate module in the first place, and
defers real rework (extending the catalog under a live client-library
dependency) rather than avoiding it.

## Connection registration grammar

**Decision**: `NICK`/`USER` get generic line parsing (`Command`
arity metadata, T014, same as any other fixed-arity command) rather than
a dedicated grammar class — unlike `CAP`, which does get one
(`CapabilityNegotiationGrammar`, T018). What *does* get its own defined
grammar is the **nickname format** itself (RFC 2812 §2.3.1: one leading
letter/`special` character, up to 8 more letters/digits/`special`/`-`,
9 characters total) and the **username** content rule (any octet except
NUL/CR/LF/space/`@`, truncated to 9 characters for the `ident` shown in
the hostmask) — both now spelled out in
contracts/irc-protocol-commands.md's "Connection Registration Grammar".
Registration *completion sequencing* (what combination/order of `NICK`,
`USER`, and optional `CAP` negotiation triggers `001 RPL_WELCOME`) is a
`jircd-core`/`ConnectionLifecycle` state-machine concern, not a
wire-protocol grammar concern, and is documented there instead.

**Rationale**: `CAP` needed a dedicated grammar component because its
subcommands (`LS`/`REQ`/`ACK`/`NAK`/`END`) branch into genuinely different
follow-on syntax within one line — a real sub-language. `NICK`/`USER`
don't have that: fixed parameter counts, no branching, so the generic
parser plus arity metadata already covers *how many parameters* a
well-formed line has. What was actually missing — and is a different kind
of thing — is *what characters are legal inside* the nickname parameter.
`432 ERR_ERRONEUSNICKNAME` (contracts/irc-numeric-replies.md) referenced
"nickname format rules" from the very first draft of this plan without
those rules ever being defined anywhere, which is a real gap this closes:
a `jircd-core` handler can't correctly reject a malformed nickname against
a rule that was never written down. Defining it in `jircd-protocol`
(alongside `Hostmask`, T017, which already needs to know what a nickname
looks like when composing `nickname!ident@hostname`) rather than inline
in `jircd-core`'s `NICK` handler keeps it reusable by the same future
client library the rest of `jircd-protocol` is built for — a client
validating a nickname before sending `NICK` needs the identical rule.

**Alternatives considered**:
- *A dedicated `RegistrationGrammar` class mirroring
  `CapabilityNegotiationGrammar`*: rejected — there's no sub-language to
  justify it; would be a class that just re-does what generic parsing
  plus arity metadata already does, adding indirection without adding
  correctness.
- *Leave nickname format as an inline check inside the `NICK` handler
  (`jircd-core`)*: works for this server alone, but ties a wire-protocol-
  level rule to `jircd-core` specifically, unlike everything else
  `Hostmask` already centralizes — and silently reopens the same "never
  formally defined" gap the moment anyone other than that one handler
  needs the rule (e.g., a future client library, or a second place in
  `jircd-core` that also validates nicknames).

## Server identity (FR-050/FR-051)

**Decision**: `ServerConfiguration.serverName` (data-model.md) is the
source/prefix on every server-originated message, administrator-
configurable with a zero-configuration fallback to the deployment host's
own network hostname if unset. It MUST contain at least one `.`
character in either case — an administrator-supplied value without one
is a load-time validation error (FR-012); the hostname fallback appends
a fixed synthetic suffix (`.local`) if the host's own hostname lacks a
dot, rather than only enforcing the rule against explicit input.
`serverVersion` is a sibling field, but
*not* administrator-configurable — sourced from the build/release itself,
since it identifies the running software, not something an administrator
has any reason to override. Concretely: `jircd-server/build.gradle.kts`
wires a `generateVersionResource` task, fed by the root build's
`project.version` (itself the single source of truth, set via root
`gradle.properties`' `version` property — no separate version string
duplicated anywhere else), into `processResources`, writing a
`net/jircd/server/version.properties` classpath resource with one
`version=<value>` line. `JircdServerApplication` reads that resource via
`ClassLoader.getResourceAsStream(...)` at startup and fails fast with a
specific startup error if it's missing or unparsable — the same posture
as any other invalid-configuration startup failure (FR-012). Upon successful
registration, `UserCommandHandler` sends a fixed burst using both:
`001 RPL_WELCOME`, `002 RPL_YOURHOST` (`serverName` + `serverVersion`),
`003 RPL_CREATED` (this process's start time — not a fixed software
release date), `004 RPL_MYINFO` (`serverName`, `serverVersion`, and the
currently-recognized channel-mode letters, read from the same
server-scoped `ChannelMode` catalog snapshot `005`'s `CHANMODES` also
reads, research.md "ISUPPORT / RPL_ISUPPORT", plus the currently-
recognized user-mode letters, read from the `UserMode` catalog
(research.md "User mode: `operator`" — `o` this release, FR-044)),
`005 RPL_ISUPPORT` (research.md "ISUPPORT / RPL_ISUPPORT"), then
`422 ERR_NOMOTD` to close the burst.

**Rationale**: This closes two real gaps at once, not one. First, the
narrower one: the "Connection Registration" contract had referenced "the
standard post-registration burst" since its first draft without ever
defining what that burst actually contains — `001` alone was the only
numeric ever marked `Used`, the same class of "referenced but never
specified" gap the nickname-grammar fix closed earlier for `432`. Second,
the more foundational one it surfaced while investigating: nothing in
this project ever defined *any* server-name concept, even though every
numeric reply this server sends (not just the registration burst) needs
one as its message source — this was a latent gap in the wire protocol's
basic shape, not something specific to registration. Fixing the burst
without also introducing `serverName` would have meant inventing a
placeholder value at exactly the moment it's first needed and never
formalizing it, the same mistake the original "standard...burst" phrase
already made once. Reusing the `ChannelMode` catalog for `004`'s mode
list (rather than hand-maintaining a separate letter list) means it
never drifts out of sync with what `MODE` actually recognizes, including
once a future extension contributes a flag (FR-043).

**Alternatives considered**:
- *Hardcode a fixed server name (e.g., "jircd") instead of making it
  configurable*: rejected — every other identity-shaping value in this
  project (administrator credentials, listener ports, rate limits) is
  configurable via `ServerConfiguration`; a hardcoded name would be the
  one exception, and administrators running multiple named instances
  (e.g., separate test/production deployments) need to tell them apart
  in every client's server-info display.
- *Require the administrator to configure a server name, refuse to start
  otherwise*: rejected — every other optional `ServerConfiguration`
  field already has a sensible default (rate limits, keep-alive timing);
  requiring configuration for a value with an obvious fallback
  (the host's own hostname) would be inconsistent friction, not safety.
- *Implement a real MOTD (file-backed content) instead of always sending
  `422 ERR_NOMOTD`*: rejected for this release — `MOTD` is already
  "Recognized only" with no content-management story anywhere in this
  project; `422` alone is enough to give clients a defined burst-end
  signal without opening a new content/configuration surface this
  release doesn't need.
- *Send `375`/`372`/`376` (an empty MOTD) instead of `422`*: rejected —
  `422 ERR_NOMOTD` is the RFC-correct, honest signal for "no MOTD exists"
  (research.md convention: reuse the exact-fit existing numeric, the same
  choice already made for `476`/`417`); sending an empty MOTD body would
  misrepresent that one was configured.
- *Read the version from the JAR manifest's `Implementation-Version`
  attribute (`Package.getImplementationVersion()`) instead of a generated
  properties resource*: rejected — this project's integration tests
  (`jircd-integration-tests`, research.md "Deterministic testing under
  concurrency") start a live server in-process against exploded build
  output, not a packaged JAR; a manifest attribute is only populated when
  classes are loaded from an actual JAR on the classpath, so it would
  silently return `null` in that path (and in an IDE run configuration)
  while working in a packaged deployment — the exact kind of dev/prod
  divergence a properties resource on the classpath avoids, since it's
  present identically whether the classes come from a directory or a JAR.
- *Don't require a dot in `serverName`; leave prefix-parsing ambiguity as
  a client-side concern*: rejected — this server controls the one input
  (`serverName`) that determines whether the ambiguity can even arise in
  the first place; refusing to guarantee it costs nothing to fix here and
  pushes a real interoperability risk onto every client instead, for no
  benefit to the administrator (an unrelated string still identifies the
  server just as well with a dot in it as without).

## IRC casemapping (FR-052)

**Decision**: Nickname and channel name comparisons — uniqueness
(FR-002, FR-003) and target resolution for every command that names one
— use IRC's traditional "rfc1459" casemapping: standard ASCII
letter-folding (`A`-`Z` with `a`-`z`) plus four additional pairs, `[`/`{`,
`]`/`}`, `\`/`|`, and `^`/`~` (RFC 2812 §2.2). `ClientSession.nickname`
and `Channel.name` (data-model.md) store the *original* casing a client
registered/created with — display, hostmasks, and echoes all show that
original casing — only the *comparison* used for uniqueness and lookup
folds case; nothing normalizes the stored value itself.

**Rationale**: This is IRC's own, decades-old resolution to a problem
every deployed network still has to solve: comparing "Alice" and "alice"
byte-for-byte would let both register, contradicting FR-002's own "single
namespace" language, and would make `PRIVMSG alice` fail to reach a
client that registered as "Alice" — a real, immediately-visible
correctness bug, not an edge case, since case variation in how people
type nicknames is constant in practice. This was a genuine gap: FR-002/
FR-003 committed to uniqueness without ever specifying *how* two names
are compared, an unstated assumption easy to get wrong (naive
byte-for-byte comparison) precisely because it looks like it needs no
specification at all. Storing the original casing (not a folded/
normalized form) matters for the same reason `UserIdentity.presentedForm`
already distinguishes storage from display elsewhere in this data model:
a client that typed "Alice" expects to see "Alice," not "alice," even
though the *server* now treats "alice" as unavailable to anyone else.

**Alternatives considered**:
- *Plain ASCII case-folding only (no `[]\^` special-character pairs)*:
  rejected — this is IRCv3's `CASEMAPPING=ascii` variant, a real,
  supported option on some networks, but "rfc1459" (the fuller mapping)
  remains the de facto default across the deployed IRC ecosystem this
  project aims to be compatible with; nothing in this project's scope
  calls for deviating from the default.
- *Normalize stored nicknames/channel names to a canonical case*:
  rejected — would silently rewrite what a client typed into something
  else, a worse UX than the (correct) alternative of keeping the
  original casing and only folding case for comparison; also unnecessary
  extra state (the "canonical form" vs. the "display form") for no
  benefit over folding at comparison time.

## ISUPPORT / RPL_ISUPPORT (FR-055)

**Decision**: Numeric `005`, sent as one or more lines during the
Registration Completion Burst (FR-051, right after `004`), is used for
its de facto `RPL_ISUPPORT` meaning — a list of `TOKEN`/`TOKEN=VALUE`
feature-and-limit advertisements — not RFC 2812's original `RPL_BOUNCE`
("try this other server") meaning, which is effectively unused in
practice across the deployed IRC ecosystem. This release's token set
(data-model.md `SupportedFeatures`) is fixed and minimal:
`CASEMAPPING=rfc1459` (FR-052), `CHANTYPES=#` (FR-048), `NICKLEN`,
`CHANNELLEN`, `TOPICLEN` (FR-056 — 9/50/390 by default, but each
administrator-configurable via `ServerConfiguration`, not fixed
constants the way `CASEMAPPING`/`CHANTYPES` are), `MODES` (FR-064 — `6`
by default, likewise configurable), `CHANMODES=b,,,imnps` (from the
`ChannelMode` catalog, FR-043; `b` populates the list-type `A` group,
FR-062; `i` populates the boolean `D` group, FR-065), `PREFIX=(ov)@+`
(FR-045/FR-046), and `UTF8ONLY` (FR-054) —
every token
restates a value this project already committed to elsewhere, none is a
new, independently-configurable setting *this decision itself*
introduces (FR-056 introduces the configurability; this decision only
consumes it). `SupportedFeatures` itself is server-scoped, not
per-session: the truly fixed tokens (`CASEMAPPING`, `CHANTYPES`,
`MODES`, `UTF8ONLY`) are constants; `CHANMODES`/`PREFIX` are recomputed
when `ExtensionRegistry`'s state changes (an extension enable/disable);
and `NICKLEN`/`CHANNELLEN`/`TOPICLEN` are recomputed whenever
`ServerConfiguration` is (re)loaded — startup, and any successful
`SIGHUP`/`REHASH` reload thereafter (contracts/server-configuration.md
"Live reload") — never on every new registration in any case; every
session's burst reads the same, already-current shared value.

**Rationale**: This resolves a deferral this project made explicit
earlier and deliberately left open rather than silently dropped
(contracts/irc-numeric-replies.md's `005` note: "revisit if a future
capability needs to advertise server limits/features this way") — FR-054
(UTF-8 enforcement) is exactly that trigger: `UTF8ONLY` is the standard,
purpose-built token for declaring it, and a server that enforces UTF-8
without advertising `UTF8ONLY` leaves every connecting client to
discover that the hard way, by trial and error, instead of up front.
Once `005` was being sent at all, advertising the other values this
project had *already* decided (casemapping, channel-name grammar
limits, nickname length, the mode-prefix mapping `353` already uses) cost
nothing further and closes several client-side guessing games at once —
real IRC clients commonly use `ISUPPORT` to configure their own UI
(e.g., how to parse `@`/`+` prefixes, what casemapping to fold locally),
and a server that never sends it forces every client to fall back to
guessed defaults that may not match this server's actual behavior.
Modeling `SupportedFeatures` as server-scoped rather than per-session
matters for the same reason its own tokens are all server-wide facts,
not client-specific ones: nothing about *which* session is registering
changes any of them, so recomputing per session would mean SC-003's
1,000 concurrent connections each redundantly walking the same,
unchanged `ChannelMode` catalog to produce an identical string — real
but avoidable work for values that only ever change on the rare
administrative action (an extension toggle) that already has its own
well-defined transition point to recompute from instead.

**Alternatives considered**:
- *Advertise a larger, more "complete" token set (e.g., `TARGMAX`,
  `CHANLIMIT`, `NETWORK`) to look more feature-complete*: rejected — this
  project has no concrete, decided value for any of these (no per-client
  channel limit is enforced, no "network name" concept distinct from
  `serverName` exists). Advertising a token with an invented value would
  be worse than omitting it — an absent `ISUPPORT` token already has a
  well-understood meaning ("unspecified"), matching this project's
  established discipline of not inventing limits it doesn't actually
  enforce (the same reasoning `serverVersion` and the max-connections
  deferral already used). `TOPICLEN` was originally omitted under this
  same reasoning — no topic-length cap existed separate from the general
  line-length budget — until FR-056 gave the project a concrete, enforced
  answer, at which point omitting it would itself have become the
  inconsistent choice.
- *Recompute `CHANMODES`/`PREFIX` fresh on every registration, reading
  the `ChannelMode` catalog live at that moment (the original shape this
  decision was first written with)*: reconsidered and rejected — not
  because the catalog it reads is wrong (it's still the single shared
  source `004` also reads, avoiding the "two numerics disagree" bug
  either way), but because *when* to read it was modeled wrong. Nothing
  about `CHANMODES`/`PREFIX` varies per session — both are server-wide
  facts, changing only when `ExtensionRegistry`'s state does — so
  reading them fresh on every registration is redundant work with no
  correctness benefit over reading a value already recomputed at the
  one point that actually changes it. `SupportedFeatures` is
  server-scoped for this reason; see data-model.md's own note (the
  "1,000 concurrent connections" argument above).
- *Send `005` immediately after `001`, before `002`/`003`/`004`*:
  rejected — every real deployed IRC server sends `ISUPPORT` after the
  server-identity numerics (`002`-`004`), and clients that parse the
  registration burst positionally (a minority, but real) expect that
  order; nothing is gained by deviating from the near-universal
  convention.

## Configurable protocol length limits (FR-056)

**Decision**: Three previously-fixed values become
administrator-configurable `ServerConfiguration` fields, each with a
default matching this specification's existing baseline: nickname
maximum length (9, previously hardcoded in the "Connection Registration
Grammar" nickname production), channel name maximum length (50,
including the leading `#`, previously hardcoded in FR-048/"Channel Name
Grammar"), and channel topic maximum length (390, newly introduced —
this project never enforced one before, research.md "ISUPPORT /
RPL_ISUPPORT"). All three share one validation rule at config load time:
a positive integer, at most 400 — a shared safety ceiling, not a
per-field tuned value, chosen so no combination of configured lengths
can produce a value that structurally cannot fit within FR-049's
512-byte base protocol line once command framing (command name, target
parameter, sigils, CR-LF) is subtracted, regardless of which field is
involved or how the other two are configured. Enforcement: nickname and
channel name length violations continue to use their existing grammar
rejections (`432`/`476`) — only the length *value* they check against
becomes configuration-derived instead of a compiled-in constant, the
check itself is unchanged. A topic exceeding the configured length is
new: no rejection path existed for it before, so it reuses `417
ERR_INPUTTOOLONG` (FR-049's numeric for "input exceeds a configured size
limit") rather than introducing a new numeric or silently truncating.
All three values are advertised via `RPL_ISUPPORT` (`NICKLEN`,
`CHANNELLEN`, `TOPICLEN`, research.md "ISUPPORT / RPL_ISUPPORT") so a
client never has to discover the actually-enforced value by trial and
error, and `SupportedFeatures` recomputes those three tokens whenever
`ServerConfiguration` is (re)loaded — the same "recompute only at the
point that actually changes it" discipline already applied to
`CHANMODES`/`PREFIX` on extension-state changes.

**Rationale**: Every other identity- and capacity-shaping value in this
project is already administrator-configurable through
`ServerConfiguration` (listener ports, rate limits, the server name
itself, FR-050) with a sensible RFC/convention-matching default when
unset; nickname and channel-name length were the two remaining
protocol-shape constants still compiled in rather than following that
same pattern, and topic length had never been given a limit at all
despite being exactly the kind of unbounded-user-input field FR-049's
line-length reasoning already argues should never go unbounded. Reusing
`417` for topic overlength keeps this consistent with the project's
established numeric-reuse discipline (`417`/`476` reused rather than
inventing new numerics, research.md "Wire-protocol command & numeric
completeness") and with the explicit "reject, don't silently mangle"
posture FR-049 and FR-054 already committed to for other length/validity
failures — the alternative (silent truncation, the treatment this
project *does* use for `USER`'s overlong `<user>` parameter) is only
appropriate where RFC 2812 defines no rejection numeral at all; `417`
already exists and already means exactly this failure class, so
truncating here would be inconsistent, not simpler.

**Alternatives considered**:
- *Leave nickname/channel-name length as fixed constants, only make
  topic length configurable (the minimal read of "topic length should
  be configurable")*: rejected — the moment topic length becomes
  administrator-tunable, leaving the other two protocol-shape lengths
  hardcoded is an arbitrary inconsistency an administrator has no way to
  anticipate ("why can I configure one length limit but not the other
  two?"), not a deliberately scoped decision.
- *Give each field its own independent upper bound tuned to its typical
  real-world range (e.g., nickname ≤ 30, channel name ≤ 64, topic ≤
  300) instead of one shared 400-character ceiling*: rejected as
  needless precision — the ceiling exists only to catch a
  configuration mistake before it produces an unusable server, not to
  encode a "recommended maximum" per field; a single, easy-to-state
  number that comfortably fits all three is simpler to specify, test,
  and explain than three separately-justified numbers that all exist
  for the same reason.
- *Silently truncate an overlong topic to the configured limit instead
  of rejecting it (mirroring `USER`'s `<user>` truncation)*: rejected —
  `<user>` truncation is a fallback used only because RFC 2812 defines
  no rejection numeral for that specific case; `417 ERR_INPUTTOOLONG`
  already exists and already covers exactly this failure class (FR-049),
  so silently mangling the topic instead of using it would reintroduce
  the "guess what happened" problem FR-049 and FR-054 were written to
  eliminate.

## Networking model

**Decision**: Blocking-style I/O per connection on Java virtual threads
(`Executors.newVirtualThreadPerTaskExecutor()`), built directly on
`java.nio.channels.ServerSocketChannel`/`SocketChannel`. No external
networking framework (e.g., Netty).

**Rationale**: Java 25's virtual threads (finalized since Java 21) make
thread-per-connection cheap at the ~1,000-connection scale SC-003 requires —
each blocked read is a park, not an OS thread, so simple sequential,
easy-to-test code per connection scales without the callback/pipeline
complexity an async framework like Netty requires. This is now the
mainstream "standard practice" recommendation for new JVM network servers at
this scale, and it minimizes external dependencies (constitution Code
Quality principle: no unjustified complexity).

**Alternatives considered**:
- *Netty*: proven at far larger scale (tens of thousands of connections) and
  has IRC-adjacent protocol codec examples, but its callback/pipeline model
  is significantly more code and cognitive overhead than this project's
  ~1,000-connection target justifies, and it would be the project's first
  and only major external dependency for something the JDK now handles
  natively.
- *Classic platform-thread-per-connection*: same programming model as
  virtual threads but each connection costs a real OS thread (~1MB stack);
  1,000 connections is borderline-risky for default JVM thread-stack
  budgets and would need manual thread-pool tuning. Virtual threads remove
  this constraint outright.

## Message fan-out concurrency model

**Decision**: Each `ClientSession` owns a bounded outbound queue and a
single dedicated writer virtual thread that is the *only* thread ever
allowed to write to that session's `SocketChannel`. Delivering a message to
a channel (FR-004) is a **two-stage** pipeline, split by who has the
information needed for each stage:

1. **Sender's thread, once**: resolves the parts of the message that do
   *not* vary by recipient — the hostmask and the message body/command.
   The hostmask is resolved by live-checking the current `cloak`
   `ServerExtension` state at that exact moment — enabled or not, never a
   cached/stale value (`UserIdentity.presentedForm`, data-model.md) — the
   same "live-check, never cached" discipline stage 2 applies to
   capabilities below, just evaluated once here rather than once per
   recipient, because cloaking is a uniform display transform applied the
   same way to every viewer, not a per-viewer choice. Server extension
   state is the *only* thing allowed to change the hostmask at this stage
   — no recipient's own state (capabilities, session data) ever factors
   into it, which is exactly what makes it safe to bake one resolved
   hostmask into a single immutable object shared by every recipient of a
   fan-out. This produces one shared, immutable "pending delivery" object,
   enqueued onto every recipient member's outbound queue. No
   capability-driven formatting decisions are made at this stage — it
   never writes to another session's socket directly, only enqueues.
2. **Each recipient's own writer thread, at drain time**: converts that
   session's next queued pending delivery into the actual wire line,
   applying *that recipient's* `negotiatedCapabilities` — live-checked
   against current `CapabilityExtension` state, never cached (data-model.md
   "Capability" validation rules) — immediately before the
   `SocketChannel.write`. This is what makes it correct for Alice (who
   negotiated `message-tags`+`server-time`) and Bob (who negotiated
   nothing) to receive different wire-level renderings of the *same*
   channel message, drawn from the *same* queued object: the per-recipient
   decoration (tag prefix, `time` tag, `msgid` tag) happens independently
   in each recipient's own thread, not once on the sender's.

`echo-message` is a different kind of decision made at a different point:
whether the sender's *own* session belongs in the recipient set at all is
decided once, by the sender's thread, when it builds the member list for
stage 1 — it is not a per-write formatting concern like the tag
capabilities are.

A member's queue reaching capacity is treated as that connection being too
slow to keep up and is handled the same way as any other connection-loss
condition (FR-017 cleanup), not by blocking the sender.

**Rationale**: This closes two gaps at once. First, the one the earlier
plan review flagged — without a shared writer-owns-its-socket model,
`PRIVMSG`/`NOTICE`/`JOIN`/`PART`/`KICK`/`MODE` fan-out (FR-004, and every
other command that echoes to multiple members) would mean one session's
thread writing directly to N other sessions' `SocketChannel`s, which is
unsafe if that member's own writer thread could run concurrently on the
same channel. Second — and this is why formatting is split into two
stages rather than done once by the sender — a single shared "already
formatted" queue element cannot simultaneously be correct for recipients
with different negotiated capabilities; doing the capability-dependent
part of formatting in each recipient's own writer thread, at the latest
possible moment, is both the only way to get per-recipient correctness
and a natural extension of the "one thread owns this socket" invariant
already established for write-safety.

**Alternatives considered**:
- *Format once on the sender's thread, before enqueueing (the original
  design)*: cannot express "Alice sees a `time` tag, Bob doesn't" from one
  shared formatted string — reverted for exactly that reason.
- *Direct cross-thread writes, synchronized on the target channel*: works,
  but requires every code path that might deliver a message to remember to
  synchronize correctly, and turns a slow recipient's contended lock into a
  latency problem for whichever sender thread happens to hit it — exactly
  the kind of thing SC-002's 1s delivery budget is sensitive to.
- *A single global outbound event loop (Netty-style)*: reintroduces the
  callback/pipeline complexity "Networking model" above rejected, for a
  problem the per-session-queue-plus-writer approach solves at
  ~1,000-connection scale without it.

## Message identifiers (`msgid`, FR-059)

**Decision**: `OutboundMessage` (data-model.md) gains a `messageId`
field — a server-generated `java.util.UUID`, assigned once by the
sender's thread at stage 1 of the fan-out pipeline above, the identical
"computed once, shared by every recipient" treatment `sentAt` already
gets. It is exposed as the `msgid` tag by the *same* `message-tags`
`CapabilityExtension` tag-decoration hook that already exists (research.md
"Message fan-out concurrency model" — the hook `SessionWriter` calls
per-recipient at drain time), not a new hook and not a new capability:
`message-tags` itself now contributes one unconditional tag
(`msgid`, present whenever the recipient has negotiated `message-tags`
at all) alongside the recipient-conditional ones `server-time`
(`time`) contributes only when *also* negotiated. `messageId` is
assigned to every `OutboundMessage`, regardless of which wire command it
represents (`PRIVMSG`, `NOTICE`, `JOIN`, `KICK`, etc.) — the field
already exists on every instance of this type, so giving every instance
an id is the uniform, non-arbitrary choice, rather than special-casing
which commands get one.

**Rationale**: `msgid` is IRCv3's own standard tag name for exactly this
purpose (an opaque, server-generated, per-message unique identifier) —
reusing it, rather than inventing a project-specific tag name, is the
same "match the well-known real-world convention" discipline this
project has applied to numerics (`417`, `432`, `476`) and admin commands
(`SAJOIN`/`SAMODE`) alike. The IRCv3 `message-tags` specification itself
describes `msgid` as something a `message-tags`-supporting server may
include without it being its own separate capability — no real IRCv3
network gates `msgid` behind a second capability negotiation, so
treating it as anything other than "part of what `message-tags`
provides" would be a project-specific deviation from the convention
clients already expect, for no benefit. `UUID` specifically: a
dependency-free JDK type (`java.util.UUID.randomUUID()`), no new
library needed, and its 122 bits of randomness make a collision within
any realistic connection count (SC-003's ~1,000 concurrent clients) a
non-concern — this project doesn't need a coordinated/sequential id
scheme (which would require shared mutable state across sender threads,
reintroducing exactly the kind of cross-thread coordination the
fan-out model above was designed to avoid) when a random one is
already collision-safe at this scale.

**Alternatives considered**:
- *A separate, independently-negotiable capability for `msgid` (e.g., a
  project-specific `message-ids` capability)*: rejected — this is
  exactly the treatment `server-time` gets, and `server-time` earns it
  because a client might genuinely want tag framing without wanting
  timestamps (or vice versa: a bouncer might want `time` without other
  tag content). No comparable case exists for `msgid` — it's cheap to
  compute, small on the wire, and every realistic `message-tags` client
  benefits from having it (delivery confirmation via `echo-message`,
  future edit/reaction correlation) — inventing a second capability
  negotiation step for something with no plausible reason to decline
  would be complexity with no corresponding client-facing benefit.
- *A monotonically-increasing sequence number instead of a random UUID*:
  rejected — generating one safely across every sender thread (many
  different `ClientSession`s can be "the sender" concurrently) requires
  either a single shared counter (a new point of cross-thread
  contention this project has otherwise deliberately avoided, research.md
  "Networking model") or a per-shard/per-session scheme complex enough
  to need its own design, for a uniqueness guarantee a random `UUID`
  already provides for free.
- *Derive the id from message content (e.g., a hash of sender + body +
  timestamp)*: rejected — content-derived ids collide on legitimate
  duplicate messages (the same client sending the same text to the same
  target twice in the same millisecond is not a hypothetical), and
  FR-059 explicitly requires the id be independent of content for
  exactly this reason.

## Extension system (runtime hot-toggle)

**Decision** *(renamed from "Module system" — see plan.md "Domain Model &
Bounded Contexts")*: A small, custom SPI: each extension implements the
`Extension` interface (`start(ServerContext)`, `stop()`, metadata)
discovered via `java.util.ServiceLoader`. Two role interfaces extend it:
`CapabilityExtension` (also exposes exactly one `Capability`; implemented
by `jircd-capabilities/*`) and `ServerExtension` (no `Capability`;
implemented by `jircd-server-extensions/*`) — see plan.md for why these are
different domain concepts, not just two packages of the same thing. Each
extension is loaded through a dedicated, discardable `URLClassLoader` per
instance so a disabled extension's classes (and any `static` state) can be
fully released and a re-enabled extension gets a fresh load — not just a
"hidden" reference to code still resident in the JVM. `jircd-core` owns an
`ExtensionRegistry` that tracks each extension's lifecycle state and
enforces FR-020 (one extension's failure doesn't affect others).

Three details make this actually work, not just sound plausible:

- **Delegation model**: each extension's `URLClassLoader` delegates to the
  application classloader *first* for any class in `net.jircd.protocol.*`
  or `net.jircd.core.*` (the shared SPI/contract types, including
  `Extension`/`CapabilityExtension`/`ServerExtension` themselves), so both
  the extension and `jircd-core` always see the identical `Class` object
  for those types — avoiding the classic "same class loaded twice" hazard
  where `instanceof`/casts across the extension boundary fail
  unpredictably. Only the extension's own classes (and its private
  dependencies, if any) are loaded by its own loader, which is exactly
  what's discarded on disable.
- **Quiesce before unload**: disabling an extension is two steps, not one
  — (1) `ExtensionRegistry` immediately marks it `DISABLED` so no *new*
  work is routed to it (new CAP negotiations stop offering a
  `CapabilityExtension`, new admin commands are rejected, etc. — this
  alone satisfies most of FR-011/SC-005's "takes effect immediately" bar),
  then (2) the registry waits for any in-flight invocation already running
  on the extension's code to finish (each `Extension` call is made through
  a small wrapper that tracks an in-flight count) before dropping the last
  reference to its classloader. Only after that does the classloader
  actually become collectible. An extension that hangs and never returns
  is itself an FR-020 failure condition — logged (FR-019) and surfaced as
  `FAILED`, not silently waited on forever (a bounded timeout applies).
- **Extension-point ownership**: for cases where an extension supplies a
  value core code consumes (e.g., cloak's hostname-display transform, see
  "Cloak extension boundary"), `ExtensionRegistry` allows at most one
  *enabled* extension to claim a given extension point at a time.
  Attempting to enable a second extension claiming an already-claimed
  extension point is treated as a configuration error (FR-012 style:
  rejected with a specific error naming the conflicting extension ids),
  not a silent override. Only one extension (`cloak`) claims an extension
  point in this release, so this is currently unreachable in practice, but
  the rule is stated now so it doesn't have to be invented ad hoc when a
  second one is added later.

**Rationale**: FR-011 requires enable/disable to take effect **without
restarting the server process** — this is a runtime lifecycle requirement,
not just a build-time packaging concern. `ServiceLoader` alone discovers
implementations but has no notion of unloading; pairing it with a
per-extension classloader gives genuine start/stop/reload semantics with
clean isolation, which is also what FR-020's fault-isolation requirement
needs (an extension's failure must be containable). The delegation and
quiesce details above exist because "use a classloader per extension" is
not by itself sufficient to deliver on FR-011/FR-020 — get the delegation
order wrong and cross-extension type checks break; skip the quiesce step
and an extension can keep running after being reported "disabled," or its
classloader is never actually reclaimed. Splitting `CapabilityExtension`
and `ServerExtension` as role interfaces (rather than one flat `Extension`
type with an optional capability field) makes the domain distinction
enforceable by the compiler: code that only makes sense for negotiated
capabilities (offering something in `CAP LS`) can require a
`CapabilityExtension` specifically, and can't accidentally be handed a
`ServerExtension` that was never meant to be client-visible.

**Alternatives considered**:
- *Java Platform Module System (JPMS)*: JPMS's module graph is resolved and
  fixed at JVM launch (or via `ModuleLayer`, which is possible to create
  dynamically but is a heavyweight, seldom-used API primarily meant for
  frameworks like application servers). It optimizes for strong
  encapsulation and reliable static configuration, not the frequent,
  administrator-driven runtime toggling FR-011 describes. Using it here
  would fight the requirement rather than serve it.
- *OSGi*: the traditional Java answer to exactly this problem
  (dynamic module lifecycle, isolated classloading), but it is a large
  framework with its own deployment model, bundle manifest format, and
  learning curve — disproportionate to a project with 5 initial optional
  extensions across `jircd-capabilities/` and `jircd-server-extensions/`
  (3 capability extensions, cloak and admin as server extensions —
  moderation and the capability-negotiation mechanism itself are core and
  not part of this system at all, see FR-035/FR-036). Revisit if the
  extension count and third-party-plugin ambitions grow.
- *A single flat `Extension` type with no `CapabilityExtension`/
  `ServerExtension` split*: fewer types, but loses the compiler-enforced
  distinction described in Rationale, and matches the code's model to
  reality less closely — the earlier, undifferentiated "Module" design
  this replaces.
- *No isolation, just a registry of enabled booleans*: simplest option, but
  fails FR-020 (an extension that leaks state or throws during static init
  can still affect the whole process) and doesn't give a clean "reload"
  story.

## Channel/user mode extensibility (FR-043/FR-044)

**Decision**: `Channel.activeModes` is a `Set` of `ChannelMode` (data-model.md)
— a Value Object with its own stable `id`, a wire `flag` character, a
`kind` (`BOOLEAN`/`VALUE`/`LIST`/`MEMBER`), and who defines it (`CORE` or a
`ServerExtension`) — not a closed enum. Core unconditionally contributes
exactly two, both `BOOLEAN`: `moderated` (`m`) and `members-only` (`n`)
(FR-013), never gated by `Extension` state (FR-036), the same guarantee
the "Extension system" decision above already gives channel moderation as
a mechanism. A `ServerExtension` MAY additionally contribute further
`BOOLEAN` flags via a new `contributedChannelModes` field (data-model.md
`Extension`) — the same "an extension contributes a named thing core
aggregates" pattern already established for `CapabilityExtension`/
`Capability`, not a new mechanism. No extension contributes one in this
release; the field exists so a future one (e.g., an account module's
registered-channel/user flag, à la classic IRC `+r`) is purely an
extension addition, not a core data-model change. User modes (FR-044) are
expected to follow the identical pattern once the first one is actually
designed, rather than a separate, incompatible mechanism — nothing is
modeled for them yet since none exist.

**Rationale**: The original `sendRestriction` enum (`NONE`/`MEMBERS_ONLY`/
`MODERATED`) was a real design flaw, not just a naming choice: standard
IRC channel modes are an open, per-flag namespace (RFC 2811), and this
project's own roadmap already names a concrete future consumer — Story
3's account module wanting a registered-channel/user flag. A closed enum
would force a core code change (and a new release) for exactly the kind
of extension FR-011 exists to add without one. It was also already subtly
wrong even without that future case: `MEMBERS_ONLY` and `MODERATED` are
independent flags on real IRC servers (a channel can have both, either,
or neither), and a mutually-exclusive enum couldn't represent that —
fixed as part of the same change, not a separate concern.

**`id` vs. `definedBy`-only identity**: An earlier draft of `ChannelMode`
identified a flag only by its `flag` character plus `definedBy` (`CORE` or
an extension id) — no separate name of its own. Validated and rejected in
favor of adding a proper `id`, for three concrete reasons, not just
"consistency for its own sake":
1. **The wire-letter namespace is the actually scarce resource, and
   conflating it with identity makes conflicts harder to report.**
   `flag` has only 52 possible values, shared by every current and future
   core and extension flag combined; two independently-developed
   extensions choosing the same letter for *different* concepts is a real
   collision risk (unlike, say, `Capability` names, which are long enough
   that IRCv3's own convention of vendor-prefixing, e.g. `draft/multiline`,
   makes collisions rare in practice). When that collision happens, the
   rejection error needs to name *what* conflicts, not just *which letter*
   — "extension `registered-channel-plus`'s `registered` flag conflicts
   with extension `read-only-mode`'s `readonly` flag, both claiming `r`"
   is the kind of specific, actionable error the constitution's Principle
   III requires; "flag `r` is claimed twice" is not.
2. **Every other extensible, administrator-facing concept in this data
   model already has a stable `id` independent of its wire form**:
   `Extension.id`, `Capability.name`. A `ChannelMode` identified only by a
   single wire letter would be the one exception, for no principled
   reason — administrators and logs would have no legible way to refer to
   a specific mode.
3. **It costs one field.** `ChannelMode` is already a new Value Object
   with a defining-source field the closed enum didn't have; adding `id`
   alongside `flag` is a marginal addition to a type already being
   introduced, not a separate migration.

**Considering the rest of RFC 2811's standard channel modes surfaced a
second, more important finding**: most of them are not simple on/off
flags. `l` (user limit) and `k` (channel key) each carry a value;
`b`/`e`/`I` (ban/exception/invite-exception) are each a list of masks;
`o`/`v` (operator/voice) are per-*nickname* privileges, not per-channel
state at all — `o` is already exactly what `Channel.operators` models,
just not yet mutable via `MODE`. `Channel.activeModes: Set<ChannelMode>`
can only represent the on/off case. Rather than silently ignoring this
until someone tries to build a `VALUE`/`LIST`-kind extension and discovers
the shape doesn't fit, `ChannelMode.kind` classifies every flag now
(contracts/irc-protocol-commands.md "Full Channel Mode Catalog" catalogs
all eleven), and the `ChannelMode` validation rules explicitly forbid
contributing a `VALUE`/`LIST`-kind flag in this release: `Channel`'s shape
would need to grow (e.g. a `modeParameters` map) to hold one, and that
shape change is deliberately left undesigned until a real consumer
exists, rather than speculatively built for zero current uses — the same
judgment call "Alternatives considered" below already makes for a
general-purpose data bag, applied one layer more specifically.

**`MEMBER`-kind flags in practice (FR-045/FR-046)**: `voice` was this
release's first `MEMBER`-kind `ChannelMode`, and it validated the
taxonomy above: its state doesn't live in `activeModes` at all (a `Set`
can't express "which members," only "which flags") but in its own
dedicated `Channel.voiced` field. `operator` follows the identical
pattern via `MODE +o`/`-o <nickname>` (FR-046) and `Channel.operators` —
originally scoped out on the grounds that first-join-gets-operator
(FR-013) already answers "how does a member become one" and nothing
required a second path, but revisited once "an operator can grant
operator status to another member" was named as an explicit requirement.
Adding it cost nothing new: `Channel.operators` already existed (FR-013
depends on it), so unlike `voice`, no new `Channel` field was needed —
only a `ChannelMode` catalog entry and a `MODE` handler branch identical
in shape to `voice`'s. First-join-gets-operator (FR-013) remains the
*only* way a channel's first operator is established; `MODE +o` is how
operator status subsequently spreads to other members, the same
relationship voice's grant mechanism has to moderated-mode's send-check.

**Validating the extensibility promise against a future `JOIN`-gating
flag**: FR-043 promises a future `ServerExtension` can contribute a new
channel-mode flag "without requiring a change to the server's core
codebase." Checked against a concrete, near-certain future case — an
invite-only extension, gating `JOIN` rather than `PRIVMSG`/`NOTICE` — the
promise as originally built did **not** hold: every enforcement rule
written so far (the `PRIVMSG`/`NOTICE` validation rule above) was phrased
specifically in terms of "does the sender pass `MEMBERS_ONLY`/`MODERATED`,"
with `MessageCommandHandler` as the only place any `ChannelMode` was ever
checked. A flag gating `JOIN` would have had nowhere to plug in without
editing `JoinCommandHandler` to add hardcoded awareness of the new flag —
exactly the "requires a core codebase change" outcome FR-043 exists to
rule out. This was a real gap in an already-committed requirement, not a
hypothetical concern: it surfaced by asking the one question that matters
for any "extensible by design" claim — does it hold for the *next*
concrete case, not just the cases already built?

**Fix**: `ChannelMode` gains a `gates: Set<GateableAction>` field
(`SEND`, `JOIN` today; data-model.md), decoupling "which command does
this flag restrict" from `kind` ("what shape is this flag's data").
Every gateable command's handler iterates currently-active flags whose
`gates` includes its own action and defers the pass/fail decision to
whoever defines that flag — `CORE`'s own logic for the built-ins,
an extension's own logic for a contributed one — rather than the handler
hardcoding per-flag-id knowledge. This is not a new architectural idea:
it's the same "extension contributes a named thing + a hook the relevant
code calls generically" pattern `CapabilityExtension` already uses (a
capability extension exposes a formatting hook `SessionWriter` calls per
recipient; it doesn't get hardcoded into `SessionWriter` itself). Two
findings fell out of applying it:
- Most future `JOIN`-gating `BOOLEAN` flags would not require
  `Channel`'s shape to grow at all — the gate hook receives the acting
  session and channel, and whoever defines the flag is free to keep its
  own bookkeeping alongside it. `invite-only` (FR-065, "Channel
  invitations" below) turned out to be the concrete case that exercised
  this: its bookkeeping (`Channel.invited`) did need a small, purpose-
  built `Channel` field of its own, the same way `ban-mask`'s did — the
  promise held (no `JoinCommandHandler` change was needed), it just
  didn't turn out to be *entirely* free of any data-model addition,
  the way a flag needing no bookkeeping at all (e.g., a hypothetical
  registered-channel flag that only consults external account state)
  still would be.
- An extension registering its own command alongside a contributed
  mode flag (e.g., claiming an until-then-recognized-but-unimplemented
  command) was already solved before any concrete case needed it:
  `admin` and `cloak` already register their own command handlers into
  `jircd-core`'s dispatch table when enabled (research.md "Extension
  system"), so no new mechanism was needed there — though `invite-only`
  and `INVITE` (FR-065) ultimately shipped as `CORE`, not an extension
  (see "Channel invitations" below), so this particular promise is
  still validated only by `admin`/`cloak`'s existing extension-defined
  commands, not by `INVITE` itself.

What this fix does **not** claim to solve on its own: `p`/`s`
(private/secret) restrict *visibility* — whether a channel appears in
`TOPIC`/`NAMES`/`LIST` output at all for a non-member — not a simple
permit/deny of attempting an action, which `SEND`/`JOIN` both are. This
turned out to fit `GateableAction` anyway, with one addition: a third
value, `DISCOVER` (FR-047, data-model.md `ChannelMode`), whose gate
*failure* convention differs from `SEND`/`JOIN`'s — it MUST produce the
same response as "channel doesn't exist," not a distinguishable
permission error, since the entire point of `private`/`secret` is that a
non-member can't tell those two cases apart. `TOPIC`-viewing, `NAMES`,
and `LIST` were already grouped under this project's own "discovery
operations" terminology (contracts/irc-protocol-commands.md) before this
change, which is exactly why one shared `DISCOVER` action — checked once
by each of the three handlers, not three separately-named actions — was
the right shape rather than `TOPIC_VIEW`/`NAMES`/`LIST_ENTRY` individually.
`private`/`secret` are `CORE`-defined, not extension-contributed, and
grant an `administratorPrivilege` bypass (FR-047) — mirroring FR-032's
existing hostname-cloaking transparency guarantee for administrators,
extended here to channel visibility.

**`LIST`-kind flags in practice: ban-mask (FR-062)**: `ban-mask` (`b`)
is this release's first — and, for now, only — `LIST`-kind `ChannelMode`
actually implemented, giving the deferral above ("`Channel`'s shape
would need to grow... deliberately left undesigned until a real
consumer exists") its real consumer. `Channel` gains a
`bans: List<BanEntry>` field (data-model.md), where `BanEntry` is a new
Value Object (`mask`, `setBy` — the setting operator's nickname,
`setAt` — an instant), the accountability metadata real IRC clients
already expect a ban-list UI to show and which costs nothing extra to
capture (`Channel.operators`/the acting session's nickname and the
current time are both already available at the point `MODE +b` is
processed). `ban-mask` doesn't occupy a slot in `activeModes` the way
`BOOLEAN`-kind flags do — like `MEMBER`-kind flags before it, its state
lives in its own dedicated field, `Channel.activeModes` staying
exactly what its own decision above says it is: a `Set` that can only
represent "which `BOOLEAN` flags are on," nothing more.

`gates: {SEND, JOIN}`, not `{JOIN}` alone: RFC 1459/2811's original text
only describes `+b` blocking future joins, but the modern IRC client
protocol specification — the maintained, current documentation those
frozen RFCs no longer track — defines `ban-mask` as controlling masks
"banned from joining **or** speaking in the channel." Gating both
actions is therefore the spec-aligned reading of `ban-mask`, not an
invented, stricter-than-standard extension of it (see Alternatives
considered, below, for the one place this project *does* stop short of
a fuller reading: no automatic `KICK` on ban).

This is also the first `gates: {SEND, JOIN}` flag whose gate check
isn't "is this flag present in `activeModes`" at all (the check every
`BOOLEAN`-kind flag above uses) — `ban-mask` is always "present" in the
sense that its restriction is always live, and what varies per-check is
whether the *specific acting session* currently matches any entry in
the list. This is exactly the generalization the `gates` mechanism was
designed to allow (research.md above: "The pass/fail decision for each
flag is provided by whoever defines it... not hardcoded per-flag-id
inside the command handler") — `MessageCommandHandler`'s and
`JoinCommandHandler`'s `SEND`/`JOIN`-gate check points now iterate every
currently-recognized flag whose `gates` includes their action (not just
those in `activeModes`), asking each one's own kind-appropriate
predicate: a `BOOLEAN` flag's predicate is "am I currently in
`activeModes`, and if so does the actor satisfy my exemption (e.g.
operator/voice)"; `ban-mask`'s predicate is "does the actor's current
`UserIdentity.presentedForm` **or** `ClientSession.realHostname`-based
identity match any entry in `Channel.bans`" — a different question,
answered by the same mechanism, exactly validating
FR-043's extensibility promise a second time (research.md above already
validated it once, hypothetically, for a future `JOIN`-gating flag —
`invite-only` (FR-065) later became that flag for real; `ban-mask` is
the one that first needed both `SEND` and `JOIN` gating from *core*
itself, ahead of any concrete `JOIN`-only case).

Mask matching checks **both** of a target's identities independently —
its presented form (`UserIdentity.presentedForm`, FR-030/FR-031) and its
real, unobfuscated form (`nickname!ident@ClientSession.realHostname`,
FR-032) — and the ban applies if *either* matches. This was originally
designed as presented-form-only, on the reasoning that matching against
the real value would grant every channel operator FR-032's
administrator-only real-hostname visibility through the back door; that
reasoning conflated two different things (*seeing* a real value vs. a
*pattern happening to match* one) and was corrected once the actual
requirement was stated explicitly: an operator specifying, say,
`*!*@*.example.com` never learns any member's real hostname/IP from
doing so — they only ever observe the same binary outcome any ban
produces (a match mutes/blocks; a non-match doesn't), exactly as
opaque as before. What dual-matching actually buys is ban-evasion
resistance: without it, a client could dodge a mask like
`*!*@1.2.3.4` — a pattern chosen specifically because it targets the
member's real, persistent network identity — simply by having a
cloaking extension present a different value, even though nothing
about who they are changed. Checking the real identity too closes that
gap, and does so uniformly for *every* ban any operator adds, not as a
privilege-gated special case (see "Administrator channel override"
above for the `SABAN` design this replaced). A partial mask (e.g., bare
`alice`, or `*@example.net`) has its missing
`user`/`host` segment(s) filled with `*` before being stored — standard
IRC ban-mask convention, and simple to apply once at `+b` time rather
than at every match. `*`/`?` wildcard matching against the full
`nick!user@host` string is case-insensitive (ASCII fold, consistent
with this project's other casemapping decisions, research.md "IRC
casemapping" — applied uniformly across the whole string rather than
mixing casemapping rules per segment, which would be more "correct" per
RFC nuance but adds complexity nothing here needs).

Numerals: `367 RPL_BANLIST` (one per active mask) then `368
RPL_ENDOFBANLIST` for the query form — both exact-fit, previously
unused RFC 2811 numerals; `474 ERR_BANNEDFROMCHAN` for a banned
client's rejected `JOIN` — RFC 2812's own numeral for exactly this
case; `478 ERR_BANLISTFULL` when an addition would exceed the fixed
per-channel cap (100 entries — a fixed constant, not administrator-
configurable, chosen as a conventional real-world ircd default; this
project's config-value additions so far — FR-056, FR-061 — have all
been genuinely administrator-tunable operational limits with a real
reason to vary by deployment, whereas a ban-list cap exists purely as a
resource-growth safety bound with no comparable reason an administrator
would need to retune it per deployment). Muting an already-matched, already-present
member reuses `404 ERR_CANNOTSENDTOCHAN` — which also, as a side effect
of implementing this, retroactively fixes a real, pre-existing gap:
`moderated`-mode's own `SEND`-gate rejection had never actually been
pinned to a specific numeral anywhere in this project (only
`members-only`'s `442 ERR_NOTONCHANNEL` was), an oversight this project
has repeatedly caught and fixed for other flags/commands; `404` is the
correct, exact-fit numeral for both cases now — "you're in the channel,
but a channel restriction still blocks you from sending" — distinct
from `442`'s "you're not even a member" case.

**Alternatives considered**:
- *Keep the enum, widen it each time a new mode is needed*: rejected —
  exactly the "requires core codebase changes" outcome the Extension
  system exists to avoid (FR-011).
- *A raw `Set<Character>` with no defining-source metadata*: rejected —
  loses the ability to say a flag stops being recognized when its owning
  extension is disabled (FR-020), and loses a place to attach each flag's
  semantics; `ChannelMode` costs one small Value Object for that.
- *Identify a `ChannelMode` by `flag` + `definedBy` alone, no separate
  `id`*: rejected — see "`id` vs. `definedBy`-only identity" above.
- *Pre-design `VALUE`/`LIST` storage on `Channel` now, since RFC 2811
  already defines what they'd need*: rejected as premature — no extension
  needs one yet, and guessing the right shape (a map? a richer per-flag
  state object?) without a concrete consumer risks designing the wrong
  thing and having to redesign it anyway once one exists.
- *A general-purpose "arbitrary key-value extension data" bag on
  `Channel`*: rejected as premature generality — `ChannelMode` solves the
  one concrete, named case (mode flags) this decision exists for, without
  inventing a schema-less mechanism nothing else in this project needs.
- *Leave `gates` out and let each command handler special-case which
  flag ids it cares about (as originally built)*: rejected — this is
  exactly the design validated and found insufficient above; it silently
  breaks FR-043's extensibility promise for any flag that isn't
  `SEND`-gating, and nothing about it would have surfaced that until
  someone actually tried to build a `JOIN`-gating extension and hit a
  wall.
- *A single `gates: GateableAction` (one value, not a set)*: rejected in
  favor of a set — costs nothing extra for this release's six flags
  (each populates zero or one value today), but a set doesn't foreclose
  a hypothetical future flag gating more than one action, and a set is
  no harder to check ("does `gates` contain this action") than a single
  value would be.
- *Three separate gateable actions (`TOPIC_VIEW`, `NAMES`, `LIST_ENTRY`)
  instead of one shared `DISCOVER`*: rejected — `TOPIC`-viewing, `NAMES`,
  and `LIST` already share identical membership-independence and
  identical `private`/`secret` semantics; three actions would just mean
  `private`/`secret` populate `gates: {TOPIC_VIEW, NAMES, LIST_ENTRY}`
  every time, in lockstep, with no case where a future flag would want
  one but not the others. One `DISCOVER` value says the same thing with
  fewer moving parts.
- *Implement `private` and `secret` with distinct behavior (RFC 2811's
  "listed but obscured" nuance for `private`)*: rejected for this
  release — real-world ircds disagree enough on `private`'s exact LIST
  behavior that guessing one would risk enshrining a convention nothing
  else agrees on; treating them identically (full hiding, like `secret`
  unambiguously requires) is simpler, defensible, and easy to relax later
  if a concrete reason to distinguish them ever shows up.
- *Automatically `KICK` an already-present member the moment a matching
  ban is added, instead of only muting them*: rejected — this project
  implements exactly what was specified: mute in place, not remove.
  Muting is not an invented deviation from RFC 1459/2811's original,
  narrower `JOIN`-only wording — the modern IRC client protocol
  specification (the maintained, current successor documentation those
  RFCs no longer track) defines `+b` explicitly as controlling masks
  "banned from joining **or** speaking in the channel," so gating both
  `JOIN` and `SEND` is the *correct*, spec-aligned reading of `ban-mask`,
  not a stricter-than-standard choice. Auto-`KICK`-on-ban, by contrast,
  genuinely would be this project inventing behavior no version of the
  ban-mode specification (classic or modern) describes — rejected for
  that reason, not because muting-in-place was already "one deviation
  too many": an operator who wants both effects can still issue `KICK`
  explicitly (FR-013), a single extra command, not a missing capability.
- *Match ban masks against a member's real hostname/IP **instead of**
  (not in addition to) their presented identity*: rejected — an
  operator without a cloak-evasion concern would have no way to write a
  mask that reliably matches what they actually see in every member's
  message hostmask; matching *both* independently (adopted, see
  Rationale above) covers the ordinary case (presented identity) and the
  evasion-resistant case (real identity) without forcing a choice
  between them.
- *Match only the real hostname/IP was initially rejected as a privacy
  escalation, on the reasoning that it would let a channel operator use
  `MODE +b` as a backdoor around FR-032's administrator-only
  real-hostname visibility*: this reasoning was itself the error,
  corrected once stated precisely — see Rationale above. Matching
  against a value never *exposes* that value to whoever wrote the
  pattern; it only reports whether the pattern currently matches
  something, the same non-disclosing signal every ban already produces.
  No visibility escalation actually occurs.
- *No per-channel ban-list cap at all*: rejected — this is an
  operator-populated, unbounded-by-default list on a long-lived
  `Channel` aggregate, exactly the kind of unbounded growth this
  project bounds everywhere else it appears (FR-016's rate limits,
  FR-049's line length, FR-056's name/topic lengths); `478
  ERR_BANLISTFULL` already exists, reserved, specifically for this
  case — using it costs nothing and closes a real, if minor, resource
  concern.

## MODE command grouping (FR-064)

**Verifying the formal basis first**: RFC 2812 §3.2.3 defines `MODE`'s
own grammar as `MODE <channel> *( ( "-" / "+" ) *<modes> *<modeparam>
)` — a repeatable sequence of signed groups, each carrying zero or more
mode letters (`*<modes>`) and the parameters (`*<modeparam>`) they
consume. This is not a convention layered on top of the base protocol;
it's the base grammar itself, and every deployed IRC server implements
it — this project's earlier single-flag-per-command design was a real,
unnecessary narrowing of what `MODE` already allows, not a faithful
minimal implementation of it. What the base RFC does *not* define is
how many parameter-consuming changes a server must accept in one
command; that cap is server-defined and conventionally advertised via
the `MODES` token in `RPL_ISUPPORT` (data-model.md `SupportedFeatures`,
already implemented, FR-055) — the same de facto/"modern IRC"-standard
category `CASEMAPPING`/`CHANMODES`/`PREFIX` already belong to, not
something this project is introducing a new category for.

**Decision**: `jircd-protocol` gains `ModeStringParser`, a pure,
stateless utility (no `jircd-core` dependency, reusable by a future
client library the same way `NickMask`/`Hostmask`/`ChannelName` already
are) that parses a modestring like `+bbb-o` into an ordered list of
`(sign, flag)` pairs — `[(+,b), (+,b), (+,b), (-,o)]` — with no
knowledge of what any flag *means* or whether it needs a parameter;
that's `ChannelMode.kind`'s job, which `jircd-core`'s `ModeCommandHandler`
already has access to. `ModeCommandHandler` then walks that list
left-to-right, and for each `(sign, flag)`:
1. Resolves `flag` against the currently-recognized `ChannelMode`
   catalog (core plus enabled extensions) — an unknown flag stops
   processing here (see step 4) and replies `472 ERR_UNKNOWNMODE`.
2. If the flag's `kind` needs a parameter (`MEMBER` or `LIST` — `VALUE`
   would too, but none exist yet) and the count of parameter-consuming
   flags already applied in this command has reached
   `ServerConfiguration.maxModesPerCommand`, processing stops here —
   silently, no reply beyond whatever `MODE` confirmation already
   covers what was applied before this point (see Rationale for why no
   error).
3. If the flag needs a parameter and none remains in the command's
   parameter list, processing stops here and replies `461
   ERR_NEEDMOREPARAMS`.
4. Otherwise the flag is applied (consuming the next parameter if it
   needed one) using the exact same per-`kind` logic already defined
   for a single flag (FR-045/FR-046's `MEMBER` grant/revoke, FR-062's
   `LIST` add/remove/normalize, `BOOLEAN`'s `activeModes`
   toggle/mutual-exclusion) — nothing about *how* an individual flag is
   applied changes, only that many can now be attempted in one command.
   An applied flag's own failure (e.g. `MEMBER`-kind naming a
   non-member, `441 ERR_USERNOTINCHANNEL`) also stops processing at
   that point, the same as steps 1/3.

Processing is deliberately **not atomic**: a flag applied before a
later stop condition stays applied — `MODE #chan +ov nick1 baduser`
still grants `nick1` operator status even though `baduser` (not a
member) halts processing at `+v` with `441`. This matches real deployed
ircd behavior and needs no new rollback mechanism this project would
otherwise have to invent. The `MODE` confirmation echoed to channel
members (FR-013/FR-045/FR-046/FR-062's existing echo requirement)
reflects only the flags actually applied — never the originally
requested set when the two differ, so the echo itself is always an
accurate record of what happened, doubling as the client's only
feedback for the silent cap-exceeded case (step 2).

`ServerConfiguration.maxModesPerCommand` (positive integer, default `6`,
capped at `20`) feeds `SupportedFeatures`'s `MODES` token the same way
`nicknameMaxLength`/`channelNameMaxLength`/`topicMaxLength` already feed
`NICKLEN`/`CHANNELLEN`/`TOPICLEN` (data-model.md `SupportedFeatures`) —
recomputed on `ServerConfiguration` load/reload, not per-command, and
`MODES` stops being a `Fixed: 1` constant.

**Rationale**: The single-flag-per-command design was a real,
unrequested restriction — nothing about it was a deliberate scope
decision documented anywhere in this project, it was simply the
shape the first `MODE` implementation happened to take, and the
formal grammar it was quietly narrowing was never checked against
until now. `6` as the default matches a long-standing, still-common
convention among deployed ircds (values in the 3-6 range are typical;
this project picks the more generous, still-conventional end).
Stopping (not skipping-and-continuing) at the first unresolvable flag
in a group keeps the processing model simple — one linear pass with
one clear stopping point — rather than a "collect all failures, apply
all successes out of order" model that would need its own
harder-to-reason-about semantics for no clear benefit. Not emitting an
error for the cap-exceeded case specifically (as opposed to the
missing-param/unknown-flag/invalid-target cases, which all keep their
existing numerals) is the one deliberate departure from this project's
otherwise-consistent "reject explicitly" posture (FR-049, FR-054,
FR-056, FR-062's ban-list cap all reject with a specific numeral) —
justified narrowly because, unlike those cases, no RFC numeral or
later widely-adopted addition actually covers this one, and this
project's own discipline (research.md "Wire-protocol command & numeric
completeness") is to reuse an exact-fit existing numeral or a genuine
de facto standard, never invent one from nothing. The truncated,
accurate `MODE` echo is a real, non-silent feedback channel, just not
a numbered error reply.

**Alternatives considered**:
- *Reject the entire command if its parameter-consuming flag count
  exceeds the configured cap, rather than applying up to the limit*:
  rejected — this would need a numeral this project has no honest
  candidate for (see Rationale), and would also discard flags that
  legitimately fit within the limit purely because later ones in the
  same command didn't — a well-behaved client that undershoots the
  limit slightly would still lose everything. Applying what fits is
  strictly more useful and no harder to specify correctly.
- *Invent a project-specific numeral for the cap-exceeded case*:
  rejected — directly against this project's established numeral
  discipline; a numeral that only this server sends would defeat the
  interoperability point of reusing RFC/de-facto-standard numerals in
  the first place.
- *Make the entire multi-flag command atomic (all flags succeed or none
  do, with rollback on any failure)*: rejected — no real deployed ircd
  works this way, it would require a transaction/rollback mechanism
  this project has no other use for, and it would make a single typo'd
  flag in an otherwise-valid batch discard every other change in it,
  worse UX than partial application for no compensating benefit.
- *Count `BOOLEAN`-kind flags toward the `maxModesPerCommand` cap too,
  not just parameter-consuming ones*: rejected — the `MODES`
  `RPL_ISUPPORT` token's own conventional meaning ("maximum number of
  channel modes **with parameters**...") is specifically about
  parameter-consuming changes; `BOOLEAN` flags never consume a
  parameter, and FR-049's line-length limit already bounds how many
  flags of any kind a single command can physically carry, so a second,
  narrower cap on non-parameterized flags would just be an invented
  restriction nothing requires.

## Channel invitations (FR-065)

**Core vs. extension, revisited**: `invite-only` had already appeared
twice in this specification before it was ever scoped for real — once
as FR-043's worked example of a future `JOIN`-gating flag, once as the
Extension Key Entity's example of a flag a `ServerExtension` would
contribute. Both mentions were about validating the `gates` mechanism
itself (research.md above, "Validating the extensibility promise
against a future `JOIN`-gating flag"), and that validation still holds:
nothing about the mechanism assumed `invite-only` specifically would
end up extension-defined, only that *some* future `JOIN`-gating flag
might be. Once `invite-only` moved from hypothetical example to an
actual, in-scope feature, checking it against this project's own
precedent settled the question the other way: every other
currently-implemented channel-mode flag — `moderated`/`members-only`
(FR-013), `private`/`secret` (FR-047), `voice`/`operator` (FR-045/
FR-046), `ban-mask` (FR-062) — is `CORE`-defined, unconditional
behavior; only `cloak` and `admin`, genuinely optional cross-cutting
capabilities a deployment might reasonably run without, are modeled as
`ServerExtension`s. `invite-only` and its paired `INVITE` command
(RFC 2812 §4.2.3, §3.2.7) sit in the first category by the same
reasoning that put `ban-mask` there: both are base-RFC channel
moderation behavior a Story 5 deployment needs, not optional add-ons.
Treating it as `CORE` costs nothing new architecturally — the `gates`
mechanism doesn't care who defines a flag, only that a definition
exists — so this is purely a placement decision, not a design
constraint the mechanism forced.

**Decision**: `Channel` gains one new field, `invited: Set<String>`
(data-model.md), holding casefolded nicknames currently holding a
usable invitation on that channel — the same "each `kind` gets its
own purpose-built storage" pattern `bans`/`operators`/`voiced` already
established, not a generic reusable invite-list mechanism (this
project's standing "don't generalize until a second concrete consumer
exists" discipline, research.md "`LIST`-kind flags in practice").
`invite-only` (`i`) becomes an eighth `CORE`-defined `ChannelMode`:
`BOOLEAN`-kind, `gates: {JOIN}`, set/cleared by an operator via `MODE
+i`/`-i` exactly like `moderated`/`members-only`/`private`/`secret`
already are. Its `JOIN`-gate predicate: pass automatically if
`invite-only` isn't currently active on the channel; otherwise pass
only if the joiner's current (casefolded) nickname is in
`Channel.invited` — evaluated as its own independent flag in the same
generic "does the actor pass every currently-recognized flag whose
`gates` includes `JOIN`" loop `ban-mask` already exercises
(data-model.md `Channel` validation rules), never combined into one
condition with `ban-mask`'s own predicate. This is what makes "an
invitation bypasses `invite-only` but never a ban" fall out for free:
nothing needs to special-case the interaction, since each gating flag
is checked on its own and a `JOIN` only succeeds if *every* one of
them passes. A successful `JOIN` removes the joining nickname from
`Channel.invited` if present, whether or not `invite-only` was the
reason a check against it ran — an invitation that outlived its
purpose (e.g., the channel wasn't invite-only after all, or stopped
being before the target joined) shouldn't linger as stale state either.
`INVITE <nickname> <channel>` (RFC 2812 §3.2.7) is a new command,
implemented in `jircd-core`, not `jircd-protocol` (the wire-level
grammar was already recognized, per the Full Command Catalog): the
sender MUST currently be a member of `<channel>` (`442
ERR_NOTONCHANNEL` otherwise — this also naturally covers "the channel
doesn't exist," the identical treatment `KICK`/`PART`/channel `MODE`
already give that case); `<nickname>` MUST resolve to a currently
connected client (`401 ERR_NOSUCHNICK`) that isn't already a member of
`<channel>` (`443 ERR_USERONCHANNEL`); if `invite-only` is currently
active, the sender MUST additionally be a channel operator (`482
ERR_CHANOPRIVSNEEDED`) — otherwise any current member may invite,
matching modern IRC client protocol documentation's own wording ("only
members of the channel are allowed to invite other users... when the
channel has invite-only mode set, only channel operators may issue
`INVITE`"). On success: the target's casefolded nickname is added to
`Channel.invited`; the sender receives `341 RPL_INVITING <nick>
<channel>` — the modern-docs parameter order (`<client> <nick>
<channel>`), not RFC 2812's own, older `<channel> <nick>` ordering,
the same "modern IRC client protocol documentation wins over a frozen
RFC's wording when the two disagree" precedent `ban-mask`'s dual-gating
already established (research.md "`LIST`-kind flags in practice");
the target receives an `INVITE` message from the sender. `SAJOIN`
(FR-057) already bypasses every currently-recognized `JOIN`-gating
flag unconditionally, so it required no change to add `invite-only` to
what it bypasses — the same "wired to the generic gate check point
once, extended automatically" outcome `ban-mask` already demonstrated.

**Rationale**: No expiration timeout and no per-channel cap on
`Channel.invited`'s size, unlike `Channel.bans`' fixed 100-entry limit
— see spec.md Assumptions (FR-065) for why: an invitation is
self-bounding, transient churn (consumed on the target's next `JOIN`
or discarded wholesale on channel recreation), not accumulating,
administrator-visible standing state the way a ban list is, and no
RFC/de-facto numeral exists for "too many outstanding invitations" to
invent a rejection around. An invitation is nickname-keyed, not
identity-keyed — if its target changes nickname before joining, the
invitation simply goes stale under the old name, exactly the same
"captured at creation time, not tracked across a rename" treatment
`BanEntry.setBy` already gives the operator who created *that* record;
no special-case cleanup or transfer logic exists for either.

**Alternatives considered**:
- *Ship `invite-only`/`INVITE` as a new `ServerExtension`, matching the
  two earlier "future extension" mentions exactly as written*:
  considered and rejected once actually scoped — see "Core vs.
  extension, revisited" above. Every other implemented channel-mode
  flag is `CORE`; making this one the sole administrator-toggleable
  exception among Story 5's moderation flags would be inconsistent
  with no benefit, since nothing about `invite-only` is genuinely
  optional the way `cloak`'s hostname obfuscation or `admin`'s
  privileged command set are.
- *Let a held invitation also bypass a matching ban entry*: rejected —
  no version of the ban-mode specification (classic or modern)
  describes `INVITE` as a ban override, and this project has
  consistently avoided extending a flag's effect past what a real
  specification actually says (research.md "`LIST`-kind flags in
  practice" — the identical reasoning that rejected auto-`KICK`-on-ban).
  An operator who wants both effects can remove the ban explicitly, a
  separate, deliberate `MODE -b`, not an implicit side effect of
  `INVITE`.
- *Expire an invitation after a fixed timeout, or cap how many a
  channel may hold outstanding*: rejected — see Rationale above; no
  RFC/de-facto convention defines either, and this project's existing
  flood/rate-limiting (FR-016) already bounds how quickly invitations
  can be issued, the same general-purpose safeguard covering every
  other high-frequency command here.
- *Transfer or re-key an outstanding invitation when its target changes
  nickname*: rejected as unnecessary machinery for a case this project
  already accepts elsewhere (a stale `BanEntry.setBy` after the setting
  operator renames) — an invitation going stale on rename is a minor,
  self-correcting inconvenience (the inviter can simply invite the new
  nickname), not a correctness problem worth new tracking logic for.

## User mode: `operator` (FR-034/FR-044)

**Decision**: `UserMode` (data-model.md) is a new Value Object, deliberately
a much smaller sibling of `ChannelMode` rather than a reuse of the same
type — it has `id`, a wire `flag` character, `definedBy` (`CORE` or a
`ServerExtension`), and `clientSettable` (boolean), but no `kind` and no
`gates`: every user mode this release's design actually needs to
represent is a plain boolean, and none of them needs to gate a second
command the way `ChannelMode` flags gate `SEND`/`JOIN`/`DISCOVER`, so
those two `ChannelMode` fields would be pure unused surface if copied
over. *(Revised from this decision's original shape, which asserted
"exactly one, fixed rule for who may set it" and had no `clientSettable`
field at all — true when `operator` was the only flag, no longer true
now that `invisible` exists with the opposite rule; see "WHO and
invisibility" below for why a field, not a second special case, is the
right fix.)* Core contributes two entries: `operator` (`o`,
`clientSettable: false`) and `invisible` (`i`, `clientSettable: true`,
FR-061). `ClientSession` gains a `userModes: Set<UserMode>` field.
`operator`'s membership in it is never independently toggled — it is
set and cleared as a direct side-effect of
`ClientSession.administratorPrivilege` changing, in both directions:
- `OPER` succeeding (FR-034, `OperCommandHandler`) sets both
  `administratorPrivilege = true` and adds `operator` to `userModes` in
  the same operation.
- A session clearing `operator` on itself via `MODE <self> -o` clears
  both `userModes` and `administratorPrivilege` — not just the visible
  flag. A user mode that could show "not an operator" while
  `administratorPrivilege` was still silently `true` (or vice versa)
  would be a real, security-relevant lie to any observer trying to
  reason about who currently holds administrative power (including the
  session's own client displaying its own modes) — this project's
  established "don't let two representations of one fact drift apart"
  discipline (data-model.md `SupportedFeatures`'s `CHANMODES`/`004`
  consistency guarantee is the same discipline, applied to a different
  pair of facts).

`MODE`'s user-targeted form is scoped narrowly: `MODE <nickname>` with no
mode string is a query (`221 RPL_UMODEIS`); `MODE <nickname>
<+/->o` and `MODE <nickname> <+/->i` are the only settable forms this
release defines. A target other than the sender's own current nickname
is rejected outright (`502 ERR_USERSDONTMATCH`, RFC 2812's own numeral
for exactly this) — this release has no "set another user's modes"
capability, mirroring the same self-only scope decision FR-058's
`SAMODE` already made, for the same reason (nothing has asked for a
broader one, and a broader one is a strict widening that can be added
later without redesigning this one). Setting `+o` directly (not via
`OPER`) from a non-privileged session is rejected with `481
ERR_NOPRIVILEGES` — the same numeral every other administrator-only
action already uses, since self-granting `+o` this way would be
indistinguishable from self-granting administrator privilege, which is
exactly what FR-034 exists to gate. Setting or clearing `+i`/`-i`, by
contrast, always succeeds instantly for any registered session — `i` is
`clientSettable: true`, so no privilege check applies at all, the same
way an operator may always clear their own `+o` with no check. An
unrecognized flag letter is rejected with `501 ERR_UMODEUNKNOWNFLAG` —
the RFC's own user-mode equivalent of `472 ERR_UNKNOWNMODE`, reused for
the same reason `472` was: an exact-fit existing numeral, not a
project-specific invention. `WHOIS` (FR-037) additionally surfaces
`operator` status via
`313 RPL_WHOISOPERATOR` when the target holds it — visible to any
querying client, not gated by administrator privilege or self-lookup,
since operator status is public information on real IRC networks (unlike
the real-hostname resolution FR-038 defines, which is exactly the kind
of information that IS gated).

**Rationale**: This is the direct implementation of the requirement that
motivated it — "when an administrator is authenticated, `+o` should be
added to user modes" — but it also finally gives FR-044's
long-standing extension-mechanism promise (research.md, above:
"When a user-level mode is introduced... it MUST use the same open,
extension-friendly mechanism FR-043 establishes") its first real
consumer, the same way `voice`/`operator` gave the `MEMBER`-kind
`ChannelMode` shape its first real validation. Deriving `userModes`
from `administratorPrivilege` rather than treating them as two
independently-settable pieces of state avoids inventing a new
source-of-truth question this project doesn't need: `administratorPrivilege`
already exists, is already checked by `EXTENSION`/`WHOHOST`/`REHASH`/
`SAJOIN`/`SAMODE`, and adding a second, separately-toggleable flag that
merely *usually* agrees with it would be a latent inconsistency bug
waiting to happen, not a feature. Allowing self-revocation via `-o`
(and having it actually revoke the privilege, not just hide the flag)
mirrors the self-revocation allowance FR-046 already grants for
channel-operator status (`MODE -o <self>`) and IRC convention generally
— an operator can always step back down from their own privilege without
needing anyone else's permission, symmetric with never needing anyone
else's permission to be granted it via `OPER` either.

**Alternatives considered**:
- *Give `UserMode` the same `kind`/`gates` shape as `ChannelMode`, for
  structural symmetry*: rejected — `gates` exists to decouple "which
  command does this flag restrict" because `ChannelMode` flags restrict
  several different channel-scoped commands (`SEND`, `JOIN`, `DISCOVER`).
  No `UserMode` this release restricts anything except itself (whether
  `+o` may be set) — there is no second command for `operator` to gate.
  Adding the field now, unused, would be exactly the "guessing the right
  shape without a concrete consumer" mistake this project has
  deliberately avoided elsewhere (e.g., `VALUE`/`LIST`-kind `ChannelMode`
  storage, above) — if a future user-mode flag genuinely needs to gate
  something, `gates` can be added to `UserMode` then, informed by what
  that flag actually needs, not guessed now.
- *Track `operator` status only as `userModes` membership, retire
  `ClientSession.administratorPrivilege` as a separate field*: rejected
  — `administratorPrivilege` predates this decision and is already the
  name every other FR (FR-033, FR-057, FR-058) and a substantial part of
  `jircd-server-extensions/admin` is written in terms of; renaming the
  concept purely for this change would be churn across already-settled
  requirements for no behavioral benefit — keeping `administratorPrivilege`
  as the single source of truth and `userModes` as its protocol-visible
  reflection achieves the same "one fact, not two" guarantee without the
  rename.
- *Let an administrator's `MODE <self> +o` re-assertion (already an
  operator) be rejected as redundant, mirroring how an unrecognized flag
  is rejected*: rejected — an unrecognized flag is a genuine error (the
  flag doesn't exist); re-asserting a flag that's already set is a
  harmless idempotent no-op, the standard IRC convention for redundant
  mode changes, and erroring on it would make scripted/automated admin
  tooling need to first query current state before every `+o` just to
  avoid a spurious rejection.
- *Silently ignore a non-privileged client's direct `+o` attempt, no
  error* (mirroring how some real ircds handle it): rejected — this
  project's established posture throughout is "reject explicitly, don't
  silently ignore or no-op" for anything that isn't already a harmless
  idempotent case (FR-049's line-length, FR-054's UTF-8, FR-056's topic
  length all reject rather than silently drop); `481` already exists and
  already means exactly this failure class.
- *Add `invisible` as a second hardcoded special case in prose,
  alongside `operator`'s, rather than a `clientSettable` field*:
  rejected once a second flag with a genuinely different setting rule
  existed — two special cases in handler logic is exactly the pattern
  `ChannelMode.gates` was introduced specifically to avoid for channel
  modes (research.md "Channel/user mode extensibility" — "Validating the
  extensibility promise against a future `JOIN`-gating flag"), and
  letting the identical mistake happen here, one flag later, would
  undermine the same lesson already learned once. A `clientSettable`
  field costs one boolean and means a third future flag needs zero new
  branches in `UserModeCommandHandler`, only a new catalog entry.

## WHO and invisibility (FR-044/FR-061)

**Decision**: `WHO` (RFC 2812 §3.6.1) is implemented with three query
forms, dispatched on its single argument's shape: a channel name (per
`ChannelName`'s grammar, T018) lists that channel's current members; an
argument containing `*`/`?` is treated as a wildcard mask matched
against nicknames (case-insensitively, the same rfc1459 casemapping
`NICK`/`JOIN` already use); anything else is treated as an exact
nickname; no argument at all matches every currently-connected user.
Real RFC 2812 `WHO` masks can also match host/server/real-name fields —
this release's mask matching is deliberately narrower, nickname only
(see Alternatives). Each match produces one `352 RPL_WHOREPLY`,
reusing FR-038's real-vs-presented hostname resolution verbatim (the
same computation `WHOIS`/`WHOHOST` already share) and `ChannelMode`'s
existing `@`/`+` operator/voice prefix convention (FR-045/FR-046) when
the query is channel-scoped, plus a `*` marker if the target holds the
`operator` user mode (mirroring `313 RPL_WHOISOPERATOR`'s visibility —
public information, not gated). No `H`/`G` (here/away) distinction is
sent — this release has no `AWAY` — always `H`. The reply always closes
with `315 RPL_ENDOFWHO`, even for zero matches; unlike `WHOIS`, a `WHO`
with no matches is not an error (RFC convention — `WHO` is a search,
and an empty search result isn't a failure the way a `WHOIS` of a named,
expected-to-exist nickname is).

`invisible` (`i`) gates the exact-nickname and mask/no-argument forms
only: a result is excluded unless the requester shares at least one
`Channel` membership with the target (a set-intersection check against
both sessions' `channelMemberships`, no new field needed — data-model.md
`ClientSession`) or the requester holds `administratorPrivilege`
(FR-032/FR-047's established admin-transparency pattern, reused rather
than inventing a new one). The channel-scoped form is exempt from this
check entirely — it reuses `NAMES`'s exact membership-visibility rules
(FR-041/FR-047's `DISCOVER` gate for private/secret, nothing about
invisibility), so `WHO #chan` and `NAMES #chan` always agree on who's a
member, never silently diverging.

A new `ServerConfiguration.whoMaskEnabled` field (boolean, default
`true`) gates the mask and no-argument forms a second, coarser way —
independent of any individual target's `invisible` state. When `false`,
a non-administrator's mask or no-argument `WHO` returns bare `315
RPL_ENDOFWHO` with no `352` lines at all, indistinguishable from a real
search that matched nobody — the same "don't let the requester
distinguish policy from an empty result" posture `private`/`secret`
channels already use for `DISCOVER` failures (FR-047). An
administrator's mask/no-argument `WHO` is never affected by this
setting, checked before it: `administratorPrivilege` short-circuits the
whole gate, the same precedence order the `invisible` check above
already uses. The channel-name and exact-nickname forms are untouched
by this setting entirely, same as they're untouched by `invisible`.

**Rationale**: The channel-scoped/search-scoped split matters because
they answer genuinely different questions: "who is in this channel" (an
already-public fact to anyone who can see the channel at all — `NAMES`
already answers it with no invisibility filtering, so `WHO` disagreeing
would be a confusing, undocumented inconsistency between two commands
that otherwise look interchangeable) versus "find users matching this
pattern across the whole server" (an enumeration/search capability,
exactly what `invisible` exists to blunt — RFC 2812's own stated purpose
for the flag). Gating the exact-nickname form the same as the mask form,
rather than treating a known nickname as automatically exempt (the way
`WHOIS` is never gated by invisibility at all), matters because
nicknames are frequently guessable or learnable outside any shared
context (a support channel's topic, a pasted log, a previous session) —
if an exact-nickname `WHO` bypassed invisibility, any stranger who
merely learned someone's nickname could route around the flag entirely,
which would make `invisible` far weaker than its RFC-stated intent.
`WHOIS` staying ungated is a deliberate, pre-existing asymmetry this
change does not touch: `WHOIS` already requires knowing the exact
nickname up front (no wildcard/no-argument form exists for it at all),
so it doesn't carry the same enumeration risk `WHO`'s mask and
no-argument forms do.

`whoMaskEnabled` is a separate lever from `invisible` because they solve
different problems for different actors: `invisible` is a per-user,
self-service choice ("don't let strangers find *me* by searching"),
while `whoMaskEnabled` is a deployment-wide, administrator choice ("this
server doesn't offer broad user-search to ordinary clients at all,"
independent of what any individual user has opted into) — real IRC
networks commonly run with exactly this second kind of restriction
(oper-only mask `WHO`) regardless of individual `+i` settings, since
unrestricted enumeration is also a load/abuse concern, not only a
privacy one. Defaulting `whoMaskEnabled` to `true` (available to
everyone) rather than `false` keeps the zero-configuration case
matching ordinary client expectations — most IRC clients assume
wildcard `WHO` works — while giving administrators who want the
stricter, oper-only posture a one-line way to get it; `invisible`
remains the primary, always-on protection for individual users
regardless of which way this setting is configured.

**Alternatives considered**:
- *Full RFC-shape mask matching against nickname, host, server, and real
  name, all at once*: rejected as scope beyond what was asked — this
  release has no server-name-federation concept to match against
  anyway (FR-021), and matching against host/real-name introduces its
  own privacy question (should a real-vs-presented-hostname distinction
  apply to *matching*, not just *display*?) that nothing currently
  requires answering. Nickname-only matching is the minimal shape that
  satisfies "list of users based on mask pattern" and can be widened
  later without redesigning the command.
- *Gate the channel-scoped form by invisibility too, hiding invisible
  members from a non-member's `WHO #channel`*: rejected — this would
  make `WHO #channel` and `NAMES #channel` disagree on membership for
  the exact same channel and requester, an inconsistency with no
  precedent elsewhere in this specification (every other pair of
  commands answering the same underlying question — e.g. `TOPIC`/`NAMES`/
  `LIST`'s shared `DISCOVER` gate — agree by construction, not by
  coincidence).
- *Let an exact-nickname `WHO` bypass invisibility, matching `WHOIS`'s
  ungated behavior*: rejected — see Rationale; this would make
  `invisible` trivially defeatable by anyone who already has the
  target's nickname from any source, undermining the flag's entire
  purpose for the one query form (search/enumeration) it exists to
  blunt.
- *Error (e.g., `401 ERR_NOSUCHNICK`) when a `WHO` mask/nickname matches
  nothing*: rejected — this is RFC 2812's own convention (a search
  yielding zero results isn't a failure) and matches this project's
  existing `LIST`/`NAMES`-on-empty-set precedent (an empty result set is
  never itself an error condition).
- *Fold mask-restriction into `invisible` itself (e.g., "if any user has
  `+i` set, disable mask search server-wide") instead of a separate
  config field*: rejected — this conflates two independent questions
  (an individual's own privacy choice vs. an administrator's
  server-wide search policy) into one signal, and would mean a single
  user setting `+i` silently changes search behavior for every *other*
  user too, a surprising, hard-to-reason-about side effect nothing
  requires.
- *Reject a disabled mask/no-argument `WHO` with a specific error
  instead of a silent empty result*: rejected — this is the same
  "don't let policy be distinguishable from an empty result" reasoning
  `private`/`secret`'s `DISCOVER` gate already established (FR-047); an
  explicit "search disabled" error would tell a prospective abuser
  exactly what's blocking them (useful information to a bad actor,
  worthless to a well-behaved client, which would get the same "no
  matches" experience either way).
- *Default `whoMaskEnabled` to `false` (restricted by default,
  administrators opt in to opening it)*: considered and rejected in
  favor of `true` — see Rationale; this project's zero-configuration
  defaults consistently favor matching ordinary client/deployment
  expectations (e.g., TLS offered but optional, FR-018; a server name
  falls back to the host's own hostname rather than refusing to start,
  FR-050) over defaulting to the more locked-down posture, reserving
  "secure by default" treatment for cases with a clear, specific risk
  this project has already named (credential hashing, FR-034; UTF-8
  validation, FR-054) rather than applying it uniformly to every new
  setting regardless of context.

## Deterministic testing under concurrency

**Decision**: Success criteria expressed as wall-clock budgets (SC-002's 1s
delivery, SC-003's 1,000 connections, SC-006's no-degradation-under-load) are
tested with two tiers: (1) fast, deterministic unit/component tests that
assert *ordering and correctness* (e.g., "message is enqueued to every
member's outbound queue before the send call returns") without any sleep or
wall-clock assertion, and (2) a small number of explicitly-labeled
`@Tag("load")` integration tests, excluded from the default fast test run
and run separately (e.g., a dedicated Gradle task / CI stage), using
generous, documented margins (e.g., asserting p99 < 1s, not a tight bound)
specifically so they tolerate CI environment noise without becoming flaky.

**Rationale**: The constitution's Testing Standards principle is explicit
that flaky tests "MUST be fixed or removed, never silenced by retries or
skips" — so timing-sensitive success criteria cannot be asserted the same
way as functional correctness. Separating "does it behave correctly" from
"does it meet its performance budget" keeps the default (must-pass-every-
run) suite deterministic while still giving SC-002/003/006 real, repeatable
verification.

**Alternatives considered**:
- *Assert wall-clock timing in every test*: simplest to write, but directly
  produces the flaky/non-deterministic tests the constitution forbids,
  especially for SC-003's 1,000-connection scenario on shared CI hardware.
- *Skip performance testing, rely on manual/production observation*: fails
  the constitution's Performance Requirements principle, which requires a
  re-runnable benchmark/load test for performance-sensitive paths.

## Configuration format

**Decision**: YAML via SnakeYAML for the Server Configuration file (FR-012):
extension enable/disable flags, listener ports (plaintext + optional TLS),
rate-limit thresholds.

**Rationale**: Human-edited, hierarchical, comment-friendly — matches an
administrator-facing config file (FR-012 requires clear, specific error
reporting on invalid config, which is easier with YAML's structure than flat
`.properties`). SnakeYAML is a small, mature, dependency-light library with
no transitive framework baggage.

**Alternatives considered**:
- *Java `.properties`*: zero dependencies, but flat key-value structure is
  awkward for per-extension nested settings and doesn't support comments
  well for admin documentation.
- *HOCON (Typesafe Config)*: richer feature set (includes, substitutions),
  but pulls in a heavier library for features this project doesn't need at
  its current scope.

## Configuration reload mechanism

**Decision**: A manually-triggered reload — not automatic file-watching.
A single core reload operation (re-run `ConfigurationLoader`, validate,
reconcile the result against `ExtensionRegistry` — succeed or reject
atomically, same validation path as startup, FR-012) is exposed through
two independent triggers:

1. **`SIGHUP`** — the classic Unix daemon convention (nginx, sshd, most
   IRCds themselves), handled in `jircd-server`'s application entry point.
   Requires only shell/process access — no IRC connection.
2. **`REHASH`** — an in-band, administrator-privileged IRC command (added
   to the Story 6 admin command set, contracts/irc-protocol-commands.md),
   matching the command name and behavior most IRC daemons (ircu,
   InspIRCd, UnrealIRCd, charybdis) use for exactly this.

Both call the same underlying reload operation; neither depends on the
other being available.

**Rationale**: An administrator-driven manual trigger — rather than
detecting file changes automatically — avoids reloading against a file
an editor has only partially written, and matches what IRC operators
already expect from `REHASH`. Exposing it via *two* independent triggers,
rather than picking just one, is what keeps Story 4 and Story 6
independently deliverable: Story 4 is specifically the
configuration-file-only administration path (no IRC access required), so
its reload trigger can't live inside the optional `admin` `ServerExtension`
that Story 6 delivers — `SIGHUP` is handled in the core application
process regardless of which extensions are enabled. Story 6 gets `REHASH`
as the in-band equivalent, consistent with its "no file system access"
premise and with real-world IRC operator convention.

**Alternatives considered**:
- *Automatic file-watching (`java.nio.file.WatchService`)*: the original
  choice, reverted here. It reacts to file-system events, which risks
  triggering mid-write (a half-saved YAML file from certain editors/save
  patterns) and doesn't match how IRC administrators actually expect to
  apply a config change (explicitly, on their own timing) — `REHASH`'s
  request/response also gives immediate confirmation or a specific
  validation error, which a background watcher can't easily surface back
  to the admin's terminal.
- *`SIGHUP` only, no `REHASH` command*: would leave a Story 6 administrator
  needing shell access to apply anything beyond an `EXTENSION` toggle
  (which bypasses the file entirely), undermining Story 6's "no file
  system access" premise for the rest of the config (rate limits,
  listeners, credentials).
- *`REHASH` command only, no `SIGHUP`*: would make Story 4 (file-only
  administration) depend on the optional `admin` extension existing and
  being enabled just to apply its own file edits — contradicts Story 4's
  independence and FR-011's baseline "no restart" guarantee, which holds
  regardless of which extensions are built.

## Logging

**Decision**: SLF4J API + Logback implementation.

**Rationale**: De facto standard Java logging pairing; satisfies FR-019's
requirement for reviewable security-event logs with structured, leveled
output and file/rotation support out of the box.

**Alternatives considered**: `java.util.logging` (built into the JDK, zero
dependencies, but noticeably weaker configuration ergonomics and no
structured-logging story — a worse fit for FR-019's "an administrator can
review" bar).

## Static analysis / code quality tooling

**Decision**: Spotless (formatting/style enforcement) + SpotBugs (bug
patterns) as Gradle-integrated build tasks, both configured to fail the
build on violations.

**Rationale**: Directly implements the constitution's Code Quality
principle ("Static analysis and linting MUST run clean... before a change
is considered mergeable"). Spotless auto-formats and verifies formatting in
one Gradle task (lower friction than Checkstyle's report-only style, since
`spotlessApply` can fix violations directly rather than only flagging
them), while SpotBugs covers the complementary concern of actual defect
patterns.

**Alternatives considered**: Checkstyle (viable, but Spotless's
apply-and-fix workflow was preferred for day-to-day contributor ergonomics
over Checkstyle's report-only model). PMD (overlaps significantly with
SpotBugs; adding both was judged redundant tooling for this project's size
— revisit only if SpotBugs proves insufficient in practice).

## Administrator credential storage (FR-034)

**Decision**: Administrator credentials in the Server Configuration are
stored and verified the same way as FR-024's account credentials: never in
plain text, protected with a salted, computationally-expensive hash (e.g.,
bcrypt/Argon2 via a small, well-reviewed library rather than a hand-rolled
implementation).

**Rationale**: FR-034 explicitly requires this parity with FR-024. Reusing
one hashing approach (rather than inventing a second one specific to admin
credentials) keeps the credential-handling code path singular, which is
easier to review and test — directly serving the constitution's Code
Quality principle (single, clear responsibility).

**Alternatives considered**: Storing an admin password hash with a
weaker/faster algorithm (e.g., plain SHA-256) on the reasoning that it's
"just local config" — rejected because the threat model (config file
disclosure, e.g., via backup leak or misconfigured permissions) is the same
threat FR-024 is already written to defend against; there's no principled
reason to weaken it for this one credential type.

## OPER failed-attempt lockout (FR-034)

**Decision**: Track `OPER` credential failures in a shared, bounded
`OperAttemptTracker` keyed by the accepted socket's normalized source-address
bucket, across reconnects and usernames. IPv4 uses /32; IPv6 uses
`ipv6ConnectionLimitPrefix` (default /64); IPv4-mapped addresses normalize to
IPv4. If the address is unavailable, use a per-connection key rather than
parsing the display hostname. On reaching `ServerConfiguration.operFailureThreshold`
(default `5`, maximum `20`), reply `464 ERR_PASSWDMISMATCH`, log the verified
failure, and disconnect through normal FR-017 cleanup. Subsequent OPER attempts
in the locked bucket skip credential verification, log `OPER source locked out`,
and disconnect, including attempts with correct credentials.

The fixed `operLockoutSeconds` window (default `300`, range `1`–`86400`) starts
when the first failed verification completes. Slow password hashing therefore
cannot consume the new window before its failure is recorded. Subsequent
attempts do not extend the deadline. Success below threshold clears the source
counter, and expiry allows verification again. Entries expire lazily; the map
is capped at 10,000 entries. At capacity, new sources fall back to per-session
failure counting, still verifying passwords and disconnecting at the threshold,
without evicting live source lockouts. A successful OPER resets the fallback
counter. `REHASH`/`SIGHUP` apply threshold changes immediately; window duration
changes apply to new windows only.

**Rationale**: `OPER` is a credential-verification command guarding
server-wide administrative privilege — brute-forcing it is a real threat
model FR-019 already asks the server to log against, and this project's
established pattern for exactly this kind of abuse (FR-016's rate
limiting) is "reasonable, administrator-configurable default," not a
fixed universal threshold or no protection at all. Disconnecting (rather
than merely rate-limiting further attempts) matches how `LivenessMonitor`
and other abuse-adjacent paths already terminate a misbehaving connection
outright rather than degrading it in place. Sharing failure windows across
connections prevents reconnects from resetting the password-guessing budget.

**Alternatives considered**: *Rate-limiting `OPER` attempts via the
existing `rateLimitBucket` (FR-016) instead of a dedicated counter* —
rejected because `rateLimitBucket` governs message/command throughput
generally and slowing `OPER` attempts down doesn't bound how many a
sufficiently patient attacker eventually gets to try; a hard disconnect
after a fixed count is the stronger, simpler guarantee FR-034's own
wording ("disconnect the client when the threshold is reached") calls
for. *No lockout at all, logging only* — rejected as insufficient given
FR-034's explicit MUST for disconnection, not just visibility.

## Administrator channel override: SAJOIN/SAMODE (FR-057/FR-058)

**Decision**: Two administrator-only in-band commands, following the
same "restricted to administrator privilege" pattern as `OPER`/`EXTENSION`/
`WHOHOST`/`REHASH` (contracts/irc-protocol-commands.md "Administration"):

- `SAJOIN <channel>` — the administrator's session joins `channel`
  through the *same* create-or-join path ordinary `JOIN` uses
  (`ChannelRegistry`, FR-003; first-join-gets-operator, FR-013, applies
  identically if the channel doesn't yet exist), except the `JOIN`-gate
  check point (FR-043's `gates` mechanism, data-model.md `Channel`
  validation rules) is skipped entirely for this command. Grammar
  (FR-002/FR-048) and UTF-8 validity (FR-054) checks are NOT skipped — a
  malformed channel name is rejected the same way for `SAJOIN` as for
  `JOIN`.
- `SAMODE <channel> <+o|-o>` — grants or revokes operator status on the
  sender's own membership in `channel`, bypassing FR-046's "sender must
  already be a channel operator" precondition. Requires the sender to
  already be a member (via `JOIN` or `SAJOIN`) — `SAMODE` does not join.
  No target parameter: self only, by design (see Alternatives).

Both are wired into `jircd-server-extensions/admin`, the same module
`OPER`/`EXTENSION`/`WHOHOST`/`REHASH` already live in — this is squarely
"administrator/operational concerns specific to running this service,"
plan.md's own description of that bounded context, not a new one. A
third command in this family, `SABAN` (an administrator-only ban
override), was proposed and then withdrawn — see "`LIST`-kind flags in
practice: ban-mask" below for what replaced it: the actual requirement
turned out not to be administrator-specific at all, so it needed no new
command, just a change to how any operator's existing ban already
matches.

**Rationale**: Real deployed IRC networks distinguish an administrator's
*ordinary* client commands from their *privileged override* commands
precisely so a bypass is a deliberate, auditable act rather than a
silent side effect of routine use — an administrator investigating
something by typing an everyday `JOIN` should not unknowingly end up
bypassing a restriction they didn't intend to bypass. `SAJOIN`/`SAMODE`
are the well-known real-world names for exactly this pattern (this
project already reuses well-known real-world names over inventing new
ones where one exists — the same discipline `417`/`432`/`476`'s numeral
choices already follow). Restricting `SAMODE` to self-targeting only
(no arbitrary-nickname, no arbitrary-modestring form) matches the actual
requirement — "an administrator can op themselves" — without opening a
much larger surface (arbitrary mode overrides against arbitrary targets)
nothing has asked for and this project's own "no speculative generality"
guidance would flag.

**Alternatives considered**:
- *Make administrator bypass silent and automatic on ordinary `JOIN`/
  `MODE` instead of new commands* (mirroring how FR-047's discovery
  bypass on `TOPIC`/`NAMES`/`LIST` already works transparently):
  rejected for this specific pair — discovery (seeing that a channel
  exists) is a passive, low-consequence action where transparent bypass
  is harmless; forcing entry into a gated channel or seizing operator
  status are active, consequential actions where an explicit, distinct
  command is the safer and more auditable design, and matches the
  established real-world convention for exactly this case.
- *A general `SAMODE <channel> <modestring> [<target>]` accepting any
  mode change against any target, admin-privilege-gated* (the fuller
  real-world `SAMODE` shape on some networks): rejected as scope beyond
  what was asked — this release has no request for administrators to
  revoke *other* members' operator status or set arbitrary channel
  modes on someone else's behalf; a self-op-only command is the minimal
  shape that satisfies the actual requirement, and a fuller `SAMODE`
  can be added later without redesigning this one (it would be a
  strict widening, not a breaking change).
- *A dedicated `SABAN` command, administrator-only, for adding/removing
  bans on channels the administrator doesn't operate*: proposed, built,
  and then withdrawn on reconsideration. `SABAN` was never a real,
  established IRCd convention the way `SAJOIN`/`SAMODE` are — unlike
  those two, there was no existing real-world name being reused, only
  one invented by extending a naming pattern, which is exactly the kind
  of "invented because it looked consistent" reasoning this project's
  numeral/command-naming discipline exists to avoid (research.md "Wire-
  protocol command & numeric completeness" — reuse an existing
  convention or don't invent one at all). More importantly, framing this
  as an *administrator* capability was itself a category error: the
  actual need was for ban masks to reliably match a client's real
  network identity, not administrator-only *access* to the ban list —
  any channel operator already has full ban-list access (FR-062), and
  the operator adding a host/IP-shaped mask doesn't need to see the
  real value to specify a pattern that happens to match it. Once the
  fix was correctly identified as a matching-logic change rather than a
  privilege-gating one, a separate admin-only command became not just
  unnecessary but actively wrong — it would have kept the real
  capability (dual-matching bans) hidden behind a privilege level it
  never needed to depend on.
- *Have `SAJOIN` also auto-grant operator status*, folding FR-057 and
  FR-058 into one command: rejected — the two are independently useful
  (an administrator investigating a channel via `SAJOIN` may deliberately
  want to observe without becoming an operator and changing the
  channel's visible member list semantics), and keeping them separate
  mirrors how real networks keep force-join and force-op as distinct
  commands, not a combined one.

## Cloak extension boundary (FR-031)

**Decision**: `jircd-server-extensions/cloak` is a `ServerExtension` (see
"Extension system" — it has no negotiable `Capability`, so it's a
`ServerExtension`, not a `CapabilityExtension`), but it does not itself own
a client's real hostname — `jircd-core` always records the real value on
the `ClientSession` (data-model.md) and asks the currently-enabled cloak
extension, if any, for the *display* value used when presenting identity
to other clients (FR-030). It does this by claiming the `hostname-display`
extension point (data-model.md's `Extension.extensionPoint`), so the
"Extension system" decision's ownership rule — at most one enabled
extension per extension point — already prevents a second, competing
cloaking extension from being enabled at the same time without inventing a
cloak-specific conflict rule. Administrative hostname lookups (FR-032,
via `WHOHOST`) and `WHOIS`'s self-lookup/administrator cases (FR-037/
FR-038) always read `jircd-core`'s stored real value directly, bypassing
the cloak extension entirely; `WHOIS`'s non-administrator-looking-up-
someone-else case instead calls the exact same display-value lookup
message hostmasks use (`UserIdentity.presentedForm`, data-model.md) —
one resolution function, three call sites (`presentedForm`, `WHOHOST`,
`WHOIS`), not three independent implementations that could drift apart.

**Rationale**: This directly satisfies the edge case of "what happens when
cloaking is disabled while clients are connected" (spec.md Edge Cases): if
the real value only ever lived in `jircd-core`, disabling `cloak` at
runtime (FR-011) simply means the display-value lookup returns the real
value again immediately — no migration, no per-session state to fix up,
and no way for a cloak-extension bug to lose or corrupt the real value the
rest of the system depends on.

**Alternatives considered**: Having the cloak extension own/replace the
stored hostname (with the real value kept in a side table only the cloak
extension manages) — rejected because it makes administrator lookups
(FR-032) and disabling-while-connected behavior depend on the cloak
extension's own bookkeeping being correct, which is a larger trust surface
for what should be a purely presentational concern.

## Connection-admission extension point (FR-066)

**Decision**: Reuse the existing `extensionPoint` single-owner-claim
mechanism (data-model.md `Extension`, this document's "Extension system")
rather than inventing a new one. `jircd-core`'s connection-acceptance path
consults a new named point, `connection-admission`, once per accepted
connection, before that session may proceed toward registration (FR-001)
— structurally the same call-out shape `hostname-display` already uses
for `cloak` (see "Cloak extension boundary" above), just invoked earlier
in a connection's life and answering "may this proceed?" instead of
"what value should this show?" With no extension claiming
`connection-admission` in this release (none exists yet — FR-066 defers
the actual G-line capability), the call-out always permits the
connection, the same default-permissive behavior `hostname-display`
falls back to when `cloak` is disabled. No new `Extension` role, no new
`ExtensionRegistry` mechanism, and no new Gradle subproject are needed to
satisfy FR-066 this release — only the call-out site itself, inside
`jircd-core/session`'s `PlaintextListener`/`TlsListener`/
`ConnectionHandler` path (plan.md "Project Structure").

**Rationale**: FR-066 only requires that adding a future network-mask
connection block not force a redesign of core connection-handling logic.
Since `ExtensionRegistry`'s ownership-claim mechanism is already generic
over "what value or decision does the claiming extension supply," a
connection-admission predicate (host/IP → allow/deny) is not a new kind
of extension point conceptually, just a new point at a new call site —
the same reasoning that let `admin` (FR-032) and `cloak` (FR-031) share
one mechanism despite doing unrelated things. Building a second,
G-line-specific extensibility mechanism now, for a capability that isn't
being implemented this release, would be exactly the kind of speculative
generality the constitution's Development Workflow section warns
against; reusing the existing mechanism costs only the one call-out site.

**Alternatives considered**: *A dedicated `ConnectionGate`/
`ConnectionExtension` interface, separate from `Extension`* — rejected
for the same reason a second `LIST`-kind storage mechanism was rejected
for `bans` (FR-062, "Channel/user mode extensibility"): no second
concrete consumer exists yet to justify a parallel mechanism, and
`extensionPoint` already fits without modification. *Wiring the call-out
only into `ConnectionHandler` (post-accept, pre-dispatch) rather than the
raw listener accept path* — deferred as a planning-phase decision for
when the `gline` extension is actually built (see spec.md Edge Cases:
whether a match is judged before or after TLS negotiation, and against
real vs. presented identity, are open questions this release doesn't
need to answer, since nothing calls the extension point with real
data yet).

## TLS approach

**Decision** *(revised)*: `SSLServerSocket`/`SSLSocket` — the blocking TLS
API — for the optional TLS listener (FR-018), not `SSLEngine`.

**Rationale**: This decision was originally made to match a non-blocking,
channel-based I/O model, but "Networking model" above actually chose
*blocking* I/O per connection on virtual threads, not a non-blocking
reactor. `SSLEngine` is designed for exactly the non-blocking case — you
drive its wrap/unwrap/handshake state machine by hand — and using it in a
blocking-per-thread design means hand-rolling that state machine for no
benefit, since nothing else in the connection-handling code is
non-blocking. `SSLSocket` wraps a blocking socket and, like the rest of
the design, reads/writes as ordinary blocking calls on a virtual thread —
it is the pairing that actually matches "Networking model," not the
mismatch the original version of this decision introduced. Both the
plaintext (`ServerSocketChannel`/`SocketChannel`) and TLS
(`SSLServerSocket`/`SSLSocket`) listeners end up structurally similar:
accept a connection, hand it to a virtual thread, read/write blocking
calls from there — TLS or not is an implementation detail of that one
connection's I/O calls, not a fork in the overall concurrency model.

**Alternatives considered**: `SSLEngine` (the original choice, reverted —
see Rationale: right API for a non-blocking reactor, wrong one for this
project's actual blocking/virtual-thread model). Wrapping
`SocketChannel` in a hand-written blocking adapter just to keep using
`SSLEngine`: technically possible but reinvents what `SSLSocket` already
provides, for no gain.

## Voluntary disconnect and quit reasons (FR-017/FR-060)

**Decision**: `QuitCommandHandler` is not "the `QUIT` command's handler"
in the narrow sense — it's the one shared disconnect-cleanup entry
point (channel membership removal, `QUIT` notification to every
affected channel, FR-017) that every disconnect path funnels through,
whichever triggered it: a client-sent `QUIT` (FR-060, this decision), a
keep-alive timeout (FR-039, "Connection keep-alive" below), or an
abrupt TCP-level connection loss. What differs between these paths is
only the *reason* text carried on the notification:

- **Client-sent `QUIT :<reason>`**: the supplied reason, verbatim
  (subject to FR-054's UTF-8 validity requirement, same as any other
  human-readable field).
- **Client-sent `QUIT` with no reason**: a short, fixed server default
  (e.g., `"Client Quit"`, the conventional real-IRC wording) — never a
  blank/empty reason, which would read as a formatting bug rather than
  a deliberate "no reason given."
- **Keep-alive timeout**: a server-generated reason describing why
  (e.g., `"Ping timeout"`), distinct from the client-quit default so a
  channel's remaining members can tell the two apart in their own
  client's disconnect log, even though both reach the identical
  cleanup path.
- **Abrupt TCP-level loss** (a read returning EOF/error, no `ERROR` or
  `QUIT` involved): a server-generated reason describing the connection
  having simply dropped (e.g., `"Connection reset by peer"` or
  similarly worded, mapped from the underlying I/O exception where
  reasonable).

A session that has not yet completed registration MAY still send
`QUIT` (contracts/irc-protocol-commands.md "Channel Operations" —
`QUIT`'s "any time" precondition, unlike every other command in that
table) — `QuitCommandHandler` runs the same cleanup path, which is
simply a no-op for channel memberships since a not-yet-registered
session has none.

**Rationale**: FR-017 already required disconnect cleanup "whether
gracefully or unexpectedly," and the "Connection keep-alive" decision
below already established that a keep-alive timeout reuses
`QuitCommandHandler`'s path rather than its own separate one — this
decision is what makes that reuse concrete by defining what varies (the
reason) versus what's identical (the cleanup) across every trigger.
Never allowing a blank reason matters for the same reason FR-051's
burst-completion signal matters: a client parsing "no notification body
at all" cannot distinguish "nothing to report" from "a bug that dropped
the content," so the server MUST always supply *something*, even when
the client itself supplied nothing.

**Alternatives considered**:
- *A separate handler/code path for keep-alive-triggered and
  TCP-loss-triggered disconnects, distinct from `QuitCommandHandler`*:
  rejected — this is exactly the duplication "Connection keep-alive"
  below already rejected once (the "no keep-alive at all" alternative's
  sibling concern): FR-017's cleanup logic must not exist twice,
  redundantly, in different states of correctness.
- *Show no reason at all for server-generated disconnects (timeout,
  TCP loss), only for client-supplied `QUIT` text*: rejected — an empty
  reason on some disconnects and populated text on others is an
  inconsistent notification shape a client's UI would have to special-case
  for no benefit; a short, fixed default costs nothing and keeps every
  `QUIT` notification uniformly shaped.

## Connection keep-alive (FR-039)

**Decision**: A per-connection `LivenessMonitor`, driven by an injectable
clock/scheduler rather than real time: when a connection has been idle
past a configured probe interval, it sends a server-initiated `PING`; if
no `PONG` arrives within a configured timeout, it sends `ERROR` and closes
the connection through the exact same disconnect path `QuitCommandHandler`
uses (FR-017's cleanup/notification), not a separate one. A
client-initiated `PING` is answered with an immediate `PONG` on any
connection, independently of the monitor's own probing (contracts/
irc-protocol-commands.md "Connection Keep-Alive").

**Rationale**: `jircd-protocol`'s Full Command Catalog already listed
`PING`/`PONG`/`ERROR` as "Implemented" (a completeness commitment this
project makes for every command it claims — see "Wire-protocol command &
numeric completeness" above), but nothing backed that claim: no FR, no
task, no code. That gap matters beyond documentation accuracy — FR-017
already commits to cleaning up a client's channel memberships "whether
gracefully or unexpectedly," but the *unexpected* case as originally
scoped only covers a TCP-level signal (a read returning EOF or an error).
A connection whose network path has gone silent without the OS ever
noticing (a common real-world case: a dead client behind a NAT/firewall
that silently drops idle mappings) produces neither a `QUIT` nor a
TCP-level error — without a keep-alive probe, that session would sit in
`REGISTERED` forever, still counted as a channel member, still a
candidate recipient in fan-out, indefinitely. The injectable-clock
requirement follows "Deterministic testing under concurrency" below: a
timeout mechanism tested with real `Thread.sleep` calls is exactly the
kind of flaky, slow test that principle rules out.

**Alternatives considered**:
- *Rely on TCP keep-alive (`SO_KEEPALIVE`) instead of an application-level
  `PING`/`PONG`*: OS-level keep-alive intervals are typically hours by
  default and not portably tunable to IRC-appropriate timescales (minutes)
  from Java without platform-specific code — and it wouldn't satisfy the
  Full Command Catalog's claim that `PING`/`PONG` are implemented,
  wire-visible commands.
- *No keep-alive at all, treat it as future scope*: rejected because it
  leaves the "Implemented" claim false and FR-017's "unexpectedly"
  disconnect case incomplete for a failure mode (silently dead
  connections) that is common enough in real deployments to matter for
  SC-003's 1,000-connection sustained-operation target — a server slowly
  accumulating ghost sessions would eventually violate it.

## Rate limiting (FR-016)

**Decision**: Per-connection token bucket, refilled at a fixed rate,
configurable via the Server Configuration (bucket size and refill rate).

**Rationale**: Token bucket is the standard, well-understood approach for
this exact case (bursty-but-bounded traffic from a single source); it is
simple to reason about, cheap per-connection, and its two parameters map
directly to an administrator-tunable config (matching the spec's Assumptions
section: "reasonable industry-standard defaults, configurable by the
administrator").

**Alternatives considered**: Fixed window counter (simpler but allows burst
doubling at window boundaries); sliding-window log (more precise but
higher per-connection memory cost for a benefit this project doesn't need).
