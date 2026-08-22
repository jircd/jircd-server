# Research: Away-Notify and Batch Capabilities

## Decision 1: `away-notify` fan-out lives directly in `AwayCommandHandler`, not as a
`CapabilityExtension` hook

**Decision**: Extend `AwayCommandHandler` itself to, after a successful set/change/clear,
collect the sender's channel-mates into a `Set<ClientSession>` (deduplicated across every
channel the sender is a member of), filter to sessions whose `negotiatedCapabilities()`
contains the string `"away-notify"`, and send each a `Command.AWAY` raw `Message` via
`writer().enqueueRaw(...)` — the exact same primitive `NickCommandHandler`'s existing
`broadcastNickChange` already uses for its own channel-mate fan-out.

**Rationale**: The two existing `CapabilityExtension` hooks (`contributeTags`,
`includeSenderInFanOut`) both operate on a message *already being sent* by some other command
(`PRIVMSG`/`NOTICE`) — they decorate or extend an existing fan-out, they don't originate a new
one. `away-notify`'s notification isn't a decoration of an existing send; it's a wholly new
broadcast triggered by `AWAY`, the same shape `NICK`'s channel-mate broadcast already is (which
also has no `CapabilityExtension` of its own — it's just unconditional there, where
`away-notify` adds a per-recipient capability check). Keeping the fan-out in the command
handler that owns the triggering state change avoids inventing a third hook used by exactly one
capability.

**Deduplication note**: `NickCommandHandler.broadcastNickChange` iterates every channel and
every member without deduplication — a recipient sharing two channels with the sender receives
the `NICK` line twice. That was never required to dedupe by its own spec. `away-notify`'s
FR-004 explicitly requires at most one notice per recipient regardless of shared-channel count,
so this feature's fan-out builds a `Set<ClientSession>` first (natural dedup by object identity)
before sending, rather than reusing `NICK`'s simpler double-loop as-is.

**Alternatives considered**:
- *A generic "presence-change" `CapabilityExtension` hook*, letting a capability module itself
  decide whether to broadcast: rejected as premature generality — no second consumer of such a
  hook exists yet, and the constitution's "no speculative generality" guidance applies directly.
- *Reusing `NICK`'s undeduplicated double-loop as-is*: rejected because it would violate FR-004
  outright for any sender in more than one shared channel with a given recipient.

## Decision 2: `batch=<ref>` tag emission requires the recipient to have negotiated BOTH
`batch` and `message-tags`, checked inside `SessionWriter.enqueueBatch` itself

**Decision**: `SessionWriter.enqueueBatch(type, typeParams, members)` checks
`session.negotiatedCapabilities().contains("batch") && session.negotiatedCapabilities().contains("message-tags")`
before doing anything else. If both are present, it sends `BATCH +<ref> <type> [typeParams...]`,
then each member message with a `batch=<ref>` tag merged in, then `BATCH -<ref>` — all via the
existing `enqueueRaw`. If either is absent, it sends every member message via `enqueueRaw`
completely unmodified — no wrapper, no tag, exactly the messages the caller passed in.

**Rationale**: `SessionWriter.enqueueRaw`'s own documentation is explicit: "no tag decoration
applied" — whatever tags a `Message` already carries when handed to `enqueueRaw` are serialized
verbatim, with zero capability gating (unlike the `OutboundMessage`/`CapabilityTagRenderer` fan-
out path, which live-checks `message-tags` before forwarding any tag at all —
`CapabilityTagRenderer`'s own comment: "a recipient with nothing negotiated has no way to parse
a tag section at all, so it must never appear on the wire for them", citing the
005-fix-batch-conformance FR-010 regression this project already fixed once for a different tag
source). A caller of `enqueueBatch` who forgot to check `message-tags` first would silently
reproduce that exact class of bug for a client that negotiated `batch` alone — checking both
capabilities inside `enqueueBatch` itself makes that mistake structurally impossible for every
future caller, not just the first one.

**Alternatives considered**:
- *Trust the caller to check `message-tags` before calling `enqueueBatch`*: rejected — this is
  exactly the "caller must remember" pattern that let the FR-010 regression happen once already
  in this codebase; centralizing the check in `enqueueBatch` removes the failure mode entirely.
