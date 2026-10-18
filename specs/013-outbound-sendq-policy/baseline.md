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
