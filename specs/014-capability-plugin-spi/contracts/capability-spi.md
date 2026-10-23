# Contract: Capability Plugin SPI

Implements FR-001–004, FR-010–016. Complements [runtime lifecycle](runtime-lifecycle.md) and [reply budgets](reply-budgets.md). Names below are proposed public types/methods, not implementation code. They belong under `net.jircd.core.extension.capability` unless an existing type is named.

## Ownership and registration

`CapabilityExtension` remains an `Extension` discovered by ServiceLoader and provides exactly one `Capability`. Add a registration entry point `register(CapabilityRegistrar)`; `AbstractCapabilityExtension` retains id/capability boilerplate, with documented no-op lifecycle defaults. `start(ServerContext)` prepares resources; registration is staged, never visible until successful publication. `stop()` runs after withdrawal, outside core locks; it may overlap callbacks using an older captured snapshot.

`CapabilityDescriptor` declares provider id, capability name, hard provider dependencies, and deterministic hook registrations. Names/ids are nonempty protocol-safe tokens; capability name is unique across discovery and may not claim core `cap-notify`. Duplicate ids/offers cannot silently replace another provider. A provider must not mutate its descriptor or registrations after staging. Registration handles belong to their provider and close idempotently. Captured immutable hook definitions remain usable after removal from the current table; withdrawal affects new captures.

Extend `ServerContext` with a capability-services handle: events, structured replies, authorized delivery, scoped command registration and runtime snapshots. Keep existing operational services for ServerExtension. A capability receives a provider-owned facade; it cannot negotiate on another session's behalf. Its captured inputs and returned immutable plans may outlive withdrawal. No core-to-capability-module dependency.

The registrar supports these typed registrations. Each records an owner, stage/slot, negotiation prerequisites, bounds and fallback. Dispatch applies owner state and declared negotiation gates centrally; providers do not query mutable session capability sets themselves.

| Registration | Inputs | Result | Gate/default |
| --- | --- | --- | --- |
| `onEvent(kind, hook)` | CommittedEvent with immutable subject, pre/post values and an audience handle | Bounded delivery intents to permitted interested peers | Enabled provider; recipient negotiation gates resulting intents. Subject need not negotiate; no listener means no audience traversal |
| `onInboundTags(hook)` | Parsed bounded tags, command facts, sender snapshot, RequestContext | Sanitized client-tag selection and/or provider-scoped context | Enabled provider; optional sender prerequisites declared per registration; default ignores semantic tags |
| `onRecipients(hook)` | Authorized DeliveryIntent, sender snapshot, immutable base audience, RequestContext | Additional sender-copy decision/roles only within permitted scope | Owner negotiated by sender; default ordinary core recipient rules |
| `onOutboundTags(hook)` | Immutable OutboundMessage + role/request metadata when declared recipient-dependent | Owned validated tag contribution with origin metadata | Owner negotiated by recipient; default empty |
| `onMemberStatus(hook)` | MemberSnapshot ordered held statuses, reply kind and requester snapshot | Replacement status string in allowed PREFIX order | Owner negotiated by requester; default highest status |
| `onNamesIdentity(hook)` | MemberSnapshot's already-permitted nick/ident/display-host fields | Replacement NAMES identity token | Owner negotiated by requester; default nickname |
| `onReplyField(slot, hook)` | A schema-declared extension slot and immutable reply facts | Bounded field/text contribution allowed by that schema | Owner negotiated by requester; default schema value/omission |
| `onOutputGroup(hook)` | Finite logical OutputGroup, recipient snapshot and RequestContext | FramingPlan: complete ordered messages, including any open/close | Owner plus declared session prerequisites; default plain members |
| `command(command, handler)` | Core-parsed command, sender snapshot and scoped services | Command's logical replies/delivery intents | Enabled provider unless explicit sender gate; default generic unknown-command handling when no provider owns it |

Core MessageParser/serializer continue to recognize tag syntax and wire tokens even with no provider. Semantic tag forwarding, msgid selection, TAGMSG behavior, batching syntax and full status selection live in modules. Ordinary IRC parsing, authorization, visibility and highest-status formatting remain core behavior.

