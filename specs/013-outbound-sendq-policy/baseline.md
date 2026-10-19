# Sendq policy measurements

Worktree `013-outbound-sendq-policy`, starting HEAD `b2519c5` (rebased onto main with PR #59, `f9dfac1`). Local developer laptop, loopback, Java 25, serial Gradle invocations only. All runs use `--no-daemon`; `--rerun` forces the selected load-test task to execute. Raw logs and XML are in `/private/tmp/impl50-p3/`.

## T002 — pre-change baseline

Command (seven executions):

```sh
./gradlew --no-daemon :jircd-integration-tests:loadTest --tests '*JoinStormLoadTest*' --rerun
```

Original 256-entry queue, 2,000 clients, waves of 100, connection concurrency 25, unchanged test bounds (storm <15 s, quiet p99 <500 ms, max <1 s). Default test JVM heap (512 MiB). Every execution exits 0.

| Run | Storm ms | Quiet samples | p50 ms | p99 ms | Max ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 2310 | 21 | 31.0 | 106.3 | 106.3 |
| 2 | 2304 | 21 | 27.3 | 128.6 | 128.6 |
| 3 | 2261 | 20 | 35.9 | 103.5 | 103.5 |
| 4 | 2273 | 20 | 14.0 | 79.1 | 79.1 |
| 5 | 2356 | 21 | 24.2 | 162.0 | 162.0 |
| 6 | 2371 | 21 | 26.3 | 80.5 | 80.5 |
| 7 | 2265 | 20 | 13.6 | 82.8 | 82.8 |
| Median | 2304 | — | 26.3 | 103.5 | 103.5 |

Temporary wave-size-only changes produce these expected baseline failures with the same command:

- Wave 250: exit 1, `ExecutionException: IOException: Connection closed while waiting for:  ` (end-of-NAMES read). XML contains 124 distinct connection tokens with queue-overflow warnings.
- Wave 1,000: exit 1, `ExecutionException: IOException: Storm client disconnected before teardown`. XML contains 144 distinct connection tokens with queue-overflow warnings.

Warning totals include failure cleanup and are not counts of pre-teardown disconnects. The shared failure future proves each storm has an unexpected disconnect. No completion metric is claimed for failed runs. The original test source is restored after these probes. Logs/XML: `t002-1` through `t002-7`, `t002-wave250`, `t002-wave1000`.

## T003 — accepted scratch peak measurement

The diagnostic source variants are saved under ignored `build/impl50-p3/` in this worktree; production/test sources are restored to HEAD afterward. A temporary package-private `peakQueuedBytes()` hook records per-writer outstanding estimated bytes, subtracting them when the queued closure starts running. The scratch queue capacity is 100,000 entries to avoid censoring the peak at the old 256-entry limit. The scratch test changes only the wave size to 2,000 and reads each storm writer's peak before explicit teardown. It retains the shared failure future, ClientPool, scheduled sender, connection concurrency 25, and all original time/latency bounds.

The measurement uses prefix/command/parameter/tag UTF-8 lengths, protocol delimiters, escaped tag-value expansion and CRLF, plus the then-planned 128-byte allowance for fan-out. No queue enforcement implementation is delivered. The successful measurement attempt avoids serializing at enqueue.

- Initial serialization-based probe: default 512 MiB heap exhausts; native Java instrumentation assertions follow. Interrupted with exit 130; orphaned test JVM is terminated before the next Gradle invocation. No valid measurement. Log: `t003-probe.log`.
- Lightweight size-accounting probe: same default heap exhausts. Interrupted with exit 130; orphaned test JVM is terminated before the next Gradle invocation. No valid measurement. Log: `t003-probe-light.log`.
- Lightweight probe with a temporary init script setting only load-test JVM maximum heap to 2 GiB:

  ```sh
  ./gradlew --no-daemon -I build/impl50-p3/heap.init.gradle :jircd-integration-tests:loadTest --tests '*JoinStormLoadTest*' --rerun
  ```

  Exit **1**, solely on the unchanged quiet-channel p99 assertion. All **2,000** storm clients reach end-of-NAMES and remain registered at the pre-teardown peak snapshot. Observed maximum per-writer peak: **31,286 bytes**; **3 × peak = 93,858 bytes**, below default **524,288** and ceiling **2,097,152**. Storm duration **2,116 ms**; quiet samples **19**; p50 **827.0 ms**, p99/max **1,789.5 ms**. This is a backlog measurement, not a passing load-test result. Log/XML: `t003-probe-2g`.

The user accepts the measured plain-client peak and retains the 512 KiB default. The source variants and init script remain diagnostic artifacts only; no persistent heap configuration changes are made. The 2 GiB run is not directly comparable to T002's 512 MiB runs as a performance result.

T003 is marked complete by the follow-up user decision: peak 31,286 B, 3x = 93,858 B, default 524,288 B retained. The temporary probe suffices for this measurement; no retained instrumentation is required to mark it complete. Phase 3 implementation remains pending review of the revised documents.

## Historical specification conflict — resolved by user decision

The original research D2 and T013 reserve a fixed 128 bytes for capability-contributed tags rendered later, and D2 calls the result an upper bound. Existing `CapabilityExtension.contributeTags` permits arbitrary tag maps; `CapabilityTagRenderer` accepts server-origin escaped tag data through 4,094 bytes. `SessionWriter.writeFanOutMessage` writes those rendered tags. For example, a renderer returning `Map.of("s", "x".repeat(4092))` contributes 4,094 data bytes plus `@` and a space = **4,096 wire bytes**, which is legal under the current renderer/serializer budgets but exceeds the planned allowance by **3,968 bytes per message**.

Counting client-tag lengths plus 128 cannot therefore provide the required upper bound for all existing legal fan-out messages. Summing unescaped tag-value UTF-8 lengths also needs escape-expansion accounting (`;`, space, backslash, CR and LF); the scratch estimator accounts for those bytes, but the original D2/T013 do not state this explicitly.

User decision: remove the fixed allowance. Memoize each enabled capability's escaped contribution byte metadata once per message, using a documented/tested pure `contributeTags` contract. Sum only the recipient's selected client `+` tags and negotiated enabled contributions, component bytes, delimiters and CRLF. Research D2 defines the conservative upper bound and rendered-wire tests; rendering stays at drain time. No smaller legal tag budget is introduced.

User decision on the storm: keep existing `JoinStormLoadTest` waves of 100 and all latency bounds unchanged. Add separate load-tagged `SingleWaveJoinStormLoadTest` for 2,000-client survival with zero overflow disconnects and no latency assertion. The build's integration `loadTest` task may use 2 GiB with a comment citing the probe. These are approved targets, not new measured results: T009–T015 and their alternating before/after measurements remain unrun. Future comparisons use matching heap settings and retain the original 512 MiB baseline above.


## T015 — final implementation comparisons

All measurements use serial `--no-daemon` Gradle invocations and the build's integration `loadTest` 2 GiB maximum heap. Each class runs alone in a fresh test JVM; each before/after pair runs back to back, restoring original implementation files from HEAD `c0359ad` for before and the final implementation for after. No Git metadata or stash is used. Identical test source and JVM settings apply to both sides. The original wave-of-100 JoinStormLoadTest and all existing bounds are unchanged. The two delivery tests print latency summaries without changing assertions.

For each sample the runner polls machine load averages every 0.5 seconds; the nearest readings to the XML class start/end appear below as **1/5/15-minute** values. Other workers may run concurrently on this machine. Raw logs/XML plus all load readings selected for the samples are in `/private/tmp/impl50-p3b/measurements.jsonl` and the corresponding `pair-*` files. Seven before/after pairs per class execute successfully; no failed final-series samples are excluded.

Command template (one class per invocation):

```sh
./gradlew --no-daemon :jircd-integration-tests:loadTest --tests 'net.jircd.integration.<Class>' --rerun
```

### ConcurrentConnectionScaleLoadTest

| Pair | Side | Storm ms | p50 ms | p99 ms | Max ms | Load start 1/5/15 | Load end 1/5/15 | Exit |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: |
| 1 | before | — | 1.043 | 4.471 | 4.471 | 3.87/6.69/7.28 | 3.87/6.69/7.28 | 0 |
| 1 | after | — | 1.022 | 2.083 | 2.083 | 3.88/6.65/7.26 | 3.73/6.57/7.23 | 0 |
| 2 | before | — | 1.149 | 1.610 | 1.610 | 4.26/6.37/7.12 | 4.26/6.37/7.12 | 0 |
| 2 | after | — | 1.204 | 1.518 | 1.518 | 4.24/6.33/7.10 | 4.14/6.27/7.08 | 0 |
| 3 | before | — | 0.795 | 1.881 | 1.881 | 6.31/6.65/7.18 | 6.04/6.59/7.15 | 0 |
| 3 | after | — | 1.151 | 1.906 | 1.906 | 6.12/6.59/7.15 | 6.12/6.59/7.15 | 0 |
| 4 | before | — | 0.521 | 1.612 | 1.612 | 7.08/6.82/7.20 | 6.75/6.76/7.18 | 0 |
| 4 | after | — | 0.544 | 1.567 | 1.567 | 6.85/6.78/7.18 | 6.85/6.78/7.18 | 0 |
| 5 | before | — | 0.976 | 1.370 | 1.370 | 7.41/7.04/7.25 | 7.41/7.04/7.25 | 0 |
| 5 | after | — | 1.012 | 1.686 | 1.686 | 7.14/6.99/7.23 | 6.73/6.90/7.20 | 0 |
| 6 | before | — | 0.973 | 1.522 | 1.522 | 5.81/6.71/7.12 | 6.07/6.75/7.13 | 0 |
| 6 | after | — | 1.142 | 1.824 | 1.824 | 5.82/6.68/7.11 | 5.82/6.68/7.11 | 0 |
| 7 | before | — | 1.138 | 1.810 | 1.810 | 6.80/6.89/7.17 | 6.74/6.88/7.16 | 0 |
| 7 | after | — | 1.106 | 1.940 | 1.940 | 6.28/6.78/7.12 | 6.28/6.78/7.12 | 0 |

| Metric | Before median | After median | Change |
| --- | ---: | ---: | ---: |
| p50Ms | 0.976 | 1.106 | +13.3% |
| p99Ms | 1.612 | 1.824 | +13.2% |
| maxMs | 1.612 | 1.824 | +13.2% |

### JoinStormLoadTest

| Pair | Side | Storm ms | p50 ms | p99 ms | Max ms | Load start 1/5/15 | Load end 1/5/15 | Exit |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: |
| 1 | before | 2285 | 23.900 | 98.100 | 98.100 | 3.91/6.56/7.22 | 4.24/6.59/7.23 | 0 |
| 1 | after | 2269 | 22.100 | 103.100 | 103.100 | 4.38/6.58/7.22 | 5.23/6.72/7.27 | 0 |
| 2 | before | 2198 | 20.500 | 119.500 | 119.500 | 4.45/6.30/7.09 | 6.36/6.65/7.20 | 0 |
| 2 | after | 2388 | 23.800 | 102.500 | 102.500 | 6.25/6.62/7.19 | 6.95/6.76/7.23 | 0 |
| 3 | before | 2341 | 20.400 | 150.800 | 150.800 | 5.79/6.52/7.12 | 7.01/6.74/7.19 | 0 |
| 3 | after | 2225 | 24.800 | 124.700 | 124.700 | 7.17/6.77/7.20 | 8.13/6.99/7.27 | 0 |
| 4 | before | 2156 | 14.000 | 101.100 | 101.100 | 6.38/6.68/7.14 | 7.62/6.94/7.23 | 0 |
| 4 | after | 2174 | 28.800 | 96.500 | 96.500 | 7.41/6.91/7.22 | 9.09/7.29/7.35 | 0 |
| 5 | before | 2346 | 17.500 | 159.700 | 159.700 | 6.51/6.85/7.18 | 7.27/7.01/7.24 | 0 |
| 5 | after | 2202 | 23.200 | 138.500 | 138.500 | 7.01/6.96/7.22 | 7.65/7.09/7.26 | 0 |
| 6 | before | 2221 | 24.700 | 107.600 | 107.600 | 5.76/6.66/7.09 | 6.74/6.84/7.16 | 0 |
| 6 | after | 2117 | 27.300 | 136.200 | 136.200 | 6.76/6.85/7.16 | 7.98/7.10/7.24 | 0 |
| 7 | before | 2238 | 41.100 | 150.900 | 150.900 | 6.02/6.72/7.10 | 5.93/6.69/7.08 | 0 |
| 7 | after | 2146 | 37.800 | 99.500 | 99.500 | 6.18/6.73/7.10 | 5.76/6.63/7.06 | 0 |

| Metric | Before median | After median | Change |
| --- | ---: | ---: | ---: |
| stormMs | 2238.000 | 2202.000 | -1.6% |
| p50Ms | 20.500 | 24.800 | +21.0% |
| p99Ms | 119.500 | 103.100 | -13.7% |
| maxMs | 119.500 | 103.100 | -13.7% |

### Story1DeliveryLatencyLoadTest

| Pair | Side | Storm ms | p50 ms | p99 ms | Max ms | Load start 1/5/15 | Load end 1/5/15 | Exit |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- | ---: |
| 1 | before | — | 1.573 | 3.925 | 3.925 | 5.23/6.72/7.27 | 4.72/6.56/7.20 | 0 |
| 1 | after | — | 1.609 | 4.069 | 4.069 | 4.75/6.53/7.19 | 4.37/6.42/7.15 | 0 |
| 2 | before | — | 1.301 | 2.103 | 2.103 | 7.36/6.85/7.26 | 7.01/6.78/7.24 | 0 |
| 2 | after | — | 1.528 | 4.254 | 4.254 | 7.09/6.80/7.24 | 6.60/6.71/7.20 | 0 |
| 3 | before | — | 0.942 | 4.356 | 4.356 | 7.96/6.98/7.26 | 7.32/6.86/7.22 | 0 |
| 3 | after | — | 0.731 | 3.447 | 3.447 | 7.13/6.83/7.21 | 6.56/6.72/7.17 | 0 |
| 4 | before | — | 1.541 | 2.375 | 2.375 | 9.09/7.29/7.35 | 8.30/7.18/7.31 | 0 |
| 4 | after | — | 1.518 | 2.550 | 2.550 | 8.11/7.16/7.30 | 7.62/7.07/7.27 | 0 |
| 5 | before | — | 1.630 | 2.800 | 2.800 | 7.51/7.07/7.25 | 6.99/6.97/7.22 | 0 |
| 5 | after | — | 1.544 | 2.681 | 2.681 | 6.59/6.89/7.19 | 6.14/6.79/7.15 | 0 |
| 6 | before | — | 0.997 | 2.113 | 2.113 | 7.74/7.06/7.23 | 7.68/7.06/7.23 | 0 |
| 6 | after | — | 1.664 | 2.894 | 2.894 | 7.30/6.99/7.20 | 6.96/6.93/7.18 | 0 |
| 7 | before | — | 1.599 | 3.562 | 3.562 | 5.78/6.62/7.05 | 5.48/6.54/7.02 | 0 |
| 7 | after | — | 1.053 | 2.194 | 2.194 | 5.28/6.49/7.00 | 5.08/6.40/6.96 | 0 |

| Metric | Before median | After median | Change |
| --- | ---: | ---: | ---: |
| p50Ms | 1.541 | 1.528 | -0.8% |
| p99Ms | 2.800 | 2.894 | +3.4% |
| maxMs | 2.800 | 2.894 | +3.4% |

### Separate single-wave survival

All seven final-implementation runs complete all 2,000 clients with zero overflow/unexpected disconnects. There is no latency assertion; durations are observations only.

```sh
./gradlew --no-daemon :jircd-integration-tests:loadTest --tests 'net.jircd.integration.SingleWaveJoinStormLoadTest' --rerun
```

| Run | Observed storm ms | Completed | Overflow disconnects | Load start 1/5/15 | Load end 1/5/15 | Exit |
| --- | ---: | ---: | ---: | --- | --- | ---: |
| 1 | 1956 | 2000 | 0 | 5.08/6.40/6.96 | 6.04/6.58/7.02 | 0 |
| 2 | 1925 | 2000 | 0 | 5.79/6.52/7.00 | 6.93/6.74/7.08 | 0 |
| 3 | 2134 | 2000 | 0 | 6.86/6.73/7.07 | 6.86/6.73/7.07 | 0 |
| 4 | 1965 | 2000 | 0 | 6.63/6.69/7.05 | 7.90/6.96/7.14 | 0 |
| 5 | 2025 | 2000 | 0 | 7.67/6.92/7.13 | 7.53/6.91/7.12 | 0 |
| 6 | 2001 | 2000 | 0 | 7.41/6.89/7.12 | 9.73/7.48/7.32 | 0 |
| 7 | 2139 | 2000 | 0 | 9.35/7.44/7.31 | 9.65/7.53/7.34 | 0 |

### Earlier grouped attempts and profiling (retained)

Before the final protocol, three latency classes shared one JVM per invocation. All earlier failures remain in `attempt-1/` and `attempt-2/`, each with logs, XML, per-class load readings and `measurements.jsonl`. They are not substituted into the final isolated-class medians.

- Attempt 1: seven grouped before/after pairs; control pair 4 fails Story1's 1 s bound at 1.650532 s, after pair 6 has a Story1 socket timeout; remaining invocations and seven separate survival runs pass.
- A deterministic red/green close test identifies a writer waiting two seconds for itself when its write-failure callback closes it. Skip self-join; normal external close/drain semantics remain. Store message and charge together to remove the additional per-entry Runnable allocation.
- Attempt 2: seven grouped pairs after that correction; after pairs 1–3 and control pairs 5–6 fail; all seven survival runs pass. See retained XML for exact failures rather than treating them as successful latency samples.
- A separate JFR diagnostic run passes; 93 of 298 CPU samples have WireBytes as their top application frame, mostly from repeated raw broadcast byte scans through Recipients.deliver. Share each immutable raw message's charge once per broadcast, and avoid preparing entries for already-closed writers. The independent raw-broadcast characterization test and focused suite pass. The optimized profile removes WireBytes from the leading sampled frames but still encounters grouped Story1 registration timeout; no claim is made that scan optimization alone fixes grouping. Profile/JFR/GC artifacts remain in the top-level scratch directory.
- Source review finds that TestServer/application shutdown waits for listeners, while JoinStorm waits only for client tasks; detached server connection/writer cleanup can continue into the next class. This is a concrete isolation gap, consistent with contamination; the source/profile evidence does not attribute every timeout to it. GC pauses in the diagnostic profiles are milliseconds, not evidence of multi-second stop-the-world pauses. No test/server shutdown behavior or latency bound is changed to hide the grouped failures. Future combined load-suite validation (T029) must account for that existing fixture gap.

T002 remains a separately labeled 512 MiB baseline. Final comparisons use matching 2 GiB heaps and the same one-class-per-JVM protocol on both sides.

### Regression justification and limits

Changes above 10% in the final medians are flagged explicitly rather than treated as improvements.
The full sample table and per-sample load readings above remain the evidence; these shared-machine
measurements do not isolate CPU accounting cost from JVM scheduling and external load. The new
policy adds byte reservation/accounting and consistent recipient selection to enforce the required
wire-data bound. A measured hotspot is already removed: raw JOIN/QUIT broadcasts share one charge,
and the deterministic writer self-join is fixed. The remaining costs fit the unchanged budgets and accompany the required accounting
guarantee. No bound,
retry rule or test exclusion is added. Review these relative changes before merge; they are a
written performance tradeoff, not a claim that noise explains every increase.

- `ConcurrentConnectionScaleLoadTest` `p50Ms`: 0.976 → 1.106 ms (+13.3%, absolute +0.130 ms).
- `ConcurrentConnectionScaleLoadTest` `p99Ms`: 1.612 → 1.824 ms (+13.2%, absolute +0.212 ms).
- `ConcurrentConnectionScaleLoadTest` `maxMs`: 1.612 → 1.824 ms (+13.2%, absolute +0.212 ms).
- `JoinStormLoadTest` `p50Ms`: 20.500 → 24.800 ms (+21.0%, absolute +4.300 ms).

All 42 final comparison invocations and seven final survival invocations exit 0. Every unchanged hard latency/time budget passes; all seven survival runs complete all 2,000 clients with zero overflow/unexpected disconnects. T009–T015 stop at the Phase 3 checkpoint; Phase 4 remains pending.


### T029 grouped-suite teardown validation (2026-10-19)

The unmodified grouped suite reproduced Story1 registration timeout after the single-wave storm
(exit 1, 51 s). A deterministic lifecycle regression also showed `stop()` returning with a
registered session still present. Shutdown now tracks accepted connections, closes their sockets,
and waits for connection, liveness and writer work to finish, with a 30 s connection deadline.
Listener join timeout now throws instead of reporting successful closure. This addresses the
previously documented isolation gap; it does not attribute every historical latency outlier to it.

Three consecutive executed grouped suites pass (exit 0; 44 s, 45 s, 44 s). The latter two use
`./gradlew --no-daemon loadTest --rerun-tasks` because an unforced repeat was up-to-date and is
not counted. No latency/time bound, ordering, exclusion, retry or queue-accounting behavior changes.
These are suite validations, not replacement before/after medians. T029 quickstart and PR-median
steps remain pending; Phase 4 has not started.


### T029 Round 2: failed-listener cleanup and shared deadline

Coordinator verification subsequently failed: SingleWave teardown hit the TLS listener's 3 s
join timeout, and Story1 then timed out (grouped suite exit 1, 57 s). This supersedes the earlier
three-pass reliability conclusion. The listener exception skipped `closeConnections`; a new
deterministic regression confirms that listener failure left a registered connection behind.
Carrier starvation is plausible, but the available log/XML contains no thread-state evidence
that proves it.

Stop now rejects late accepts, gives listener joins and connection draining one shared 30 s
shutdown deadline, and always attempts connection cleanup even when listeners fail. Further
failures are suppressed; genuinely stuck listeners still throw. Five consecutive forced grouped
runs pass (exit 0; 48 s, 49 s, 44 s, 45 s, 50 s); `rg -n 'Timed out'` finds no matches in each log
(exit 1 for no matches). Load-test latency bounds, ordering, exclusions and retry behavior are
unchanged. The coordinator's Duration import is retained. Quickstart and PR medians remain pending.
