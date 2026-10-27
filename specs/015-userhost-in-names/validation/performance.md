# Performance evidence: issue #117 (T022)

## Findings and written review justification

All asserted absolute budgets passed. **SC-004 performance evidence is satisfied with the written justification below for the relative >10% observations**; this is not a claim that noisy timings demonstrate every existing path is within 10%. T024 independent review and T025 final acceptance remain unchecked.

- The lower-load, alternating dedicated NAMES rerun replaces the earlier off-first series. Median request throughput falls **17.1% / 46.7% / 24.3%** at **10 / 100 / 1,000 members**, beyond the empirical two-unit screens of **4.7% / 4.2% / 5.0%**. This is a reproducible opt-in workload cost: on-mode enables multi-prefix and expands each identity by 20 bytes; off-mode uses bare identities. The modeled complete NAMES byte throughput increases **64.9% / 50.7% / 126.7%**, respectively. All reported measured dedicated requests remain below 6 ms against the 1 s budget. The change in requests/s is justified by the larger output and extra opted-in rendering, rather than an existing unnegotiated path doing more work.
- Main→candidate old fan-out throughput differences remain **−16.3%** (100-recipient groups) and **−10.3%** (100-recipient member listings), inside their conservative screens of 28.5% and 13.9%. Member-listing CPU is **+26.0%** (46.6→58.7 ms), marginally beyond its 22.3% screen. **For review, these main→candidate changes are attributed to the old harness and contended conditions, not execution of the new capability.** Code inspection below shows no new per-request capability work in that workload. The measurements themselves do not prove this causal attribution or establish a precise ≤10% bound.

### Verified code paths and scope of the justification

`MemberReplies.namesTokens` is unchanged from main `a2a12ad`. It captures the requester’s negotiated hook view, and when both MEMBER_STATUS and NAMES_IDENTITY hook lists are empty it takes the same nickname/highest-status fast path. Ident/display-host resolution occurs only when customIdentity is true. `HookDispatcher` and the measured fan-out test are also unchanged from that baseline. Startup enables only explicitly enabled capability entries (`JircdServerApplication`); userhost-in-names is omitted from the fan-out YAML, disabled, and never negotiated there. Its new identity hook therefore cannot run in those measured NAMES or WHO paths.

One premise in the handoff required correction: **the fan-out sender does negotiate multi-prefix** (`CAP REQ :message-tags server-time echo-message batch multi-prefix`), and some recipient mixes do too. The sender issues the NAMES/WHO measurements, so those listings use the existing status hook rather than the no-hooks fast path. That status-only path is unchanged and `customIdentity` remains false. The justification is absence of new capability work, not a claim that this entire workload negotiates neither capability.

The old single-pass fan-out harness has established worst-phase throughput CVs around 18–24% and cold-start/timing limitations. #111 / PR #118 replaces that harness. These runs still use the old version; the newer harness’s tighter limits cannot be retroactively applied. No new per-request work on these paths plus this known measurement variability justifies treating the fan-out flags, including CPU +26%, as harness/environment variation for this review. This is a code-based review judgment, explicitly **not proven by the measurements**.

The dedicated comparison changes the requested operation: bare nicknames versus opted-in full identities, with multi-prefix enabled on the on-side as well. It is not a regression of the unchanged default request path and does not isolate userhost-in-names from multi-prefix. The byte model and low absolute latency below provide the written justification for accepting this feature cost under the plan’s relative-budget rule. Review approval and final project acceptance are still tracked separately by T024/T025.

## Procedure and conditions

Runs were supplied from `scratchpad/perf117` by the coordinator on 2026-10-27; this analysis did not execute Gradle, irctest, or any timed workload. The coordinator kept the original procedure, conditions, JUnit reports and logs outside the repository; the commands and recorded order are summarized below. Baseline is main `a2a12ad`, worktree `.worktrees/irctest-main`; candidate is the uncommitted `015-userhost-in-names` working tree on documentation HEAD `4c0ce89`. The old CapabilityFanoutLoadTest source is byte-identical in both worktrees (current source hash `c535e4a99bd7bb3a06cffa3ed1bb1d99cc04ebb975972844ab506ddee9d5fdf3`).

Full-suite procedure: `GRADLE_OPTS=-Dorg.gradle.daemon=false`, then `env X=1 ./gradlew -q --continue cleanLoadTest loadTest` on each revision. Warm-base/warm-cand are excluded. Measured order: B1 C1, C2 B2, B3 C3, C4 B4, B5 C5. Five full runs per revision passed: 13 assertions-bearing test invocations per baseline run and 16 per candidate run (the candidate adds three NAMES sizes).

