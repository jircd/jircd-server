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
| `queuedBytes` | atomic estimated bytes currently queued; never above `limitBytes` except by the reserved final lines |
| `overflowed` | atomic flag, set once; after it, enqueues are ignored |

State: `OPEN -> OVERFLOWED -> (ERROR + pill queued) -> closed`. `OVERFLOWED` is entered at most
once per writer. It is independent of `ClientSession`'s `CLOSING`, which `DisconnectCleanup` still
owns and claims idempotently.

## Overflow Disconnect (event)

Fields: connection token, limit bytes, queued bytes at overflow, cumulative overflow total.
Produces: one `ERROR` attempt, one QUIT broadcast (reason `SendQ exceeded`), one `sendq-overflow`
log entry, one `disconnected` log entry.

## ConnectionMonitorLog

Adds a process-wide monotonic overflow counter. No persistence; resets on restart.
