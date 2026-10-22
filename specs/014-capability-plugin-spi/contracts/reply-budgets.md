# Contract: Reply, Tag and SendQ Budgets

Implements FR-013/014, SC-003/005. This replaces capability-specific reply-width assumptions in ConfigurationLoader and HostmaskReplyBudget with generic schema/contribution bounds while preserving their current safe acceptance envelope.

## Units and validation entry points

All bounds count serialized UTF-8 bytes, including separators/escaping where relevant, never Java character counts. Arithmetic uses checked addition/multiplication; null, negative, overflowed or unbounded bounds reject registration. No hook may allocate an arbitrarily large output before checking its declared bound.

A descriptor's reply bound is a deterministic function of validated configured limits and generic reply facts. Startup and REHASH validate the conservative envelope of all discovered valid provider decorations, including disabled providers that could later be enabled. Runtime enable validates actual active slot ownership/composition using the same checker. Validation never invokes per-user rendering hooks. Missing/invalid bound declarations are provider faults, not zero-byte defaults.

The conservative catalog prevents routine toggles from invalidating previously accepted configuration. A larger future plugin requires startup/reload validation against its installed descriptor. Mutually exclusive replacement slots reserve their maximum, independent composable slots sum; additive slots sum all compatible owners. Report provider/slot/formula/configuration fields in actionable errors without sensitive values. Pure configuration validation errors publish nothing.

## NAMES and WHO

Let S = running server-name bytes, N = configured maximum nickname bytes, C = configured channel-name bytes, U = IDENT_MAX_BYTES and H = PRESENTED_HOST_MAX_BYTES. Use running server identity during reload, as ConfigurationReloader already does. P is maximum status-string bytes for the compatible registered status transforms; I is additional identity bytes beyond nickname; F = 2 + P reserves away H/G and IRC-oper `*` plus channel statuses.

| Reply | Conservative complete command-section bound, including CRLF |
| --- | --- |
| One 353 member | `S + 2N + C + 13 + P + I` |
| 366 end of NAMES | `S + N + C + 29` |
| 352 WHO with empty fitted realname | `2S + 2N + C + U + H + 18 + F` |

Core highest-status default has P <=1. multi-prefix declares the sum of supported PREFIX symbol bytes (2 for @/+ today), without naming that capability in core. With P=2/I=0, the formula is the current `S+2N+C+15`. WHO with F=4 exactly matches the current HostmaskReplyBudget assumption. A later userhost-in-names declares I=`U+H+2` for `!ident@host`; it must compose with P=2, not be checked only in isolation. Generic extra-field slots add all separators/field bytes permitted by their schema.

Keep every other HostmaskReplyBudget entry (333, 311/314, 302, INVITE) and its existing limits. Core owns fixed fields and fit policy. Remove the multi-prefix-specific WHO_FLAGS_BYTES constant and ConfigurationLoader's hidden two-prefix allowance; express those bounds through layouts plus the installed catalog. Make startup/reload constructors, runtime enable and test construction use the same bound validator.

At runtime collect/render complete member tokens before packing 353 lines; split only between tokens. Never truncate nick/status/ident/host midway. Apply fitting only to permitted free text (e.g. WHO realname), after every decoration. A normal fully valid configuration must be able to fit one worst-case member and its closing reply. If a provider lies about its bound, quarantine it and re-render using the remaining/default slots, preserving the core reply/366. A core-only unrepresentable identity is reported through the existing bounded OutboundSizeGuard warning, never a silent writer drop. Assert fitted text/content and closing numeric, not just numeric presence.

## Tags

Retain MessageSerializer constants and origin semantics:

- Command section: at most 512 bytes including CRLF, independently of tags.
- Client inbound tag section: at most 4096 bytes including `@` and separating space (existing ConnectionHandler limit).
- Each forwarded-client/server tag origin: at most 4094 data bytes under MessageSerializer's exact escaped accounting.
- Combined outbound tag section: at most 8191 bytes including framing/separators.

