# Contract: storm survival and latency tests

- Preserve `JoinStormLoadTest`: 2,000 clients in waves of 100, connection concurrency 25, existing
  shared failure future, ClientPool, scheduled sender and support tests. Its bounds remain storm
  <15 s, quiet p99 <500 ms and max <1 s; do not turn it into the single-wave test.
- Add `SingleWaveJoinStormLoadTest`, tagged `load`: preconnect/register 2,000 clients with bounded
  connection concurrency, then release one JOIN gate for one channel. All clients read
  continuously, reach their own end-of-NAMES and remain connected through explicit teardown;
  assert zero overflow and unexpected disconnects. Reuse hardened failure/ownership patterns.
  It has no latency assertion; finite step/completion/failure/cleanup timeouts prevent hangs.
- The integration build's `loadTest` task may set `maxHeapSize = "2g"` with a comment explaining
  the T003 result: 512 MiB exhausts, while 2 GiB reaches all 2,000 clients. Keep ordinary `test`
  settings unchanged. Both load tests run through normal `loadTest` without an init script.
- Run Gradle serially with `--no-daemon`. Force execution for repeated measurements. Run seven
  alternating before/after pairs for the existing wave-of-100 test, Story1DeliveryLatencyLoadTest
  and ConcurrentConnectionScaleLoadTest with matching JVM/heap settings. Keep T002's original
  512 MiB baseline labeled separately if the new task uses 2 GiB. Record medians and justify
  measured regressions over 10%. Run the separate survival test seven times after implementation;
  its pre-fix red reproduction and all post-fix results remain visible.
- T003 is accepted: peak 31,286 B, 3x = 93,858 B, default 524,288 B retained. The scratch probe's
  failed quiet p99/max (1,789.5 ms) remains historical evidence; it is not a revised bound or a
  passing result under the new tests.
