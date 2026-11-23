---
title: OPER protection
description: Lock out sources that fail OPER repeatedly and refuse OPER over plaintext connections.
sidebar:
  order: 7
---

These settings apply to the `admin` extension's `OPER` command ([Configuration](configuration.md#capabilities-and-extensions)).

Failed `OPER` attempts are counted per source across reconnects and usernames. IPv4 uses /32; IPv6 uses `ipv6ConnectionLimitPrefix` (default /64), with mapped IPv4 addresses normalized. After the threshold, `OPER` is refused without checking credentials and the connection is closed, even for a correct password. A successful `OPER` before lockout clears that source's failures.

```yaml
operFailureThreshold: 5  # integer 1–20
operLockoutSeconds: 300  # integer 1–86400, fixed window from the first failure
operRequireTls: false    # set true to reject plaintext OPER with 491 ERR_NOOPERHOST
```

| Key | Range | Default |
| --- | --- | --- |
| `operFailureThreshold` | 1–20 | 5 |
| `operLockoutSeconds` | 1–86400 | 300 |
| `operRequireTls` | `true` or `false` | `false` |

These settings reload on `REHASH`/`SIGHUP`. New window durations apply to new windows; existing deadlines remain unchanged. Expired entries are reclaimed on subsequent `OPER` attempts.

Tracking is capped at 10,000 sources. While it is full, new sources use per-session failure counting: credentials are still checked and a failure at the threshold disconnects that session. Existing source lockouts stay intact. Source addresses come from the accepted socket; if unavailable, `OPER` uses a per-connection key. Restarting the server clears this in-memory state.

Credentials are described in [Generating an administrator credential](admin-credentials.md). The sample container configuration sets `operRequireTls: true`; enable a TLS listener before using `OPER` there.
