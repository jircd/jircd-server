# Data Model: Capability Runtime and Contributions

Extends the original Extension, ClientSession and OutboundMessage models. All proposed views/contributions are in-memory and immutable once captured. Lifecycle is defined in [runtime-lifecycle.md](contracts/runtime-lifecycle.md).

## CapabilityDescriptor and HookRegistration

| Field | Rule |
| --- | --- |
| providerId, capabilityName | Nonempty protocol-safe token; unique across discovery; providers cannot claim core cap-notify |
| requiresProviders | Immutable set; no self, duplicate, unknown or cyclic hard dependency |
| registrations | Immutable ordered typed hooks, each with a provider owner; no mutation after staging |
| prerequisites/default | Enabled owner plus declared sender/recipient/session prerequisites from one captured view; explicit default |
| ownership | One replacement owner per reply slot/server-tag key; additive slots explicitly declared and deterministically ordered |
| bounds | Nonnegative finite byte bound evaluated from validated configuration; no null, overflow or unbounded result |

Registration scope owns staged handles/resources. Close is idempotent. Removing registrations from the current table does not invalidate immutable hook definitions held by captured work. Provider start/stop and callbacks must be thread-safe with one another; core does not count/wait for active calls.

## RuntimeSnapshot

| Field | Rule |
| --- | --- |
| providers, states, offers | Immutable coherent tables; provider offers derive only from successful ENABLED entries; core cap-notify is always offered |
| hook indexes | Immutable typed arrays/maps compiled on state changes, deterministic stage/slot order |
| dependencies | Validated immutable DAG and reverse dependent closure |
| configured reply limits / desired capability states | Immutable validated capability configuration captured with the same snapshot, never independent old/new reads |

Publish a new snapshot and physically prune every affected live session's explicit names in one administrative-mutex critical section. Then queue DEL/NEW. Capture snapshot + relevant session negotiation under the same mutex, use it through ordinary preparation without revalidation, and release the mutex before hooks. No per-message lifecycle version field is needed.

## SessionNegotiation and live membership

- `explicitNames`: immutable set; mutations only under administrative control. Getter returns a snapshot, never a mutable/live set.
- `highestCapVersion`: highest supported LS semantics ever indicated on this connection; >=302 support remains sticky.
- `notificationEligible`: derived from highest version >=302 OR explicit cap-notify, not independently mutable.
- `effectiveHooks`: immutable compiled selection for the captured snapshot/explicit names; reuse selection associated with those immutable inputs rather than scanning the registry per recipient.
- ConnectionHandler.liveSessions includes unnamed sessions before CAP handling; insertion/removal/writer attachment uses administrative control. NicknameRegistry is insufficient.

Disable/failure removes withdrawn names physically. Enable does not restore them. Removing message-tags suppresses new batch framing without removing the still-held batch name. Core cap-notify remains offered independently of providers; implicit-only support stays out of LIST.

## CommittedEvent, Audience and MemberSnapshot

CommittedEvent holds kind, logical operation id, immutable subject before/after facts and permitted audience. Publish exactly once after a successful core transition, retaining existing repeated accepted-AWAY semantics. No event for rejected/no-op JOIN/nick or duplicate cleanup.

Audience deduplicates permitted peers with explicit self policy and no global-session scan. Capture departure peers before removal. MemberSnapshot contains permitted nickname/ident/display-host, consistently ordered held statuses and generic reply facts; it contains no multi-prefix flag. Across members preserve existing weak snapshot semantics; core default is highest status + nickname. Release domain locks before any hook.

## OutboundMessage, DeliveryIntent and RequestContext

OutboundMessage has immutable command, presented sender identity, target/body, normalized client tags, one sentAt and logical message id. Keep shared ids/time across recipients and through captured work. Cache immutable validated pure contributions and exact escaped bytes by envelope/registered hook identity; this is memoization only, not lifecycle control. No provider callback is queued to drain.

DeliveryIntent has core-authorized target/audience, envelope id and roles (ordinary target, sender copy, correlated request reply). Deduplicate recipient + envelope id and merge roles. No identity/visibility widening.

RequestContext has core request id, bounded provider-namespaced immutable attributes and explicit completion boundary. No credentials/raw arbitrary context in diagnostics; unsolicited peer traffic does not inherit request correlation. No label-specific core branch.

## ReplyLayout and ReplyContribution

ReplyLayout specifies schema-permitted slots, fixed fields, list/text fit policy and fixed-byte formula. A contribution carries provider owner, slot/tag origin, immutable value and declared/actual bounds. Null/illegal/out-of-bound is a fault; empty valid values are allowed. Compose independent slots in schema order, fit variable fields and validate complete output. Core retains ordinary highest-prefix/identity privacy behavior.

## OutputGroup, FramingPlan and PreparedDelivery

OutputGroup has finite members, type/typeParams, logical id, optional parent id, RequestContext and conservative byte bound. Parent graph is acyclic; total/nesting memory is bounded by SendQ reservation. No unbounded stream handle.

FramingPlan is a complete immutable ordered sequence including open/close, with exact bytes and an already validated plain alternative when applicable. Batch ref contains only ASCII letters/digits/hyphens and is unique per recipient/group. No partial admission or independently droppable close. Final group admission makes a current prerequisite selection, then appends the entire chosen sequence in one queue operation. If required capabilities went ineffective, choose plain.

PreparedDelivery contains the captured immutable runtime/session view, materialized validated FrameSequence and exact byte reservation including CRLF/tags/framing. Ordinary deliveries are not rechecked against current state at append. Valid old captured output may finish after DEL; malformed contributions are never emitted.

## State invariants and delivery ownership

- ENABLE: stage/start/validate, publish new snapshot with existing session names unchanged, queue NEW; core cap-notify is never toggleable.
- DISABLE: publish withdrawal and all-session prune under administrative mutex, queue DEL, stop outside core locks.
- FAULT: same withdrawal path with FAILED; explicit administrative enable is required for recovery. Late faults belong to provider identity, even after re-enable.
- REQ: validate ordered candidate and reply size, replace one immutable set under administrative control, ACK; rejection changes nothing.
- Captured work is permitted to complete using old views, including after stop/DEL. Providers own safe synchronization of their resources during this overlap.
- #100 introduces contracts/safe dispatch and server-time; #101 completes ownership; #97 introduces runtime transitions/cap-notify; #102 completes runtime fault withdrawal/diagnostics and load evidence.
