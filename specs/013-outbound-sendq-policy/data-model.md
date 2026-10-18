# Data Model: Outbound Queue Overflow Policy

## ServerConfiguration (existing record, one new component)

| Field | Type | Default | Validation |
|-------|------|---------|------------|
| `sendqLimitBytes` | int | 524288 | integer, 16384 <= value <= 2097152; otherwise `ConfigurationException` naming `sendqLimitBytes` |

Constants: `DEFAULT_SENDQ_LIMIT_BYTES`, `MIN_SENDQ_LIMIT_BYTES`, `SENDQ_LIMIT_CEILING_BYTES` (single
source of truth, like `DEFAULT_RATE_LIMIT_BUCKET_SIZE`). The record's canonical constructor gains a
component, so every direct constructor call in tests changes.

## SessionWriter (per connection, runtime state)

| Field | Meaning |
|-------|---------|
| `limitBytes` | fixed at construction from the configuration in force when the connection opened |
| `queuedBytes` | atomic sum of conservative wire-byte charges currently queued; never above `limitBytes` except by the reserved final lines |
| `overflowed` | atomic flag, set once; after it, enqueues are ignored |

State: `OPEN -> OVERFLOWED -> (ERROR + pill queued) -> closed`. `OVERFLOWED` is entered at most
once per writer. It is independent of `ClientSession`'s `CLOSING`, which `DisconnectCleanup` still
owns and claims idempotently.

## OutboundMessage accounting metadata (message lifetime)

The immutable message identity includes `messageId`, `sentAt`, sender, command, target/body and
client tags. Each enabled capability's escaped contribution byte metadata is computed once per
message/capability identity, shared safely across recipient enqueues and released with the message.
No process-wide cache retains completed messages. New messages and different capability instances
have separate memoized metadata.

| Metadata | Meaning |
|----------|---------|
| component bytes | UTF-8 prefix/command/parameter bytes with protocol syntax and CRLF |
| client tag entries | escaped byte lengths and entry counts, restricted to forwarded `+` keys |
| capability contributions | memoized escaped key/value lengths and entry counts per capability |
| queued entry charge | component bytes plus only that recipient's selected tag entries and delimiters |

The charge is a **conservative upper bound**: summing contribution entries before tag merging may
count overwritten keys, and text may be truncated at drain time. Non-colliding, untruncated lines
have exact charges. The raw-message path charges its own tags with the same escaped-byte rules.
Accounting and drain-time selection must stay consistent so capability negotiation/reload cannot
introduce uncharged tags into a queued entry. Rendering remains at drain time. See
`contracts/outbound-accounting.md` for the formula and validation matrix.

## CapabilityExtension contribution contract

`contributeTags(message)` is pure: equal message inputs yield equal tag maps, independently of
recipient, wall clock, randomness, call order and mutable external state. `msgid` derives from
`message.messageId()` and `time` from `message.sentAt()`. Its contract is documented at the Java
interface and tested for both existing implementations. This permits memoized byte metadata
without moving recipient rendering to enqueue.

## Overflow Disconnect (event)

Fields: connection token, limit bytes, queued bytes at overflow, cumulative overflow total.
Produces: one `ERROR` attempt, one QUIT broadcast (reason `SendQ exceeded`), one `sendq-overflow`
log entry, one `disconnected` log entry.

## ConnectionMonitorLog

Adds a process-wide monotonic overflow counter. No persistence; resets on restart.