Dedicated NAMES replacement series (`perf117b`, 2026-10-27): the coordinator ran `env JIRCD_NAMES_BASELINE=true|false ./gradlew -q :jircd-integration-tests:cleanLoadTest :jircd-integration-tests:loadTest --tests '*UserhostInNamesLoadTest'` on the candidate. Warm-off and warm-on are excluded. The recorded measured order is **off1 on1, on2 off2, off3 on3, on4 off4, off5 on5**. Starting 1-minute load is **4.72–6.66** for measured runs (warmups 5.61/5.65); all 12 invocations exited 0, and all 36 JUnit cases passed without errors or skips. The earlier always-off-first `perf117` dedicated series had an order confound and is **superseded**, not pooled into these estimates.

Each NAMES size additionally has one internal warmup request and five measured requests per invocation. On-mode configures/negotiates both multi-prefix and userhost-in-names; off-mode omits both. Timing includes the PING barrier, response parsing and membership assertions, so these are end-to-end harness request rates. A p99 from five requests is effectively the maximum; a median of per-run p99s is not a pooled p99.

The original full-suite series is unchanged: 12 invocations including two excluded warmups, all rc=0, 13 test cases per baseline invocation and 16 per candidate invocation. Together with the replacement dedicated series, there are **20 measured invocations / 175 passing cases** and four excluded warmup invocations / 35 passing cases. KeepAliveLoadTest and RateLimitLoadTest emit no timing metrics; each full run passes their two and one cases.

**Conditions:** full-suite measured starting load remains 6.33–14.12, largely from back-to-back Gradle runs and other agents per the coordinator. Dedicated replacement load is lower as recorded above, but not idle. Full-suite durations range 44–64 s baseline and 47–50 s candidate; dedicated durations were not recorded. Both worktrees configure Java 25 and a 2 GiB load-test heap. Exact inherited JDK/JVM environment, hardware model and power source were not captured in the supplied procedure/conditions files. External warmup invocations use separate JVMs and do not remove old fan-out per-JVM cold-start effects. No workload was rerun by this analysis.

## Noise method and #111 limits

For each metric use five per-run reports per side, excluding warm directories. Tables give the **median [minimum, maximum] of those five reports**, plus `(candidate/base − 1) × 100%`. A negative throughput change is worse; positive latency/CPU/allocation changes are worse. Diagnostic counters have no relative performance budget; a zero baseline has undefined percentage.

Use the current #111 analysis script's normal-approximation median standard error: `SE(median)=1.2533 × sample_sd / sqrt(5)`. One difference noise unit, as percent of the baseline median, is `100 × 1.2533 × sqrt(sd_base²/5 + sd_cand²/5) / median_base`. Differences within **two units** are unresolved by that screen. This combines within-side variation rather than inflating noise with the treatment shift itself. With five observations, non-normal distributions and contention, it is a descriptive screen, not a calibrated confidence interval or hypothesis test.

#111's revised multi-round/minimum-window harness differs from these old single-pass runs. At the original analysis time its interim throughput limits (e.g. 2.4–7.2% in its low-noise phases) were marked superseded pending another control rerun. Those tighter numbers do not apply here. The methodological source was #111 working tree at `ed1098c` plus local changes; the method and detection-limit status are summarized here, with source evidence retained outside the repository.

For the old fan-out throughput/p99/CPU metrics, conservatively use the **larger of this series' empirical two-unit screen and the historical same-harness A/A two-unit limit** from the [spec-014 #102 control](../../014-capability-plugin-spi/validation/pr-102/performance.md). Its old worst throughput CVs are 23.7% (100 away), 18.0% (100 group), and 19.5% (1,000 member); their historical detection limits are 37.4%, 28.4% and 30.8%. The referenced control report summarizes the historical per-phase limits; the table screens below take their maximum with this series’ empirical screen. The old A/A conditions were less contended, so even this conservative choice cannot guarantee coverage of today's noise. Other metrics and dedicated NAMES have no matching A/A control; their screens are empirical only and flags are exploratory. An observed loss under 10% does not prove that a true 10% loss is excluded when the screen is larger than 10%.

## Absolute budgets

Observed maxima across measured baseline/candidate and dedicated runs (warmups separately passed their assertions):

