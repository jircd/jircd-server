---
title: Outbound queue (SendQ) policy
description: The per-connection outbound queue limit, what happens when a client exceeds it, and how to size memory.
sidebar:
  order: 8
---

Each connection has a bounded outbound queue, measured in bytes of queued wire data (escaped tags and the trailing CRLF included). A client that does not read fast enough to stay under the limit is disconnected instead of letting the queue grow.

| Setting | Value |
| --- | --- |
| Key | `sendqLimitBytes` in `jircd.yaml` |
| Unit | bytes |
| Default | 524288 (512 KiB) |
| Minimum | 16384 (16 KiB) |
| Ceiling | 2097152 (2 MiB) |

A value outside the range is rejected.

When the limit is exceeded, the client is sent exactly `ERROR :Closing Link: <host> (SendQ exceeded)` (delivery is best effort if the client is not reading), other users see `QUIT :SendQ exceeded`, and the log records `connection-event=sendq-overflow ... limitBytes=<n> queuedBytes=<n> overflowTotal=<n>` followed by `connection-event=disconnected ... reason=SendQ exceeded`. `overflowTotal` counts overflows since the process started.

A configuration reload applies a new `sendqLimitBytes` to new connections only; existing connections keep the limit they were accepted with.

## Sizing memory

Worst-case queued wire data is `connections x limit`: 10,000 connections x 512 KiB = 5 GiB at the default, and 10,000 x 2 MiB = 20 GiB at the ceiling. The limit budgets wire bytes only. JVM object headers, queue nodes, per-message objects and caches add further heap on top of that figure, so size the heap with headroom. Raise the limit only if legitimate clients are being cut off, for example during large channel `JOIN` bursts, and re-check the worst-case figure for your connection count first. `maxConnections` is in [Connection limits](connection-limits.md).