A tag hook declares ownership and origin, returns immutable validated data, and is measured using the real serializer's escaping rules. Count semicolons, equals signs, escaped spaces/backslashes/CR/LF and UTF-8 expansion. Forwarded `+` tags and server contributions are checked separately and again after deterministic merge. Duplicate keys/forged server keys cannot hide bytes or override another owner's contribution.

Memoize a pure provider's validated tag map AND exact byte metadata per envelope/hook identity; the identity is a computation-sharing key only, not a lifecycle counter. Do not invoke a provider once to measure and again on drain, or keep a deferred supplier capturing a provider. A per-recipient plan may reuse these immutable results; share common work rather than copying every map on every fan-out. A recipient-dependent contribution declares that fact and is evaluated once for that preparation.

Adding batch tags or later label/group metadata participates in the server origin budget. Validate complete member messages after shaping. A group with insufficient tag room is handled before any opener is admitted; never silently drop a tagged member during drain. Attributable plugin defects fail that owner; valid bounded data hitting a documented size limit uses the declared fallback/rejection and a bounded diagnostic without faulting unrelated providers.

## Queue accounting and bounded preparation

The core writer admits only validated immutable FrameSequences. Its reservation counts exact serialized command + tag section + CRLF bytes for every frame, including open/close/nested wrappers, before publication. The queue holds no plugin callbacks or mutable state. Queue and in-flight accounting retain existing SendQ semantics and are released exactly once on dequeue/overflow/disconnect.

For groups, reject/preflight by a conservative byte bound before building a full framing copy, reserve/refine to exact size and atomically append the entire sequence. If it cannot fit, admit no prefix and use existing SendQ overflow cleanup for that recipient. Plain fallback still counts and validates all member bytes. NEW/DEL/ACK are charged like ordinary output; a full SendQ disconnects only that session and cannot stall/roll back the lifecycle transaction.

Ordinary output uses the runtime/session view captured before preparation and is appended without lifecycle revalidation. A state change may overtake it. This needs no extra queue beyond the existing SendQ. Groups prepare validated framed/plain alternatives outside locks; final group admission chooses using current owner/session prerequisites under the existing administrative mutex, then appends under one writer queue lock. Only this group choice is coordinated with state changes. All plugin calls, large copies and serialization remain outside those locks. An admitted group keeps its close; a not-yet-admitted group whose prerequisites went ineffective is plain.

## Performance proof

The following are acceptance budgets to measure, not achieved results:

| Workload | Budget/evidence |
| --- | --- |
| Existing ordinary delivery / 1,000 live sessions | Message max <1 s; same-machine p99 and throughput compared with baseline |
| Existing JOIN storm with quiet channel | Storm <15 s, quiet p99 <500 ms and max <1 s |
| New mixed-capability fan-out | 10/100/1,000 recipients; same logical messages, payloads, negotiation mixes and rate settings; p99 <1 s; CPU/allocation/throughput comparison |
| New lifecycle publication (#102 final proof) | At 1,000 live sessions, administrative-mutex wait + immutable snapshot publication/all-session prune p99 <=100 ms; report wait and hold separately. Notification enqueue, config I/O, start/stop and socket drain are separate measurements. Do not wait for old captured output |
| Concurrent rendering/grouping/toggling | No deadlock; bounded reservations and operation completion. Old captured output after DEL is allowed. Assert current-view new captures, complete admitted groups and plain unadmitted groups when prerequisites are ineffective |

Message throughput must regress by no more than 10% against the recorded main baseline unless explicitly justified in review. Latency/CPU/allocation increases above 10% also require optimization or recorded justification. Use a warmup and at least five identical measured runs per revision; report medians plus p99/max samples, machine/JDK/JVM settings, workload and raw result paths. Existing baseline has no lifecycle implementation: the new publication metric uses the absolute budget, not a fabricated relative comparison.

Hot-path code review must show no registry-wide enabled() scan for each recipient; session hook views change only on negotiation/runtime revision. Event peers are collected once, bounded by shared memberships. Lifecycle is O(sessions × withdrawn names), not a per-message global scan. The existing administrative mutex, pre-materialized tags and group alternatives must be profiled. Each hot-path PR runs applicable existing loads; #97 records repeatable publication timings from its socket fixture, and #102 adds dedicated load classes and final SC-005 evidence.
