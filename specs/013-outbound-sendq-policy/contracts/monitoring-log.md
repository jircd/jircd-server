# Contract: connection monitoring log

New entry, once per overflow disconnect:

```
connection-event=sendq-overflow connection=<token> limitBytes=<n> queuedBytes=<n> overflowTotal=<n>
```

Existing entry, reason distinct from all other causes:

```
connection-event=disconnected connection=<token> durationMs=<n> reason=SendQ exceeded
```

- `overflowTotal` is a monotonic count since the process started, so a rate can be derived from the log.
- Exactly one `sendq-overflow` and one `disconnected` entry per overflowing connection, even when
  other disconnect triggers race it.

- `queuedBytes` uses the conservative recipient wire-byte charges defined in
  [outbound accounting](outbound-accounting.md), including escaped tags and CRLF; it is not a
  direct heap measurement.