## Event contract

Event kinds implemented now: `AWAY_CHANGED`, `JOINED`, `PARTED`, `QUIT`, `NICK_CHANGED`. A future account implementation adds `ACCOUNT_CHANGED` at the actual account-state commit boundary; do not emit fake account events or build an account subsystem here.

- Emit after a successful state transition, once; no event for rejected/no-op operations. Existing AWAY semantics treat every accepted set/change/clear as the trigger, even if the supplied reason is repeated; preserve current behavior.
- AWAY includes old/new reason and presented subject identity; JOIN includes committed join/member facts; PART/QUIT include pre-removal audience/identity; NICK includes old presented identity and new nickname. QUIT cleanup is idempotent.
- Capture state/audience facts under existing state locks as needed, then release all registry/channel locks before extension evaluation or delivery. Preserve current weak membership-snapshot semantics; do not promise an atomic snapshot of all channels. A member's privileges are internally consistent.
- `Audience` is a core-owned lazy, deduplicated peer view, not a mutable global registry. Shared-channel neighbors are collected at most once per event. Subject inclusion is explicit; away-notify excludes self. A departing user's peers are captured before memberships disappear. Disconnected recipients are skipped.
- Plugin hooks return intents. Core validates the requested audience/identity and applies recipient negotiation at final admission. Plugins cannot widen private/secret visibility, see unauthorized real hostnames, undo core state or send while holding a domain lock.
- Event hooks are synchronous, cooperative and nonblocking. They must not perform network/file I/O or recursively mutate lifecycle. No general executor/event bus is introduced.

## Message delivery and tags

Extract the reusable portion of MessageCommandHandler target resolution/send gates into `MessageDelivery` (same core session package). It returns an authorized intent or the existing fitted error. PRIVMSG/NOTICE and module-owned TAGMSG share it; NOTICE silence and all ban/mode gates remain core policy. A command extension cannot bypass gates by supplying an arbitrary peer set.

Delivery roles include ordinary target, sender copy and correlated request reply. Core's default channel delivery omits sender; direct self-target delivers once. echo-message adds a sender-copy role for permitted PRIVMSG/NOTICE/TAGMSG. Deduplicate by recipient + logical envelope id and merge roles; no second self-delivery, no bypass of TAGMSG recipient gates. Preserve original ordering/error behavior and one msgid per envelope.

Inbound tag syntax/size validation is always on in protocol/core. message-tags owns the existing client-tag selection (client-only `+` tags), strips client attempts to supply server-owned tags, creates msgid from the immutable envelope id and requires its recipient capability for tag-only delivery. Client-only tags continue to be accepted from a sender without message-tags negotiated where current wire behavior permits; do not add a new sender gate. Other capabilities can claim inbound semantic keys such as a future label through their own sender gate/context namespace.

Tag phase order: inbound normalization → immutable envelope metadata → recipient policy → server/client contribution composition → field/text fitting → group framing → final validation/admission. server-time uses envelope sentAt and remains independent of message-tags. Server tags do not need a negotiated message-tags capability merely because they use tag framing (preserves server-time semantics).

Outbound pure hooks must return the same contribution for the same immutable input; msgid/time use envelope metadata. Cache validated immutable results and exact escaped byte sizes per envelope and registered hook identity. This identity is only a memoization key, never a lifecycle version or admission token. Hooks marked recipient-dependent use a bounded per-preparation result, not a global recipient cache. No provider callback or mutable map survives into the writer queue. A captured delivery keeps its original logical message id/time and is not rechecked or retried on a lifecycle change.

Each server tag key/namespace has one owner per active composition. Client-only `+` namespace belongs to forwarding policy; server keys cannot be supplied there. `batch` tag is owned by the batch shaper, not ordinary tag contributors. Reject conflicting registration, invalid keys/values and conflicting output rather than relying on iteration order. Empty valid contribution is distinct from null. Validate aggregate per-origin and total limits; see budgets contract.

## Structured reply composition

`MemberSnapshot` holds member identity permitted for the requester and a consistent ordered list of all held status symbols, derived from generic channel privileges/PREFIX catalog. Core's normal field is the highest status. No `multiPrefix` boolean, CapabilityName check or capability-specific helper remains in Channel/ChannelRegistry.