| Measurement | Largest reported value | Budget |
| --- | --- | --- |
| Ordinary delivery max | 8.865 ms | <1,000 ms |
| Fan-out phase max | 471.083 ms | <1,000 ms |
| JOIN storm quiet p99 / max | 149.4 ms | p99 <500 ms, max <1,000 ms |
| JOIN storm total | 2,262 ms | <15,000 ms |
| Lifecycle wait + swap/prune p99 | 6.994 ms | ≤100 ms |
| NAMES max (all measured series/sizes) | 6.019 ms | p99 <1,000 ms |

## Main → candidate: fan-out throughput

Scenarios are recipients/phase. Throughput counts phase items/s, not comparable item types between phases.

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median | 2-unit screen | Assessment |
| --- | --- | --- | --- | --- | --- | --- |
| 10/message | throughputPerSec | 24,715 [24,113, 26,743] | 24,005 [22,741, 26,616] | -2.9% | 8.7% | Observed loss ≤10% / improvement |
| 10/away | throughputPerSec | 34,798 [32,233, 39,967] | 36,936 [36,290, 40,391] | +6.1% | 12.9% | Observed loss ≤10% / improvement |
| 10/member | throughputPerSec | 3,057 [2,932, 3,110] | 3,175 [3,062, 3,260] | +3.9% | 8.8% | Observed loss ≤10% / improvement |
| 10/group | throughputPerSec | 22,781 [21,811, 24,450] | 22,758 [20,114, 23,797] | -0.1% | 10.8% | Observed loss ≤10% / improvement |
| 100/message | throughputPerSec | 225,668 [193,129, 234,745] | 205,474 [152,202, 259,917] | -8.9% | 20.6% | Observed loss ≤10% / improvement |
| 100/away | throughputPerSec | 507,485 [374,820, 602,017] | 484,066 [348,558, 560,271] | -4.6% | 37.4% | Observed loss ≤10% / improvement |
| 100/member | throughputPerSec | 2,041 [1,953, 2,271] | 1,831 [1,554, 2,140] | -10.3% | 13.9% | Inside noise; unresolved |
| 100/group | throughputPerSec | 55,237 [38,263, 61,615] | 46,220 [38,703, 59,724] | -16.3% | 28.5% | Inside noise; unresolved |
| 1000/message | throughputPerSec | 503,578 [411,354, 599,387] | 460,038 [419,496, 562,673] | -8.6% | 19.8% | Observed loss ≤10% / improvement |
| 1000/away | throughputPerSec | 761,106 [691,903, 816,770] | 742,317 [701,373, 864,932] | -2.5% | 11.8% | Observed loss ≤10% / improvement |
| 1000/member | throughputPerSec | 1,442 [818, 1,666] | 1,622 [1,393, 1,707] | +12.5% | 30.8% | Observed loss ≤10% / improvement |
| 1000/group | throughputPerSec | 121,017 [117,634, 134,223] | 127,472 [125,582, 131,198] | +5.3% | 9.2% | Observed loss ≤10% / improvement |

## Main → candidate: fan-out latency, CPU and allocation

