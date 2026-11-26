---
title: Connection limits and registration timeout
description: Cap total and per-address connections, override limits for trusted networks, and set the registration deadline.
sidebar:
  order: 6
---

```yaml
maxConnections: 10000          # positive integer; total across plaintext and TLS listeners
maxConnectionsPerIp: 20        # positive integer; total per client bucket across all listeners
ipv6ConnectionLimitPrefix: 64  # 1-128; 128 counts each IPv6 address separately
connectionLimitOverrides: []   # optional list; defaults to no overrides
```

IPv4 clients count per /32; IPv6 clients share a bucket for the configured prefix (default /64). IPv4-mapped IPv6 clients (`::ffff:a.b.c.d`) normalize to IPv4 before bucketing and override matching.

## Overrides

Overrides replace `maxConnectionsPerIp` for clients whose address matches a CIDR. The most specific matching prefix wins, regardless of list order. Counts remain per client bucket, **not per override range**. Matching uses the client's address, even when the CIDR is more specific than the counting prefix.

```yaml
connectionLimitOverrides:
  - cidr: 203.0.113.0/24
    maxConnectionsPerIp: 200
  - cidr: 203.0.113.128/25
    maxConnectionsPerIp: 50    # wins over /24 for this half of the subnet
  - cidr: "2001:db8:1234::/48"
    maxConnectionsPerIp: 1000000  # practical per-bucket exemption; global limit still applies
```

CIDRs must use IP literals with **zero host bits**: use `203.0.113.0/24`, not `203.0.113.1/24`. IPv4 prefixes range from 0 to 32 and IPv6 prefixes from 0 to 128. Scoped or bracketed addresses and duplicate ranges (including equivalent IPv6 spellings) are rejected. Mapped IPv6 CIDRs with /96 to /128 normalize to IPv4 /0 to /32, so they cannot duplicate an equivalent IPv4 range. Each override needs a positive integer `maxConnectionsPerIp`; a large value gives an exemption without a separate list.

## What happens at the limit

Both limits count registered and unregistered connections. A connection over either limit receives `ERROR :Too many connections` and is closed. The rejection is logged as `security-event=connection-limit` with the source address, the exceeded setting, `bucket` and `limitApplied` (the threshold of the exceeded setting).

- Rejection I/O runs on virtual threads with a one-second deadline and a shared cap of 64 in-flight rejections across all listeners.
- When that cap is full, new rejected sockets close at once, with no error write and no individual log entry. Dropped rejections are counted and logged as `security-event=connection-limit-suppressed count=N`, at most once per second.
- Peers that cannot finish the TLS handshake or receive the error within the deadline are closed.
- Counts are released when connection teardown completes.

## Reload

`REHASH`/`SIGHUP` applies new limits, overrides and IPv6 grouping to subsequent accepts without disconnecting existing clients. Each connection releases its original bucket when it ends, even if the prefix has changed, so old and new buckets coexist during that transition. Lowering a limit below a bucket's current usage blocks new connections to that bucket until usage falls below it. Raise the per-address limit for deployments with many clients behind one NAT and for localhost load tests.

## Registration timeout

```yaml
registrationTimeoutSeconds: 60   # positive integer, 1-3600
```

A connection that has not completed registration within this many seconds of being accepted receives `ERROR :Registration timeout` and is closed. Registration means `NICK` and `USER` plus, if the client started capability negotiation, `CAP END`: a client parked after `CAP LS` counts as unregistered. Answering `PING`s does not extend the deadline. A registered client is never affected. A reload applies to connections accepted afterwards only.
