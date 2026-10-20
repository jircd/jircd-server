# Contract: server configuration

```yaml
# Maximum bytes of outbound data queued for one connection before the server closes it.
# Default 524288 (512 KiB). Minimum 16384. Maximum 2097152 (2 MiB).
sendqLimitBytes: 524288
```

- Top-level key, integer, unit bytes. Absent means the default.
- Zero, negative, non-integer, below the minimum or above the maximum is rejected with a message
  naming `sendqLimitBytes` (startup: server does not start; reload: previous configuration stays
  active), as for every other setting.
- Applies to all listeners, plaintext and TLS.
- Reload: affects connections opened after the reload only.
- Worst-case queue memory is `connections x sendqLimitBytes` (documented in the README); at the
  default, 10,000 connections x 512 KiB = 5 GiB, at the ceiling 10,000 x 2 MiB = 20 GiB.

- The 512 KiB default is confirmed by T003: peak 31,286 B, 3x = 93,858 B. The ceiling stays 2 MiB.
- Queue charges upper-bound rendered wire bytes using the selected recipient's actual capability
  contribution metadata, escaped tag bytes, delimiters and CRLF; no fixed tag allowance. See
  [outbound accounting](outbound-accounting.md). The product describes queued wire data; JVM
  object/cache overhead is additional to the `connections x limit` figure.
- The optional 2 GiB integration `loadTest` heap is a test-harness setting, not this configuration
  key or a server deployment requirement; see [load-test contract](load-tests.md).