All time fields are ms; allocMB is the harness-reported MB per phase. CPU/allocation are process counter summaries, not profiles. The CPU flag above is the only >10% worsening here outside the conservative screen; the code-based review justification above addresses it, without claiming measurement proof. Source measurements remain outside the repository.

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| 10/message | allocMB | 46.7 [46.7, 46.9] | 46.7 [46.5, 46.9] | +0.0% |
| 10/message | cpuMs | 617.4 [521.8, 636.3] | 614.6 [583.3, 666.8] | -0.5% |
| 10/message | maxMs | 49.826 [46.568, 50.952] | 49.696 [45.294, 50.029] | -0.3% |
| 10/message | p50Ms | 28.994 [28.812, 30.364] | 29.576 [27.202, 31.491] | +2.0% |
| 10/message | p99Ms | 49.566 [46.303, 50.653] | 49.472 [44.981, 49.631] | -0.2% |
| 10/away | allocMB | 1.2 [1.2, 1.3] | 1.2 [1.2, 1.4] | +0.0% |
| 10/away | cpuMs | 47.6 [39.5, 50.5] | 46.1 [40.1, 47.1] | -3.2% |
| 10/away | maxMs | 4.88 [4.617, 5.673] | 4.667 [4.524, 5.082] | -4.4% |
| 10/away | p50Ms | 3.374 [2.892, 4.094] | 3.11 [2.86, 3.416] | -7.8% |
| 10/away | p99Ms | 4.867 [4.598, 5.669] | 4.666 [4.508, 5.066] | -4.1% |
| 10/member | allocMB | 1.1 [1.1, 1.1] | 1.1 [1.1, 1.1] | +0.0% |
| 10/member | cpuMs | 34 [31.8, 35.6] | 32.7 [29.3, 35.4] | -3.8% |
| 10/member | maxMs | 0.517 [0.491, 0.629] | 0.576 [0.452, 0.654] | +11.4% |
| 10/member | p50Ms | 0.35 [0.338, 0.356] | 0.32 [0.309, 0.367] | -8.6% |
| 10/member | p99Ms | 0.517 [0.491, 0.629] | 0.576 [0.452, 0.654] | +11.4% |
| 10/group | allocMB | 3.4 [3.4, 3.4] | 3.4 [3.4, 3.4] | +0.0% |
| 10/group | cpuMs | 66.5 [62, 68.3] | 69 [61.8, 76.3] | +3.8% |
| 10/group | maxMs | 1.058 [0.915, 1.247] | 1 [0.914, 1.146] | -5.5% |
| 10/group | p50Ms | 0.114 [0.099, 0.152] | 0.105 [0.097, 0.145] | -7.9% |
| 10/group | p99Ms | 0.222 [0.214, 0.297] | 0.248 [0.23, 0.288] | +11.7% |
| 100/message | allocMB | 136 [134.5, 136.9] | 134 [133.7, 140.6] | -1.5% |
| 100/message | cpuMs | 1,084.8 [1,047.4, 1,272.4] | 1,032.8 [902, 1,656.3] | -4.8% |
| 100/message | maxMs | 84.072 [80.772, 96.864] | 92.421 [72.402, 125.608] | +9.9% |
| 100/message | p50Ms | 44.963 [44.233, 53.827] | 52.072 [40.081, 75.513] | +15.8% |
| 100/message | p99Ms | 83.602 [80.266, 96.325] | 91.827 [71.972, 124.929] | +9.8% |
| 100/away | allocMB | 3.5 [3.5, 3.7] | 3.6 [3.5, 3.7] | +2.9% |
| 100/away | cpuMs | 55.2 [47.8, 80.5] | 63.6 [41, 74.7] | +15.2% |
| 100/away | maxMs | 4.546 [3.808, 6.132] | 4.79 [4.007, 6.656] | +5.4% |
| 100/away | p50Ms | 3.433 [2.871, 4.719] | 3.925 [3.059, 4.979] | +14.3% |
| 100/away | p99Ms | 4.512 [3.758, 6.017] | 4.726 [3.973, 6.625] | +4.7% |
| 100/member | allocMB | 7.8 [7.7, 7.8] | 7.7 [7.7, 7.8] | -1.3% |
| 100/member | cpuMs | 46.6 [40.4, 54.1] | 58.7 [47.6, 66.1] | +26.0% |
| 100/member | maxMs | 1.008 [0.863, 1.063] | 1.172 [0.928, 2.579] | +16.3% |
| 100/member | p50Ms | 0.638 [0.534, 0.668] | 0.668 [0.591, 0.711] | +4.7% |
| 100/member | p99Ms | 1.008 [0.863, 1.063] | 1.172 [0.928, 2.579] | +16.3% |
| 100/group | allocMB | 29.3 [28.9, 29.9] | 29.7 [28.7, 30] | +1.4% |
| 100/group | cpuMs | 406.2 [330.3, 687.6] | 456.1 [348.9, 657.9] | +12.3% |
| 100/group | maxMs | 15.638 [1.847, 28.666] | 12.656 [0.218, 37.573] | -19.1% |
| 100/group | p50Ms | 1.149 [0.126, 7.894] | 0.184 [0.093, 10.214] | -84.0% |
| 100/group | p99Ms | 9.454 [0.773, 25.154] | 9.657 [0.17, 34.38] | +2.1% |
| 1000/message | allocMB | 1,262.4 [1,257.5, 1,277.4] | 1,269.6 [1,259.1, 1,279.6] | +0.6% |
| 1000/message | cpuMs | 4,942.2 [3,864, 6,136.6] | 5,086.3 [3,938.5, 5,943.3] | +2.9% |
| 1000/message | maxMs | 383.238 [320.747, 471.083] | 419.395 [341.63, 462.716] | +9.4% |
| 1000/message | p50Ms | 255.44 [218.038, 294.198] | 273.427 [207.902, 313.937] | +7.0% |
| 1000/message | p99Ms | 381.932 [319.595, 468.834] | 417.256 [338.839, 461.327] | +9.2% |
| 1000/away | allocMB | 29.7 [29.4, 29.9] | 29.7 [29.3, 30.3] | +0.0% |
| 1000/away | cpuMs | 416.8 [344.5, 445.6] | 386.5 [363.8, 455.4] | -7.3% |
| 1000/away | maxMs | 31.382 [28.923, 34.485] | 31.989 [27.563, 33.899] | +1.9% |
| 1000/away | p50Ms | 20.52 [17.116, 26.629] | 21.414 [17.878, 26.578] | +4.4% |
| 1000/away | p99Ms | 31.178 [28.778, 34.378] | 31.815 [27.385, 33.693] | +2.0% |
| 1000/member | allocMB | 37.1 [35.2, 70.9] | 36.3 [35.4, 36.8] | -2.2% |
| 1000/member | cpuMs | 66.3 [58, 100.8] | 64.1 [46.8, 70] | -3.3% |
| 1000/member | maxMs | 1.927 [1.686, 2.663] | 2.122 [1.519, 2.532] | +10.1% |
| 1000/member | p50Ms | 0.741 [0.473, 1.151] | 0.513 [0.36, 0.592] | -30.8% |
| 1000/member | p99Ms | 1.927 [1.686, 2.663] | 2.122 [1.519, 2.532] | +10.1% |
| 1000/group | allocMB | 298.4 [269.2, 302.5] | 300.5 [297.7, 300.7] | +0.7% |
| 1000/group | cpuMs | 1,954.6 [1,873.1, 2,157.2] | 1,966.8 [1,651, 2,038.4] | +0.6% |
| 1000/group | maxMs | 38.293 [36.08, 50.346] | 43.396 [37.445, 61.819] | +13.3% |
| 1000/group | p50Ms | 15.613 [13.347, 15.842] | 15.777 [14, 16.133] | +1.1% |
| 1000/group | p99Ms | 28.931 [24.775, 32.875] | 31.062 [30.888, 32.577] | +7.4% |