- *Require only `batch`, not `message-tags`, reasoning that `batch` implies it*: rejected as an
  unenforced assumption about client behavior; nothing prevents a client from requesting `batch`
  without `message-tags`, and this project already treats "recipient can't parse a tag section
  without message-tags" as an invariant, not a convention any specific client is expected to
  follow.

## Decision 3: Batch reference tags are `UUID.randomUUID().toString()`, generated fresh per call

**Decision**: Each `enqueueBatch` invocation generates its own reference tag via
`UUID.randomUUID().toString()` — no shared counter, no per-connection sequence state.

**Rationale**: Matches the existing convention `message-tags`' own `msgid` tag already uses
(`MessageTagsExtension.contributeTags` → `message.messageId().toString()`, itself a
`UUID.randomUUID()` generated once per `OutboundMessage`). A random UUID per call is
collision-safe without any synchronization, satisfying FR-009's "no ambiguity between
concurrent groups" requirement for free — there is no shared mutable state between two
concurrent `enqueueBatch` calls to begin with.

**Alternatives considered**:
- *An incrementing per-connection counter*: rejected — would need to live somewhere (likely on
  `ClientSession` or `SessionWriter`) and be synchronized against concurrent callers, solving a
  problem the stateless UUID approach doesn't have in the first place.

## Decision 4: `batch`'s correctness is verified by a `jircd-core` unit test constructing
`SessionWriter` directly, not by an end-to-end `jircd-integration-tests` scenario

**Decision**: `SessionWriter.enqueueBatch` gets a unit test in `jircd-core`'s own test source
set, constructing `SessionWriter` directly against a `ByteArrayOutputStream` — the exact pattern
`LivenessMonitorTest.java` already establishes for testing `SessionWriter` mechanics
(`new SessionWriter(session, outputStream, TagRenderer.NONE)`) — asserting on the raw bytes
written for two concurrent `enqueueBatch` calls to the same session, and for a
capability-declined session receiving unwrapped output instead.

**Rationale**: `jircd-integration-tests` is deliberately black-box — `TestServer.start()` plus a
real `RawIrcClient` socket connection, with no in-process hook to invoke an arbitrary internal
method. That fits every other capability in this project because each one has a real client-
triggerable command that exercises it end-to-end (`PRIVMSG` for `echo-message`/`server-time`,
now `AWAY` for `away-notify`). `batch` has no such trigger in this release — spec.md's
Assumptions section is explicit that no currently-shipped behavior is changed to route through
it, since a real consumer (e.g. a future `chathistory` capability, tracked separately) doesn't
exist yet. Given that, a black-box integration test would need either (a) a production consumer
this feature doesn't otherwise need, or (b) a synthetic command invented purely to exercise test
infrastructure — both rejected below. Testing the mechanism directly, the same altitude
`LivenessMonitorTest` already tests `SessionWriter`'s other internal mechanics at, keeps FR-011
("not hardcoded to any single specific use") true in the test suite as well as the production
code, and satisfies the constitution's Testing Standards (deterministic, covers primary behavior
and documented edge cases) without inventing scope this feature doesn't otherwise need.

**Alternatives considered**:
- *Wire `batch` into an existing multi-line reply (e.g. `NAMES`, `WHOIS`) as this feature's own
  first consumer*: rejected — this would mean this feature, framed by the spec as "add the
  mechanism," also silently takes on "and change `NAMES`/`WHOIS` to use it," a scope expansion
  the spec's own Assumptions section explicitly disclaims, and would require re-validating those
  commands' entire existing test coverage for a change unrelated to their own behavior.
- *Add a minimal test-only/debug command whose sole purpose is triggering `enqueueBatch`*:
  rejected — the constitution's Code Quality principle requires every module to have "a single,
  clear responsibility"; a command that exists only to make a test possible is exactly the
  test-induced production surface that principle rules out, and `jircd-integration-tests` has no
  precedent for a test-only production command anywhere else in this codebase.
- *Skip automated coverage for `batch` and rely on manual verification*: rejected outright — the
  constitution's Testing Standards principle is unconditional ("every new feature MUST ship with
  automated tests covering its primary behavior").