- multi-prefix replaces the status slot with all held symbols in PREFIX order for 353 and channel 352.
- NAMES identity is a separate slot so a later userhost-in-names provider can produce `nick!ident@displayHost` independently of multi-prefix. Display-host inputs follow the normal cloak/privacy boundary.
- WHO core owns H/G and IRC-oper `*` flags. The status slot only appends channel statuses; exact-nick/mask WHO keep their current behavior.
- At most one enabled provider replaces a given slot; multiple different slots compose in schema order. A declared additive slot can have several owners only with deterministic provider-id order and summed bounds. Dependencies are not an implicit override mechanism.
- `ReplyLayout` defines slots allowed for that numeric/command, fixed fields, list/text positions and fit policy. A provider cannot append arbitrary WHO parameters or alter numeric arity; a future protocol extension needing new fields must define an appropriate schema first. This is how “extra fields where the protocol permits them” is bounded.
- Capture unformatted member facts once for JOIN results, not capability-shaped strings. JOIN, targeted/bare NAMES, SAJOIN and WHO use the same structured service. Plugin failure after JOIN never loses the successful JOIN or leaves an empty channel.

## Output groups and future request context

`OutputGroup` contains immutable logical members, type/type parameters, group id, optional parent group, RequestContext and logical byte upper bound. Input is finite and must fit the recipient's SendQ envelope before a full transformed copy is allocated. Group nesting is validated acyclic and bounded by admitted bytes; no open-ended streaming API is supplied.

Batch registers a group shaper with owner=batch and session prerequisite message-tags. It generates a fresh valid opaque reference per recipient/group, emits the existing prefixless `BATCH +ref type params`, tags members, and emits `BATCH -ref`. Missing owner/prerequisite produces exactly plain members. Existing raw trusted member tags are preserved and independently validated; no hidden global stripping rule is introduced.

All frames, including close and nested framing, are validated/reserved and queued in a single generic admission. Either all are admitted or none. No shaper callback runs during drain. An already-emitted start therefore always has a queued close unless the connection terminates; no ordering of that close relative to DEL is required. Prepare validated framed/plain alternatives outside locks. At final group admission, use the current owner/session prerequisite set to select an alternative atomically with appending it; if prerequisites became ineffective, send plain members. Use the existing administrative mutex for that short group-only selection/append, followed by the writer enqueue lock; ordinary output has no lifecycle admission lock or revalidation. Future chathistory pages must be finite groups, with paging/storage policy in that feature.

`RequestContext` is immutable, scoped to one inbound request and propagated to its synchronous logical replies and explicit completion boundary. It has a core request id plus provider-owned bounded attributes; core does not interpret `label`. Unsolicited peer broadcasts do not inherit a request label. Future labeled-response uses inbound tag, response tag and grouping hooks to label one reply, wrap many replies or acknowledge none; it can distinguish sender-copy from ordinary delivery and avoid a duplicate acknowledgement when an echo is the response. Its wire semantics remain future work. Pipeline stages/roles resolve composition without optional plugins calling each other by name.

## Dependencies

- `requiresProviders`: hard availability dependency ids in an acyclic graph. Cannot enable without all required providers; disable/failure withdraws transitive dependents atomically. Duplicate/self/unknown/cyclic declarations are invalid. Explicit re-enable is required for withdrawn dependents.
- `requiresNegotiated`: registration-level session prerequisites evaluated with owner negotiation in one immutable snapshot. Unmet gate uses the declared default and does not withdraw the owner's offer or negotiated name.
- An optional interaction uses typed context/stages, not a hard dependency. No undeclared global registry lookup inside hooks.
- Batch uses only a session-level message-tags prerequisite; batch-alone requests still ACK, and disabling message-tags leaves batch offered/negotiated but inert for framing. A new later message-tags request may reactivate that framing without re-requesting the still-held batch name.

## Failure policy