## Main → candidate: Lifecycle publication (1,000 sessions)

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| del-receipt-after-change-start (includes enqueue and drain) | maxMs | 31.423 [27.377, 32.013] | 26.419 [23.646, 29.259] | -15.9% |
| del-receipt-after-change-start (includes enqueue and drain) | p99Ms | 22.164 [21.433, 24.599] | 21.124 [19.638, 22.216] | -4.7% |
| del/new-enqueue | maxMs | 1.951 [1.48, 2.606] | 1.901 [1.36, 2.808] | -2.6% |
| del/new-enqueue | p99Ms | 1.951 [1.48, 2.606] | 1.901 [1.36, 2.808] | -2.6% |
| extension-change-total | maxMs | 3.253 [2.559, 3.487] | 2.423 [1.259, 3.49] | -25.5% |
| extension-change-total | p99Ms | 3.253 [2.559, 3.487] | 2.423 [1.259, 3.49] | -25.5% |
| mutex-wait | maxMs | 0.002 [0.001, 6.077] | 0.002 [0.001, 0.008] | +0.0% |
| mutex-wait | p99Ms | 0.002 [0.001, 6.077] | 0.002 [0.001, 0.008] | +0.0% |
| rehash-change-total (file I/O, parse, callbacks) | maxMs | 20.156 [14.799, 39.426] | 16.103 [13.02, 16.817] | -20.1% |
| rehash-change-total (file I/O, parse, callbacks) | p99Ms | 20.156 [14.799, 39.426] | 16.103 [13.02, 16.817] | -20.1% |
| swap+prune (held) | maxMs | 1.943 [1.316, 2.383] | 1.274 [0.821, 1.805] | -34.4% |
| swap+prune (held) | p99Ms | 1.943 [1.316, 2.383] | 1.274 [0.821, 1.805] | -34.4% |
| wait+swap+prune (budget) | maxMs | 1.944 [1.316, 6.994] | 1.274 [0.822, 1.806] | -34.5% |
| wait+swap+prune (budget) | p99Ms | 1.944 [1.316, 6.994] | 1.274 [0.822, 1.806] | -34.5% |

