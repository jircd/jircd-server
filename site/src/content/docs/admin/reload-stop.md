---
title: Reload and stop
description: Re-read the configuration with SIGHUP or REHASH and shut the server down gracefully with SIGTERM or SIGINT.
sidebar:
  order: 5
---

## Reload

`SIGHUP` (or an operator's `REHASH`) re-reads the configuration file. An invalid file is rejected and the running configuration stays. If a capability or extension fails to apply, the new configuration still applies but that extension change does not; `REHASH` reports the failure as a `NOTICE`, while `SIGHUP` logs a `WARN` such as `Configuration reloaded via SIGHUP, but could not apply <extension>: <reason>`. Fix the extension problem and reload again.

| What | On reload |
| --- | --- |
| Listeners (port, bind address, TLS, backlog) | Validated, not rebound. Restart to apply. |
| `serverName` | Restart to apply. |
| Per-connection limits (`sendqLimitBytes`, rate limit, keep-alive, registration timeout, connection limits) | Apply to connections accepted afterwards. |
| Everything else | Applies at once. If a validated new log file cannot be opened during reload, the previous logging destinations stay active while the new configuration is published. |

[Configuration](configuration.md) gives the behaviour per key. In a container, send the signal with `docker kill -s HUP jircd`.

## Stop

`SIGTERM` and `SIGINT` (`docker stop`, Ctrl-C) shut the server down gracefully:

- it stops accepting connections;
- it sends every connected client `ERROR :Closing Link: <host> (Server shutting down)`, closes the connections and exits with status 0.

This takes at most 8 seconds, inside Docker's default 10-second stop window.

- A client that stops reading, or whose connection has not yet created its session by the deadline, is closed without the `ERROR`, and a warning says how many. A client already disconnecting for another reason (`QUIT`, end of stream, `KILL`, timeout) keeps that outcome.
- A listener that fails to close during shutdown is logged, not retried: the process exits right after.

A missing or unreadable configuration at startup exits with status 1 and a `Refusing to start:` line.