| Boundary | Invalid/throwing behavior | Required action |
| --- | --- | --- |
| Descriptor/registration | Null data, duplicates, impossible bounds, ownership conflict/cycle | Reject staging; no live tables changed. Invalid provider is reported FAILED; unrelated valid providers remain usable. Operator configuration validation errors leave the prior configuration untouched |
| Start | RuntimeException/LinkageError or partial resource acquisition | Close staging handles/resources, FAILED, no offer/NEW; hard dependents fail, unrelated providers continue |
| Event/recipient/tag/field/group/command hook | Exception, null, malformed, unauthorized audience/identity, exceeded bound, illegal framing | Drop that provider's entire invalid result, withdraw it as FAILED, use the documented default for that failed contribution; no lifecycle retry of valid captured work and no rollback of committed core state |
| Runtime composition | Combined valid results exceed a previously proven bound or conflict | Fault offending owner if attributable; reject unadmitted plan and produce one bounded actionable diagnostic. Never emit partial group or silently let writer drop a member |
| Stop/registration close | Exception after withdrawal | Continue other cleanup, keep withdrawn, record FAILED through the same lifecycle; never restore negotiations |
| Recipient overflow/disconnect | Queue cannot accept full plan/notification or socket fails | Existing overflow/disconnect cleanup for that recipient; do not mark plugin FAILED or roll back server transition |

Catch ordinary runtime/linkage faults, not VirtualMachineError/ThreadDeath. Do not catch validation responses such as ordinary command rejection as plugin faults. Core fitting an allowed variable text/list is normal, not a plugin error. A pure hook result exceeding its own declared bound is a provider fault even if the resulting line happens to fit 512 bytes.

A bounded, rate-limited diagnostic identifies provider id, hook and reason category on a failure transition; repeated faults/cleanup warnings for that provider are suppressed within the logging window. Do not log raw exception messages/stack payloads, client content/tags or configuration secrets. No automatic recovery: an administrator may explicitly enable after fixing the cause. Hooks are cooperative, nonblocking and thread-safe. Stop must preserve the safety of callbacks already using captured inputs, and providers must synchronize their own start/stop resource access if it can overlap those callbacks. Core does not track active calls, wait for completion, suppress valid old results or forcibly terminate hung code. A late fault is attributed to the provider identity and, if it is currently enabled, may withdraw it again after re-enable. This is the deliberate cost of the simpler in-process lifecycle.

## Public API migration

At implementation time document the following in Javadoc and README/PR:

1. `contributeTags` becomes a registrar outbound-tag hook with owned keys, purity and validated byte metadata. Keep it during #100 for unmigrated providers; remove it in #101.
2. `includeSenderInFanOut(ClientSession)` becomes a sender-gated recipient hook with DeliveryRole/RequestContext; providers no longer read mutable negotiation state. Remove the legacy method in #101 after the last migration.
3. `ServerContext` gains capability services; migrate all in-repository construction sites/fixtures. No speculative external binary adapter is promised for 0.1.0-SNAPSHOT.
4. `Channel.memberListing(boolean)`/`statusPrefixes(boolean)` become generic member snapshots + reply rendering; update admin SAJOIN and unit fixtures.
5. `SessionWriter.enqueueBatch` callers move to generic `enqueueGroup`; any short-lived adapter delegates to registered shaping and contains no capability names. Remove adapter by final checkpoint.
6. Negotiated capabilities become immutable snapshots; mutations go through the coordinator. Unit setup uses a test negotiation helper, never `.add()` on the getter.

Keep protocol constants/token vocabulary if useful, but remove its Javadoc claim that core handlers branch on concrete capability names. Document first-release version signaling for the SPI break, consistent with the review checklist's unreleased-project interpretation of constitution III.

## Delivery checkpoints

#100 provides registration validation, defensive hook exception/result containment and safe fallback so new SPI use cannot damage core outcomes, reporting failures through the existing sanitized operator-visible diagnostic path. It does not silently suppress errors or claim runtime FAILED withdrawal/final provider-level bounded diagnostic behavior. #101 migrates the remaining providers and removes the legacy hooks. #97 provides snapshot/prune/notification mechanics. #102 connects every hook fault to that FAILED withdrawal path and adds bounded diagnostics and dedicated load proof. Each PR is built/tested independently; final failure-policy acceptance applies to the completed four-PR feature.