## Main → candidate: Notification publication (1,000 sessions)

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| no-notify disable | maxMs | 0.467 [0.438, 0.546] | 0.504 [0.394, 2.018] | +7.9% |
| no-notify disable | p50Ms | 0.342 [0.296, 0.373] | 0.311 [0.291, 0.4] | -9.1% |
| no-notify disable | p99Ms | 0.467 [0.438, 0.546] | 0.504 [0.394, 2.018] | +7.9% |
| no-notify enable | maxMs | 0.195 [0.174, 0.251] | 0.199 [0.148, 0.248] | +2.1% |
| no-notify enable | p50Ms | 0.088 [0.082, 0.098] | 0.088 [0.08, 0.095] | +0.0% |
| no-notify enable | p99Ms | 0.195 [0.174, 0.251] | 0.199 [0.148, 0.248] | +2.1% |
| notify-302 disable | maxMs | 3.351 [3.029, 3.709] | 2.752 [2.122, 2.991] | -17.9% |
| notify-302 disable | p50Ms | 1.293 [1.154, 1.44] | 1.301 [1.146, 1.497] | +0.6% |
| notify-302 disable | p99Ms | 3.351 [3.029, 3.709] | 2.752 [2.122, 2.991] | -17.9% |
| notify-302 enable | maxMs | 3.228 [3.046, 3.596] | 2.749 [2.376, 3.169] | -14.8% |
| notify-302 enable | p50Ms | 1.017 [0.943, 1.072] | 0.991 [0.892, 1.262] | -2.6% |
| notify-302 enable | p99Ms | 3.228 [3.046, 3.596] | 2.749 [2.376, 3.169] | -14.8% |

## Main → candidate: Concurrent connection scale

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| default | maxMs | 0.828 [0.383, 4.141] | 0.518 [0.364, 0.836] | -37.4% |
| default | p50Ms | 0.637 [0.319, 0.655] | 0.32 [0.3, 0.722] | -49.8% |
| default | p99Ms | 0.828 [0.383, 4.141] | 0.518 [0.364, 0.836] | -37.4% |

## Main → candidate: Exception modes

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| default | joinMs | 0.479 [0.413, 0.544] | 0.506 [0.389, 0.683] | +5.6% |
| default | maxMs | 0.291 [0.271, 0.31] | 0.284 [0.237, 0.315] | -2.4% |
| default | p50Ms | 0.101 [0.093, 0.142] | 0.109 [0.086, 0.136] | +7.9% |

## Main → candidate: JOIN storm

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| default | maxMs | 115.9 [94.4, 149.4] | 79.2 [57.2, 116.3] | -31.7% |
| default | p50Ms | 23.8 [18.1, 34.3] | 20.4 [11.7, 24.3] | -14.3% |
| default | p99Ms | 115.9 [94.4, 149.4] | 79.2 [57.2, 116.3] | -31.7% |
| default | stormMs | 2,110 [2,086, 2,203] | 2,201 [2,176, 2,262] | +4.3% |

## Main → candidate: Single-wave JOIN storm

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| default | stormMs | 1,957 [1,927, 1,971] | 1,918 [1,863, 1,983] | -2.0% |

## Main → candidate: Ordinary message delivery

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| default | maxMs | 0.713 [0.37, 0.813] | 0.521 [0.43, 8.865] | -26.9% |
| default | p50Ms | 0.563 [0.266, 0.589] | 0.279 [0.254, 0.546] | -50.4% |
| default | p99Ms | 0.713 [0.37, 0.813] | 0.521 [0.43, 8.865] | -26.9% |

## Dedicated NAMES off → on: alternating replacement series

Each row is the median [min, max] of five separate per-run reports. Both external warmups are excluded. Empirical screens use the same two-median-SE formula above; no matching NAMES A/A calibration is available. Values above 10% and outside the screen are retained as observations and justified as opt-in feature cost, not hidden as noise.

