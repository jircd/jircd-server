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