| Members | Metric | Off median [min, max] | On median [min, max] | Δ median | Empirical 2-unit screen | Review |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | p50Ms | 0.46 [0.416, 0.481] | 0.604 [0.584, 0.616] | +31.3% | 6.8% | Justified opt-in cost |
| 10 | p99Ms | 0.675 [0.608, 0.758] | 0.697 [0.679, 0.757] | +3.3% | 11.6% | Within 10% / empirical noise |
| 10 | maxMs | 0.675 [0.608, 0.758] | 0.697 [0.679, 0.757] | +3.3% | 11.6% | Within 10% / empirical noise |
| 10 | requestsPerSecond | 1,712.89 [1,582.32, 1,772.79] | 1,419.26 [1,402.52, 1,432.2] | -17.1% | 4.7% | Justified opt-in cost |
| 100 | p50Ms | 0.543 [0.49, 0.552] | 1.01 [0.94, 1.112] | +86.0% | 17.2% | Justified opt-in cost |
| 100 | p99Ms | 0.753 [0.745, 0.817] | 1.371 [1.311, 1.555] | +82.1% | 16.6% | Justified opt-in cost |
| 100 | maxMs | 0.753 [0.745, 0.817] | 1.371 [1.311, 1.555] | +82.1% | 16.6% | Justified opt-in cost |
| 100 | requestsPerSecond | 1,729.36 [1,689.19, 1,790.35] | 921.37 [844.66, 981.53] | -46.7% | 4.2% | Justified opt-in cost |
| 1,000 | p50Ms | 3.488 [3.352, 3.594] | 4.552 [3.94, 4.916] | +30.5% | 12.2% | Justified opt-in cost |
| 1,000 | p99Ms | 3.707 [3.645, 3.76] | 5.352 [5.087, 5.945] | +44.4% | 10.2% | Justified opt-in cost |
| 1,000 | maxMs | 3.707 [3.645, 3.76] | 5.352 [5.087, 5.945] | +44.4% | 10.2% | Justified opt-in cost |
| 1,000 | requestsPerSecond | 283.89 [277.88, 293.45] | 215.01 [199.51, 229.11] | -24.3% | 5.0% | Justified opt-in cost |

### Wire-size model and byte-normalized throughput

Verified against `UserhostInNamesLoadTest.expectedToken/nickname`, TestServer’s `test.jircd.local` prefix, `Replies.sendListed` and MessageSerializer’s trailing-parameter grammar. All workload nicknames and idents are nine ASCII bytes (`member000`…`member999`); the host is nine bytes (`127.0.0.1`). Ordinary tokens are **9 bytes**, expanded tokens **29 bytes** (`nick!ident@host`), with one extra `@` on the first member in both modes (10→30 bytes). **No workload member is voiced**, so multi-prefix does not add `@+` in this particular comparison, despite enabling its hook.

For N members, tokens plus their spaces would occupy approximately 10N versus 30N bytes in a single list. A 353 header for requester `member000`, channel `#load`, including prefix, trailing colon and CRLF, reserves **43 bytes**, leaving **469 bytes** for complete tokens. Applying the actual packing rule gives the line counts below. Each response also contains a **59-byte 366**. These are modeled serialized NAMES-response bytes (353 + 366, including CRLF), not captured network measurements. PING/PONG traffic, client requests, TCP/TLS overhead and setup JOINs are excluded.

| Members | 353 lines off → on (extra) | NAMES response bytes off → on | Off modeled MB/s median [min, max] | On modeled MB/s median [min, max] | Byte-rate Δ |
| --- | --- | --- | --- | --- | --- |
| 10 | 1 → 1 (+0) | 202 → 402 | 0.346 [0.32, 0.358] | 0.571 [0.564, 0.576] | +64.9% |
| 100 | 3 → 7 (+4) | 1,186 → 3,354 | 2.051 [2.003, 2.123] | 3.09 [2.833, 3.292] | +50.7% |
| 1,000 | 22 → 67 (+45) | 10,984 → 32,874 | 3.118 [3.052, 3.223] | 7.068 [6.559, 7.532] | +126.7% |

Modeled MB/s uses decimal 1,000,000 bytes and multiplies each run’s reported request rate by its fixed modeled response size, then summarizes five runs. The higher byte-normalized rate does not prove an optimized implementation or explain every latency component: it shows that lower requests/s accompanies approximately 2–3× more reply bytes and many more 353 lines. Coupled with the unchanged default path and sub-6-ms dedicated requests, this supports the written review justification for opt-in rendering cost. No CPU/allocation profiles were supplied for this dedicated comparison.

## NAMES inside the candidate full-suite runs

Main does not contain this class, so these have no baseline counterpart. These five on-mode samples are separate from the five dedicated on-mode samples and are not pooled with them.

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| 10 members | maxMs | — | 0.426 [0.258, 0.509] | — |
| 10 members | p50Ms | — | 0.347 [0.219, 0.415] | — |
| 10 members | p99Ms | — | 0.426 [0.258, 0.509] | — |
| 10 members | requestsPerSecond | — | 2,695.96 [2,337.13, 4,389.17] | — |
| 100 members | maxMs | — | 0.58 [0.518, 1.131] | — |
| 100 members | p50Ms | — | 0.401 [0.394, 0.675] | — |
| 100 members | p99Ms | — | 0.58 [0.518, 1.131] | — |
| 100 members | requestsPerSecond | — | 2,266.03 [1,284.89, 2,396.12] | — |
| 1000 members | maxMs | — | 4.284 [4.108, 6.019] | — |
| 1000 members | p50Ms | — | 4.198 [4.027, 4.637] | — |
| 1000 members | p99Ms | — | 4.284 [4.108, 6.019] | — |
| 1000 members | requestsPerSecond | — | 240.07 [212.8, 249.34] | — |

## Diagnostic counts

These counts are not performance regressions; percentages for zero baselines are undefined. Full JUnit outcomes prove the workloads' content/cleanup assertions passed.

| Scenario | Metric | Baseline/off median [min, max] | Candidate/on median [min, max] | Δ median |
| --- | --- | --- | --- | --- |
| default | completed | 2,000 [2,000, 2,000] | 2,000 [2,000, 2,000] | +0.0% |
| default | overflowDisconnects | 0 [0, 0] | 0 [0, 0] | — |
| default | quietMessages | 19 [18, 19] | 20 [19, 20] | +5.3% |
| diagnostic counts | framedGroupsSeen | 1,186 [1,056, 1,219] | 1,118 [938, 1,269] | -5.7% |
| diagnostic counts | linesSeen | 915,945 [885,399, 960,558] | 913,807 [886,067, 953,241] | -0.2% |

## Reproducibility and acceptance

The original full-suite summaries are unchanged. The replacement NAMES analysis parsed the 12 external JUnit reports, verified all 36 case outcomes, excluded both warmups, and checked five observations per mode/size/metric. Reported medians, min/max, percentage differences, empirical screens and modeled byte rates were recomputed from those inputs. Original data/procedure files remain outside the repository; no raw files, extracts or analysis scripts are added to the project.

To reproduce the workloads, set `RESULTS_DIR` to a directory outside the repository, such as `/private/tmp/jircd-117-performance`, and use the same baseline/candidate worktrees and documented order. Run once per warmup and measured label from the selected server worktree:

```sh
RESULTS_DIR=/private/tmp/jircd-117-performance
mkdir -p "$RESULTS_DIR"
# Set label to warm-base, warm-cand, base-1..5 or cand-1..5, in the order above.
GRADLE_OPTS=-Dorg.gradle.daemon=false env X=1 ./gradlew -q --continue cleanLoadTest loadTest \
  > "$RESULTS_DIR/$label.log" 2>&1
```

For dedicated NAMES, use the candidate worktree, exclude one external warmup per mode, and alternate measured pair order as recorded above. Set `mode=true` for off and `mode=false` for on; use a separate label/output for each run:

```sh
GRADLE_OPTS=-Dorg.gradle.daemon=false env JIRCD_NAMES_BASELINE="$mode" ./gradlew -q \
  :jircd-integration-tests:cleanLoadTest :jircd-integration-tests:loadTest \
  --tests '*UserhostInNamesLoadTest' > "$RESULTS_DIR/$label.log" 2>&1
```

After each run, preserve its generated `build/test-results/loadTest` XML outside the repository before the next clean. Record exit status, starting load and elapsed duration externally. Extract numeric `*LoadTest:` summaries from XML `system-out`, group by test/scenario/metric, exclude warmups and compute median/min/max over five runs on each side. Use the percentage-difference and noise formulas above; do not pool per-run quantiles or transfer the revised #111 harness’s tighter limits to the old harness.

No CPU/allocation metrics or profiles were supplied for the dedicated NAMES workload. Fan-out supplies CPU/allocation counters for the existing mixed-capability paths; it does not configure the new capability. No run-time machine diagnostics or additional benchmark runs were performed for this analysis.

T022 is checked for coordinator-executed workloads, replacement-series analysis and the written relative-budget review justification above. **SC-004 status: absolute budgets passed; relative >10% observations are justified by unchanged existing paths and the opted-in expanded workload.** This does not turn noisy fan-out deltas into measured proof of a regression or a ≤10% guarantee. T024 and T025 stay unchecked for remaining review/final acceptance. No Gradle or timed workload was executed during this update. No commits.
