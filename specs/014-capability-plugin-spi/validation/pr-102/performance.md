# PR 4 (#102) performance (T059–T061)

Dedicated SC-005 measurements: the fan-out class ([CapabilityFanoutLoadTest](../../../../jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityFanoutLoadTest.java)) against the pinned main baseline, and the lifecycle class ([CapabilityLifecycleLoadTest](../../../../jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityLifecycleLoadTest.java)) against its absolute budget. Both are tagged `load` and excluded from `test`.

## Decision

**Accepted by the user** (2026-10-27): the series-4 fan-out regressions past 10% do not block #102. The reasons:
- **Within noise:** every flagged A/B difference is within about 1.4 noise units of the A/A control (baseline against baseline, "A/A control" below).
- **No new success-path work:** this PR adds no per-recipient work on the successful hook path. Fault handling runs only when a hook faults.
- **The regressed phase moved between series:** on battery (series 1–3) it was the 1,000-recipient message phase. On AC (series 4) that phase is +3.2%, and the 100-recipient phases regressed instead.

**What this does not show:** five pairs cannot rule out a real difference below about 30–40% in the 100-recipient AWAY and group phases and the 1,000-recipient NAMES/WHO phase. Reducing the fan-out test's noise so it can resolve smaller differences is tracked in [#111](https://github.com/jircd/jircd-server/issues/111).

## Series 4 on AC power (decision-grade, T061)

Run on 2026-10-26 from 21:34 with the laptop **on AC power** for the whole series. The power source and load average were recorded before every run and after the last, and the power source never changed ([raw/s4-power.txt](raw/s4-power.txt)). No other build, test or irctest was running. Both revisions used the current fan-out class, sha256 `4d4a7e5d358b438925d288fc30fced6a5c9c2f9b208b6e9cfd21705d71f6f1b8`; the baseline ran the byte-identical injected copy. The lifecycle class ran inside the candidate's `./gradlew loadTest`.

The order was:
1. A warmup per revision, baseline then candidate.
2. Five pairs in alternating order: C B, B C, C B, B C, C B.
3. The full `./gradlew loadTest` on the baseline, then on the candidate.

All 14 runs passed every assertion, including the stricter order and framing checks the class gained in review rounds 1 and 2. No test server was left running. Same machine, JDK and test JVM as below.

```
21:34:54 before baseline s4-warmup | Now drawing from 'AC Power' | load { 3.51 8.03 9.46 }
21:34:59 before candidate s4-warmup | Now drawing from 'AC Power' | load { 3.63 7.98 9.44 }
21:35:03 before candidate s4-1 | Now drawing from 'AC Power' | load { 4.78 8.15 9.49 }
21:35:07 before baseline s4-1 | Now drawing from 'AC Power' | load { 4.40 8.01 9.43 }
21:35:11 before baseline s4-2 | Now drawing from 'AC Power' | load { 4.40 8.01 9.43 }
21:35:15 before candidate s4-2 | Now drawing from 'AC Power' | load { 4.44 7.96 9.40 }
21:35:19 before candidate s4-3 | Now drawing from 'AC Power' | load { 4.81 7.98 9.40 }
21:35:23 before baseline s4-3 | Now drawing from 'AC Power' | load { 5.87 8.14 9.45 }
21:35:27 before baseline s4-4 | Now drawing from 'AC Power' | load { 5.48 8.03 9.40 }
21:35:30 before candidate s4-4 | Now drawing from 'AC Power' | load { 5.48 8.03 9.40 }
21:35:34 before candidate s4-5 | Now drawing from 'AC Power' | load { 5.52 7.99 9.38 }
21:35:38 before baseline s4-5 | Now drawing from 'AC Power' | load { 5.48 7.94 9.36 }
21:35:42 before full baseline | Now drawing from 'AC Power' | load { 6.72 8.16 9.42 }
21:36:34 before full candidate | Now drawing from 'AC Power' | load { 7.49 8.11 9.33 }
21:37:16 after last | Now drawing from 'AC Power' | load { 5.78 7.64 9.10 }
```

### Medians over the five measured pairs

Baseline → candidate, with the change. Changes beyond ±10% are in bold (a negative latency or CPU change and a positive throughput change are improvements).

| Recipients | Phase | Throughput/s | p99 ms | max ms | CPU ms | Alloc MB |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | message | 23,893 → 22,824 (-4.5%) | 53.231 → 52.520 (-1.3%) | 53.372 → 52.772 (-1.1%) | 693.8 → 657.9 (-5.2%) | 47.0 → 46.9 (-0.2%) |
| 10 | away | 37,774 → 35,778 (-5.3%) | 4.776 → 5.143 (+7.7%) | 4.796 → 5.162 (+7.6%) | **47.2 → 52.7 (+11.7%)** | 1.2 → 1.2 (+0.0%) |
| 10 | member | 2,776 → 2,828 (+1.9%) | 0.553 → 0.569 (+2.9%) | 0.553 → 0.569 (+2.9%) | 38.8 → 35.5 (-8.5%) | 1.1 → 1.1 (+0.0%) |
| 10 | group | 21,993 → 22,463 (+2.1%) | 0.272 → 0.266 (-2.2%) | 1.040 → 0.993 (-4.5%) | 71.5 → 72.1 (+0.8%) | 3.4 → 3.4 (+0.0%) |
| 100 | message | 183,132 → 186,889 (+2.1%) | 104.899 → 101.526 (-3.2%) | 105.623 → 102.455 (-3.0%) | 1,327.1 → 1,290.3 (-2.8%) | 139.1 → 138.4 (-0.5%) |
| 100 | away | **519,242 → 412,723 (-20.5%)** | **4.368 → 5.562 (+27.3%)** | **4.375 → 5.578 (+27.5%)** | 61.5 → 65.6 (+6.7%) | 3.6 → 3.7 (+2.8%) |
| 100 | member | 1,835 → 1,826 (-0.5%) | **1.205 → 1.082 (-10.2%)** | **1.205 → 1.082 (-10.2%)** | 57.3 → 56.9 (-0.7%) | 7.8 → 7.8 (+0.0%) |
| 100 | group | **48,011 → 40,348 (-16.0%)** | **12.707 → 17.390 (+36.9%)** | 20.008 → 18.424 (-7.9%) | **484.1 → 571.2 (+18.0%)** | 31.2 → 31.6 (+1.3%) |
| 1,000 | message | 469,434 → 484,445 (+3.2%) | 407.671 → 397.060 (-2.6%) | 411.006 → 399.571 (-2.8%) | 5,173.2 → 4,868.9 (-5.9%) | 1,247.3 → 1,244.4 (-0.2%) |
| 1,000 | away | 737,257 → 688,432 (-6.6%) | 31.906 → 34.302 (+7.5%) | 32.097 → 34.503 (+7.5%) | 422.4 → 460.6 (+9.0%) | 29.8 → 30.3 (+1.7%) |
| 1,000 | member | 1,574 → 1,571 (-0.2%) | **1.652 → 2.080 (+25.9%)** | **1.652 → 2.080 (+25.9%)** | 53.7 → 53.3 (-0.7%) | 36.5 → 35.9 (-1.6%) |
| 1,000 | group | 125,634 → 119,460 (-4.9%) | 32.191 → 30.563 (-5.1%) | **42.571 → 37.434 (-12.1%)** | 2,049.9 → 2,115.0 (+3.2%) | 300.4 → 301.9 (+0.5%) |

### Findings (as reported to the coordinator; see "Decision")

1. **Throughput regressions past 10%:**
   - 100-recipient AWAY: −20.5%.
   - 100-recipient groups: −16.0%.
2. **Latency and CPU increases past 10%:**
   - 100-recipient AWAY p99 +27.3% (max +27.5%).
   - 100-recipient group p99 +36.9% and CPU +18.0%.
   - 1,000-recipient member (NAMES/WHO) p99 +25.9%.
   - 10-recipient AWAY CPU +11.7%.
3. **The earlier battery-run finding does not reproduce on AC.** The 1,000-recipient message phase is +3.2% throughput, −2.6% p99 and −5.9% CPU.

No cause is claimed. The runs below show the spread behind these medians. For example, one candidate AWAY run (s4-5) measured 295,960/s against 412,723–569,363/s in its other four, and the 100-recipient group phase swings between about 30k/s and 53k/s on both revisions.

#### 100-recipient AWAY, run by run

| Run | Baseline throughput/s | Candidate throughput/s | Baseline p99 ms | Candidate p99 ms | Baseline CPU ms | Candidate CPU ms |
| --- | --- | --- | --- | --- | --- | --- |
| warmup | 501,391 | 540,283 | 4.485 | 4.188 | 54.8 | 55.2 |
| s4-1 (C then B) | 519,242 | 496,742 | 4.368 | 4.470 | 61.5 | 61.1 |
| s4-2 (B then C) | 464,242 | 412,723 | 4.942 | 5.562 | 64.8 | 65.6 |
| s4-3 (C then B) | 348,742 | 341,270 | 6.707 | 6.955 | 87.2 | 83.3 |
| s4-4 (B then C) | 545,152 | 569,363 | 4.139 | 3.943 | 56.0 | 53.8 |
| s4-5 (C then B) | 550,646 | 295,960 | 4.135 | 7.953 | 55.1 | 105.3 |

#### 100-recipient groups, run by run

| Run | Baseline throughput/s | Candidate throughput/s | Baseline p99 ms | Candidate p99 ms | Baseline CPU ms | Candidate CPU ms |
| --- | --- | --- | --- | --- | --- | --- |
| warmup | 38,516 | 49,049 | 13.943 | 13.475 | 594.4 | 481.4 |
| s4-1 (C then B) | 36,759 | 39,747 | 18.640 | 17.390 | 714.6 | 571.2 |
| s4-2 (B then C) | 38,884 | 53,301 | 23.922 | 0.221 | 665.7 | 363.1 |
| s4-3 (C then B) | 52,827 | 29,622 | 0.385 | 49.310 | 344.9 | 848.6 |
| s4-4 (B then C) | 48,420 | 51,984 | 3.205 | 0.259 | 355.1 | 349.9 |
| s4-5 (C then B) | 48,011 | 40,348 | 12.707 | 18.214 | 484.1 | 577.1 |

#### 1,000-recipient member listings and messages

| Run | Baseline throughput/s | Candidate throughput/s | Baseline p99 ms | Candidate p99 ms | Baseline CPU ms | Candidate CPU ms |
| --- | --- | --- | --- | --- | --- | --- |
| warmup | 1,694 | 1,722 | 1.572 | 1.040 | 52.1 | 51.9 |
| s4-1 (C then B) | 1,574 | 1,556 | 1.577 | 2.433 | 53.7 | 57.8 |
| s4-2 (B then C) | 1,436 | 1,878 | 2.899 | 1.925 | 56.1 | 53.3 |
| s4-3 (C then B) | 1,748 | 1,800 | 1.652 | 2.080 | 45.2 | 45.7 |
| s4-4 (B then C) | 1,524 | 1,522 | 2.541 | 2.197 | 73.2 | 43.4 |
| s4-5 (C then B) | 1,653 | 1,571 | 1.157 | 1.661 | 51.4 | 57.3 |

| Run | Baseline throughput/s | Candidate throughput/s | Baseline p99 ms | Candidate p99 ms | Baseline CPU ms | Candidate CPU ms |
| --- | --- | --- | --- | --- | --- | --- |
| warmup | 418,937 | 509,893 | 453.051 | 376.507 | 5,170.3 | 4,750.8 |
| s4-1 (C then B) | 410,913 | 484,445 | 470.794 | 397.060 | 6,162.6 | 4,868.9 |
| s4-2 (B then C) | 491,342 | 514,371 | 388.075 | 369.497 | 4,976.8 | 4,866.4 |
| s4-3 (C then B) | 466,486 | 410,889 | 410.165 | 470.978 | 5,238.3 | 6,171.9 |
| s4-4 (B then C) | 513,664 | 494,938 | 373.948 | 385.310 | 4,858.2 | 4,741.3 |
| s4-5 (C then B) | 469,434 | 431,885 | 407.671 | 447.863 | 5,173.2 | 5,852.8 |

### `./gradlew loadTest` on AC

Both revisions passed every load test: 13 on the baseline (with the injected fan-out class) and 14 on the candidate. Raw output: [raw/loadtest-full-s4-baseline.txt](raw/loadtest-full-s4-baseline.txt), [raw/loadtest-full-s4-candidate.txt](raw/loadtest-full-s4-candidate.txt).

| Load test | Baseline | Candidate |
| --- | --- | --- |
| Story1DeliveryLatency (50 messages) | p50 0.510, p99 5.988 ms | p50 0.534, p99 0.853 ms |
| ConcurrentConnectionScale | p50 0.721, p99 1.187 ms | p50 0.607, p99 0.887 ms |
| ExceptionModes | join 0.411 ms, p50 0.139, max 1.195 ms | join 0.457 ms, p50 0.133, max 0.294 ms |
| JoinStorm (2,000 clients) | storm 2,120 ms; quiet p50 15.2, p99 96.5 ms | storm 2,120 ms; quiet p50 32.7, p99 106.7 ms |
| SingleWaveJoinStorm | 1,971 ms, 0 overflow disconnects | 1,929 ms, 0 overflow disconnects |
| CapabilityPublicationTiming (#97), no-notify / notify-302 disable p99 | 0.670 / 3.127 ms | 0.414 / 1.776 ms |
| KeepAlive, RateLimit | pass | pass |

Delivery stays far under 1 s, and the JOIN-storm budgets hold with a wide margin (storm under 15 s, quiet p99 under 500 ms and max under 1 s). JoinStorm's quiet p50 doubled (15.2 → 32.7 ms) in this single pair; it is one sample per revision, and p99 and max moved +10.6%.

**Lifecycle on AC** (candidate, inside `loadTest`; exact sample counts enforced by the review-round-1 class):

| Stage | p99 / max ms |
| --- | --- |
| Mutex wait (80 publications) | 0.002 / 0.002 |
| Swap + prune (held) | 1.617 / 1.617 |
| **Wait + swap + prune (budget ≤ 100)** | **1.617 / 1.617** |
| `DEL`/`NEW` enqueue (500 eligible) | 1.803 / 1.803 |
| Whole `EXTENSION` change (60) | 2.534 / 2.534 |
| Whole `REHASH` change (20) | 16.565 / 16.565 |
| `DEL` receipt after change start (20,000) | 22.220 / 26.580 |

Every framed group was whole (1,195 seen), and every session was coherent after each change.

## A/A control: baseline against baseline (AC)

Run on 2026-10-26 from 21:54, to see how far two identical builds drift apart. Conditions:
- **Power:** on AC for every run, recorded before each run and after the last ([raw/aa-power.txt](raw/aa-power.txt)). Nothing else heavy was running.
- **Build:** the baseline worktree (main `3b7d66d`) only, with the same injected fan-out class (sha256 `4d4a7e5d…f1b8`). Side A plays the baseline's role and side B the candidate's.
- **Order:** the same pattern as series 4, a warmup per side, then the pairs B A, A B, B A, A B, B A.
- **Outcome:** all 12 runs passed every assertion. No test server was left running. Raw per-run summaries: `raw/fanout-baseline-aa*.txt`.

```
21:54:27 before aaA-warmup | Now drawing from 'AC Power' | load { 2.02 4.34 7.91 }
21:54:31 before aaB-warmup | Now drawing from 'AC Power' | load { 2.02 4.30 7.88 }
21:54:36 before aaB-1 | Now drawing from 'AC Power' | load { 3.54 4.58 7.95 }
21:54:40 before aaA-1 | Now drawing from 'AC Power' | load { 5.10 4.89 8.04 }
21:54:43 before aaA-2 | Now drawing from 'AC Power' | load { 6.05 5.09 8.09 }
21:54:47 before aaB-2 | Now drawing from 'AC Power' | load { 6.05 5.09 8.09 }
21:54:51 before aaB-3 | Now drawing from 'AC Power' | load { 6.05 5.10 8.08 }
21:54:55 before aaA-3 | Now drawing from 'AC Power' | load { 6.04 5.12 8.07 }
21:54:59 before aaA-4 | Now drawing from 'AC Power' | load { 7.32 5.40 8.15 }
21:55:03 before aaB-4 | Now drawing from 'AC Power' | load { 6.98 5.36 8.12 }
21:55:07 before aaB-5 | Now drawing from 'AC Power' | load { 6.98 5.36 8.12 }
21:55:11 before aaA-5 | Now drawing from 'AC Power' | load { 7.22 5.43 8.13 }
21:55:15 after last | Now drawing from 'AC Power' | load { 7.12 5.44 8.12 }
```

**Reading the table:**
- **A/A median diff:** B against A over the five pairs. Both sides are the same code, so anything here is noise.
- **A/A runs min–max (CV):** all ten A/A runs together, with the coefficient of variation (sample standard deviation over mean).
- **A/A noise unit:** the expected standard deviation of a difference between two medians of five runs, about 0.79 × CV (normal approximation, 1.2533 · CV · √(2/5)).
- **A/B in noise units:** the series-4 AC A/B median difference divided by that unit. Around ±1 is ordinary noise; beyond ±2 (in bold) is unlikely to be noise alone.
- **Candidate runs outside the A/A range:** how many of the five series-4 candidate runs fall outside the ten A/A runs' min–max, on either side.

| Recipients | Phase | Metric | A/A median diff | A/A runs min–max (CV) | A/A noise unit | AC A/B median diff | A/B in noise units | Candidate runs outside the A/A range |
|---|---|---|---|---|---|---|---|---|
| 10 | message | throughput | +0.6% | 22,624–23,980 (2.0%) | ±1.6% | -4.5% | **-2.8** | 3 of 5 |
| 10 | message | p99 | +0.0% | 51.148–54.401 (2.2%) | ±1.7% | -1.3% | -0.8 | 1 of 5 |
| 10 | message | CPU | -0.2% | 634.5–807.1 (8.0%) | ±6.4% | -5.2% | -0.8 | 1 of 5 |
| 10 | away | throughput | -2.8% | 30,889–36,612 (4.3%) | ±3.4% | -5.3% | -1.6 | 1 of 5 |
| 10 | away | p99 | +2.7% | 5.023–5.418 (2.4%) | ±1.9% | +7.7% | **+4.1** | 1 of 5 |
| 10 | away | CPU | +10.9% | 49.5–61.9 (8.6%) | ±6.8% | +11.7% | +1.7 | 2 of 5 |
| 10 | member | throughput | +0.8% | 2,570–3,143 (5.6%) | ±4.4% | +1.9% | +0.4 | 0 of 5 |
| 10 | member | p99 | +3.4% | 0.561–0.705 (7.1%) | ±5.6% | +2.9% | +0.5 | 1 of 5 |
| 10 | member | CPU | -2.1% | 28.7–44.1 (11.8%) | ±9.3% | -8.5% | -0.9 | 0 of 5 |
| 10 | group | throughput | -5.1% | 19,138–23,937 (6.9%) | ±5.4% | +2.1% | +0.4 | 1 of 5 |
| 10 | group | p99 | +10.7% | 0.237–0.318 (9.0%) | ±7.1% | -2.2% | -0.3 | 0 of 5 |
| 10 | group | CPU | +2.9% | 66.3–77.6 (4.7%) | ±3.8% | +0.8% | +0.2 | 1 of 5 |
| 100 | message | throughput | -10.4% | 151,498–197,727 (10.2%) | ±8.1% | +2.1% | +0.3 | 1 of 5 |
| 100 | message | p99 | +11.2% | 94.800–124.840 (10.6%) | ±8.4% | -3.2% | -0.4 | 1 of 5 |
| 100 | message | CPU | +1.6% | 1,198.7–1,628.9 (11.3%) | ±8.9% | -2.8% | -0.3 | 1 of 5 |
| 100 | away | throughput | +11.1% | 282,568–571,864 (23.7%) | ±18.7% | -20.5% | -1.1 | 0 of 5 |
| 100 | away | p99 | -9.9% | 3.910–8.320 (25.1%) | ±19.8% | +27.3% | +1.4 | 0 of 5 |
| 100 | away | CPU | -7.9% | 45.4–113.5 (27.3%) | ±21.6% | +6.7% | +0.3 | 0 of 5 |
| 100 | member | throughput | +3.8% | 1,559–1,891 (7.4%) | ±5.9% | -0.5% | -0.1 | 2 of 5 |
| 100 | member | p99 | +0.1% | 1.007–1.387 (10.2%) | ±8.1% | -10.2% | -1.3 | 0 of 5 |
| 100 | member | CPU | -0.6% | 57.0–75.9 (9.9%) | ±7.8% | -0.7% | -0.1 | 3 of 5 |
| 100 | group | throughput | +1.7% | 36,320–64,598 (18.0%) | ±14.2% | -16.0% | -1.1 | 1 of 5 |
| 100 | group | p99 | +10.5% | 0.270–28.813 (146.6%) | ±115.8% | +36.9% | +0.3 | 3 of 5 |
| 100 | group | CPU | +4.3% | 343.3–685.8 (27.6%) | ±21.8% | +18.0% | +0.8 | 1 of 5 |
| 1,000 | message | throughput | -0.3% | 406,453–593,435 (12.5%) | ±9.9% | +3.2% | +0.3 | 0 of 5 |
| 1,000 | message | p99 | +1.1% | 320.003–464.428 (11.7%) | ±9.2% | -2.6% | -0.3 | 1 of 5 |
| 1,000 | message | CPU | +7.5% | 4,021.7–6,011.3 (12.9%) | ±10.2% | -5.9% | -0.6 | 1 of 5 |
| 1,000 | away | throughput | -3.4% | 584,982–724,250 (6.8%) | ±5.4% | -6.6% | -1.2 | 2 of 5 |
| 1,000 | away | p99 | +3.6% | 32.402–40.621 (7.4%) | ±5.8% | +7.5% | +1.3 | 2 of 5 |
| 1,000 | away | CPU | +3.7% | 412.6–517.9 (7.2%) | ±5.7% | +9.0% | +1.6 | 2 of 5 |
| 1,000 | member | throughput | -1.2% | 773–1,748 (19.5%) | ±15.4% | -0.2% | -0.0 | 2 of 5 |
| 1,000 | member | p99 | -12.1% | 1.185–2.914 (25.3%) | ±20.0% | +25.9% | +1.3 | 0 of 5 |
| 1,000 | member | CPU | +7.7% | 49.1–122.4 (31.3%) | ±24.7% | -0.7% | -0.0 | 2 of 5 |
| 1,000 | group | throughput | -2.6% | 103,308–125,390 (5.8%) | ±4.6% | -4.9% | -1.1 | 3 of 5 |
| 1,000 | group | p99 | -7.9% | 27.233–39.315 (12.0%) | ±9.5% | -5.1% | -0.5 | 0 of 5 |
| 1,000 | group | CPU | +3.4% | 1,883.5–2,497.8 (8.5%) | ±6.7% | +3.2% | +0.5 | 0 of 5 |

### Conclusion

**The three regressions that drove the decision fall inside the A/A noise:**

| Phase and metric | AC A/B | A/A (identical builds) | Run-to-run CV | A/B in noise units | Candidate runs outside the A/A range |
| --- | --- | --- | --- | --- | --- |
| 100-recipient AWAY throughput | −20.5% | +11.1% | 23.7% | −1.1 | 0 of 5 |
| 100-recipient AWAY p99 | +27.3% | −9.9% | 25.1% | +1.4 | 0 of 5 |
| 100-recipient group throughput | −16.0% | +1.7% | 18.0% | −1.1 | 1 of 5 (s4-3, 29,622/s, low) |
| 100-recipient group p99 | +36.9% | +10.5% | 146.6% (bimodal) | +0.3 | 3 of 5 (two below the range, one above) |
| 100-recipient group CPU | +18.0% | +4.3% | 27.6% | +0.8 | 1 of 5 (s4-3, high) |
| 1,000-recipient NAMES/WHO p99 | +25.9% | −12.1% | 25.3% | +1.3 | 0 of 5 |

Two identical builds differ by 10–12% in median in four places: 100-recipient AWAY throughput, 100-recipient message throughput and p99, and 1,000-recipient NAMES/WHO p99. Every A/B change past 10% from series 4 is within about 1.4 noise units. For four of the six metrics above, no candidate run falls outside what the baseline produced against itself.

**Limits of this result:**
- **It does not prove these phases have no regression.** With five pairs and CVs of 18–25%, a real difference smaller than roughly 30–40% (2 noise units) cannot be told from noise in them.
- **One candidate run, s4-3, sits outside the A/A range** in the 100-recipient group phase on throughput, p99 and CPU at once.
- **Two low-noise phases show small differences beyond 2 noise units.** Both are under the 10% thresholds:
  - 10-recipient message throughput: −4.5% (−2.8 units; A/A +0.6%, CV 2.0%).
  - 10-recipient AWAY p99: +7.7% (+4.1 units; A/A +2.7%, CV 2.4%).

  Two out of 36 comparisons beyond 2 units is about what chance alone gives (about 5% of 36, or 1.8). They may still be real small costs, but this series cannot tell them from noise.

## On-battery series 1–3 (not decision-grade)

> **Not decision-grade.** All three fan-out series (10:41, 10:43 and 15:38 on 2026-10-26), both full `./gradlew loadTest` runs and the lifecycle runs were measured with the laptop **on battery** (on battery since 06:46 that day; Low Power Mode off). The #101 and #108 records were measured on AC. These numbers must not be used for the throughput-regression decision or compared with those records. The raw data below is kept as recorded. They were superseded by series 4 above, measured on AC.
>
> **The fan-out class changed after these runs** (review rounds 1 and 2). It now checks the order of every message, AWAY and group member, and every group's framing. It waits on latches, drains each reader to a PING barrier, and requires every client to reach end of stream after `QUIT` before one shared deadline. The copy the init script injects into the baseline worktree was replaced with the new version. Both files now have sha256 `4d4a7e5d358b438925d288fc30fced6a5c9c2f9b208b6e9cfd21705d71f6f1b8`; the baseline compiles it unchanged. The hash `a3c0e802…` quoted below belongs to the class used for series 1–3.

## Setup

| Item | Value |
| --- | --- |
| Baseline | main `3b7d66df086fe5bff7cbe9804905641b05f4b9ad` (#108), detached worktree `.worktrees/014-baseline`, no file edited |
| Candidate | `014-capability-faults` working tree (uncommitted #102 diff on the same commit) |
| Machine | Apple M4 Pro, 14 cores, 24 GiB, macOS 27.0.1 (Darwin 27.0.0, arm64) |
| JDK / JVM | Temurin 25.0.4+7 LTS, OpenJDK 64-Bit Server VM; test JVM `-Xmx2g -ea` with the build's JaCoCo agent, identical on both (checked from `jdk.JVMInformation` in the recordings) |
| Gradle | 9.8.1 |
| Other load | No other build or test ran. Desktop apps only (WindowServer, IDE, browser, VPN); load average 4–5 before each series, rising to 9–12 during it (the runs themselves). Timestamps and load averages per run are in the scratch log |

**Running the new class on the baseline without editing it.** The baseline has no `CapabilityFanoutLoadTest`. A Gradle init script (kept outside the repository) adds a scratch directory holding a byte-identical copy of the class (sha256 `a3c0e802ace880154b1fd4ef46e77216dc1057a1287fc87e6b1ff3b5f84b8d3c`, same as the candidate's file) to the baseline's integration test source set, and adds `-XX:StartFlightRecording=…,settings=profile` to `loadTest`. The class uses only APIs present on main. The candidate ran with the same init script (JFR only). Command per run:

```
./gradlew -I load.init.gradle [-PextraLoadSrc=<copy dir>] -PjfrFile=<run>.jfr \
  :jircd-integration-tests:loadTest --tests 'net.jircd.integration.CapabilityFanoutLoadTest' --rerun
```

## Fan-out workload (T059)

One sender and 10, 100 or 1,000 recipients in one channel, a fresh server per size. Recipients take four negotiation mixes round-robin: none; `message-tags server-time`; CAP 302 with all six capabilities; `multi-prefix away-notify`. Phases, in order: 200 tagged `PRIVMSG`s of about 100 bytes sent back to back; 50 `AWAY` changes (received by the away-notify mixes); 10 `NAMES` plus 10 `WHO` of the channel (multi-prefix requester); 20 three-member groups enqueued to every recipient (framed for the batch mix, plain otherwise). Rate limits are raised (bucket and refill 1,000,000) and SendQ is 2 MiB, so nothing is dropped. Every item must arrive, framed groups must open and close exactly 20 times with every member tagged, and every session must be released after the clients close. Latency is send to receipt per recipient. Throughput is items received per second of phase wall time. CPU is the process's CPU time over the phase, and allocation is `getTotalThreadAllocatedBytes` over the phase (both include the test's own client threads, the same on both revisions).

## Runs

One warmup run per revision, then three series of five runs each:
- **Series 1** (10:41): baseline then candidate in each pair, in that fixed order.
- **Series 2** (10:43): the order alternated (C B, B C, C B, B C, C B) to control for run order and heat.
- **Series 3** (15:38, after the machine was suspended and resumed): a new warmup per revision, then the alternated order again (C B, B C, C B, B C, C B).

All 34 runs passed. Raw per-run summaries are in [raw/](raw/) (`fanout-<revision>-<run>.txt`); the JUnit XML and the JFR recordings of each run are kept with the coordinator's scratch outputs.

### Medians over series 1 and 2 (ten measured runs per revision)

Baseline → candidate, with the change. Changes beyond ±10% are in bold (a negative latency or CPU change and a positive throughput change are improvements).

| Recipients | Phase | Throughput/s | p99 ms | max ms | CPU ms | Alloc MB |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | message | 31,894 → 32,430 (+1.7%) | 52.924 → 53.320 (+0.7%) | 53.187 → 53.607 (+0.8%) | 539.6 → 537.3 (-0.4%) | 19.9 → 20.0 (+0.3%) |
| 10 | away | 29,200 → 29,286 (+0.3%) | 5.260 → 5.367 (+2.1%) | 5.270 → 5.379 (+2.1%) | 48.1 → 48.3 (+0.5%) | 1.2 → 1.2 (+0.0%) |
| 10 | member | 2,684 → 2,826 (+5.3%) | **0.659 → 0.591 (-10.3%)** | **0.659 → 0.591 (-10.3%)** | 39.6 → 37.8 (-4.7%) | 1.1 → 1.1 (+0.0%) |
| 10 | group | 21,808 → 21,198 (-2.8%) | **0.284 → 0.242 (-14.8%)** | 1.184 → 1.164 (-1.6%) | 68.9 → 67.6 (-1.9%) | 3.3 → 3.3 (+0.0%) |
| 100 | message | 190,832 → 189,238 (-0.8%) | 94.388 → 97.183 (+3.0%) | 95.385 → 98.108 (+2.9%) | 1,112.7 → 1,178.8 (+5.9%) | 139.4 → 140.2 (+0.6%) |
| 100 | away | 367,100 → 364,382 (-0.7%) | 5.069 → 5.336 (+5.3%) | 5.102 → 5.429 (+6.4%) | 62.0 → 66.1 (+6.7%) | 3.6 → 3.7 (+2.8%) |
| 100 | member | 1,786 → 1,794 (+0.5%) | 1.098 → 1.141 (+4.0%) | 1.098 → 1.141 (+4.0%) | 61.9 → 61.4 (-0.8%) | 7.8 → 7.8 (+0.0%) |
| 100 | group | 49,778 → 49,134 (-1.3%) | 1.327 → 1.372 (+3.4%) | **2.830 → 1.762 (-37.8%)** | 359.0 → 361.7 (+0.8%) | 28.0 → 28.0 (+0.0%) |
| 1,000 | message | 495,469 → 449,341 (-9.3%) | **378.440 → 420.168 (+11.0%)** | **381.059 → 422.641 (+10.9%)** | **4,454.2 → 5,238.2 (+17.6%)** | 1,258.9 → 1,258.9 (+0.0%) |
| 1,000 | away | 654,919 → 630,980 (-3.7%) | 33.897 → 32.605 (-3.8%) | 34.215 → 32.820 (-4.1%) | 389.0 → 408.5 (+5.0%) | 30.2 → 30.2 (-0.2%) |
| 1,000 | member | **1,413 → 1,603 (+13.4%)** | 1.753 → 1.828 (+4.2%) | 1.753 → 1.828 (+4.2%) | **61.0 → 54.3 (-10.9%)** | 36.8 → 34.6 (-5.7%) |
| 1,000 | group | 95,985 → 96,148 (+0.2%) | 30.156 → 27.370 (-9.2%) | 34.582 → 33.645 (-2.7%) | 2,099.1 → 2,115.5 (+0.8%) | 293.4 → 297.8 (+1.5%) |

Per series: [series 1](#series-1-fixed-order) and [series 2](#series-2-alternated-order) below.

### Medians over all fifteen measured runs per revision (series 1–3)

| Recipients | Phase | Throughput/s | p99 ms | max ms | CPU ms | Alloc MB |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | message | 32,081 → 32,473 (+1.2%) | 52.611 → 53.884 (+2.4%) | 52.854 → 54.261 (+2.7%) | 560.8 → 540.3 (-3.7%) | 19.9 → 20.0 (+0.5%) |
| 10 | away | 29,180 → 29,368 (+0.6%) | 5.248 → 5.340 (+1.8%) | 5.267 → 5.347 (+1.5%) | 48.3 → 47.9 (-0.8%) | 1.2 → 1.2 (+0.0%) |
| 10 | member | 2,668 → 2,815 (+5.5%) | **0.663 → 0.584 (-11.9%)** | **0.663 → 0.584 (-11.9%)** | 40.0 → 38.2 (-4.5%) | 1.1 → 1.1 (+0.0%) |
| 10 | group | 21,778 → 21,219 (-2.6%) | **0.277 → 0.236 (-14.8%)** | 1.160 → 1.141 (-1.6%) | 68.1 → 67.4 (-1.0%) | 3.3 → 3.3 (+0.0%) |
| 100 | message | 185,673 → 178,590 (-3.8%) | 99.821 → 104.586 (+4.8%) | 100.253 → 105.339 (+5.1%) | **1,194.5 → 1,331.2 (+11.4%)** | 139.9 → 140.4 (+0.4%) |
| 100 | away | 367,220 → 361,747 (-1.5%) | **5.086 → 5.618 (+10.5%)** | **5.109 → 5.705 (+11.7%)** | 64.7 → 70.2 (+8.5%) | 3.6 → 3.7 (+2.8%) |
| 100 | member | 1,774 → 1,700 (-4.2%) | 1.150 → 1.214 (+5.6%) | 1.150 → 1.214 (+5.6%) | 62.3 → 64.5 (+3.5%) | 7.8 → 7.8 (+0.0%) |
| 100 | group | 50,042 → 48,777 (-2.5%) | **1.466 → 0.921 (-37.2%)** | **3.441 → 2.051 (-40.4%)** | 357.6 → 366.9 (+2.6%) | 28.1 → 28.1 (+0.0%) |
| 1,000 | message | 491,037 → 449,904 (-8.4%) | 385.141 → 419.617 (+9.0%) | 387.641 → 422.464 (+9.0%) | **4,528.2 → 5,201.0 (+14.9%)** | 1,258.1 → 1,259.3 (+0.1%) |
| 1,000 | away | 655,277 → 645,534 (-1.5%) | 33.165 → 31.941 (-3.7%) | 33.346 → 32.161 (-3.6%) | 397.9 → 400.2 (+0.6%) | 30.1 → 30.2 (+0.3%) |
| 1,000 | member | 1,473 → 1,490 (+1.2%) | 1.728 → 1.580 (-8.6%) | 1.728 → 1.580 (-8.6%) | 60.8 → 56.8 (-6.6%) | 36.6 → 36.3 (-0.8%) |
| 1,000 | group | 96,508 → 99,139 (+2.7%) | **31.149 → 27.622 (-11.3%)** | 35.190 → 34.200 (-2.8%) | 2,104.2 → 2,116.1 (+0.6%) | 288.8 → 290.2 (+0.5%) |

### Series 3 alone (after the resume)

| Recipients | Phase | Throughput/s | p99 ms | max ms | CPU ms | Alloc MB |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | message | 32,292 → 33,033 (+2.3%) | 52.109 → 55.463 (+6.4%) | 52.444 → 55.734 (+6.3%) | 565.8 → 558.8 (-1.2%) | 19.8 → 19.9 (+0.5%) |
| 10 | away | 29,076 → 29,523 (+1.5%) | 5.196 → 5.302 (+2.0%) | 5.196 → 5.314 (+2.3%) | 50.2 → 47.9 (-4.6%) | 1.2 → 1.2 (+0.0%) |
| 10 | member | 2,554 → 2,702 (+5.8%) | **0.679 → 0.584 (-14.0%)** | **0.679 → 0.584 (-14.0%)** | 40.9 → 40.9 (+0.0%) | 1.1 → 1.1 (+0.0%) |
| 10 | group | 21,555 → 22,685 (+5.2%) | 0.236 → 0.236 (+0.0%) | 1.120 → 1.139 (+1.7%) | 65.7 → 65.6 (-0.2%) | 3.3 → 3.3 (+0.0%) |
| 100 | message | 159,442 → 160,283 (+0.5%) | 112.820 → 113.039 (+0.2%) | 113.594 → 114.229 (+0.6%) | 1,442.5 → 1,412.1 (-2.1%) | 141.0 → 142.0 (+0.7%) |
| 100 | away | **370,460 → 209,851 (-43.4%)** | **5.834 → 6.476 (+11.0%)** | **5.875 → 6.504 (+10.7%)** | **77.8 → 99.6 (+28.0%)** | 3.7 → 3.8 (+2.7%) |
| 100 | member | 1,774 → 1,678 (-5.4%) | **1.277 → 1.433 (+12.2%)** | **1.277 → 1.433 (+12.2%)** | 67.8 → 69.9 (+3.1%) | 7.8 → 7.8 (+0.0%) |
| 100 | group | **50,042 → 44,026 (-12.0%)** | **4.078 → 0.464 (-88.6%)** | **5.297 → 2.051 (-61.3%)** | 357.6 → 379.1 (+6.0%) | 28.1 → 28.3 (+0.7%) |
| 1,000 | message | 486,806 → 471,824 (-3.1%) | 390.911 → 401.151 (+2.6%) | 394.233 → 403.089 (+2.2%) | 4,982.9 → 5,099.4 (+2.3%) | 1,257.0 → 1,269.4 (+1.0%) |
| 1,000 | away | 655,277 → 676,926 (+3.3%) | 31.388 → 29.367 (-6.4%) | 31.652 → 29.618 (-6.4%) | **408.0 → 341.2 (-16.4%)** | 30.0 → 30.2 (+0.7%) |
| 1,000 | member | 1,543 → 1,456 (-5.6%) | **1.648 → 1.288 (-21.8%)** | **1.648 → 1.288 (-21.8%)** | 59.0 → 64.4 (+9.2%) | 36.5 → 36.5 (+0.0%) |
| 1,000 | group | 103,871 → 103,766 (-0.1%) | 32.335 → 29.791 (-7.9%) | 36.912 → 34.703 (-6.0%) | 2,237.9 → 2,156.7 (-3.6%) | 286.1 → 286.7 (+0.2%) |

Several phases are bimodal on **both** revisions. A run lands either near one throughput or near about half of it:
- **100-recipient AWAY:** about 2,500 items in about 7 ms, so a run gives either ~370k/s or ~190k/s.
- **100-recipient groups:** p50 either ~0.1 ms or ~2–15 ms.
- **1,000-recipient message:** ~490k/s or below 450k/s.

A five-run median moves a lot depending on how many runs land low. Runs in the low mode, of 15 per revision:

| Phase | Low-mode threshold | Baseline | Candidate |
| --- | --- | --- | --- |
| 100 AWAY | throughput < 280,000/s | 3 | 6 |
| 100 group | throughput < 45,000/s | 4 | 7 |
| 1,000 message | throughput < 450,000/s | 3 | 8 |

The candidate lands in the low mode more often in all three phases. These counts are reported as measured; no cause is claimed.

### 1,000-recipient message phase, run by run

| Run | Baseline throughput/s | Candidate throughput/s | Baseline p99 ms | Candidate p99 ms | Baseline CPU ms | Candidate CPU ms |
| --- | --- | --- | --- | --- | --- | --- |
| warmup | 518,398 | 570,488 | 363.7 | 329.5 | 4,528 | 4,012 |
| r1 (B then C) | 499,901 | 456,790 | 372.9 | 415.7 | 4,488 | 5,275 |
| r2 (B then C) | 491,037 | 522,283 | 384.0 | 366.5 | 4,028 | 4,598 |
| r3 (B then C) | 511,348 | 429,985 | 370.5 | 445.2 | 4,528 | 5,315 |
| r4 (B then C) | 453,831 | 410,811 | 418.9 | 460.3 | 4,985 | 5,437 |
| r5 (B then C) | 535,783 | 449,904 | 341.2 | 418.2 | 4,385 | 5,048 |
| a1 (C then B) | 478,646 | 457,053 | 397.5 | 419.6 | 4,809 | 5,201 |
| a2 (B then C) | 574,966 | 448,778 | 322.5 | 420.7 | 3,771 | 4,427 |
| a3 (C then B) | 475,548 | 383,076 | 388.6 | 500.3 | 4,421 | 5,611 |
| a4 (B then C) | 445,322 | 439,441 | 425.8 | 429.5 | 5,185 | 5,453 |
| a5 (C then B) | 505,693 | 475,790 | 370.8 | 394.7 | 4,319 | 4,689 |
| s3 warmup | 529,889 | 453,447 | 357.0 | 422.4 | 4,347 | 4,683 |
| s3-1 (C then B) | 491,604 | 387,829 | 385.1 | 475.4 | 4,636 | 5,890 |
| s3-2 (B then C) | 486,806 | 492,166 | 390.9 | 381.5 | 4,983 | 4,668 |
| s3-3 (C then B) | 425,700 | 406,812 | 450.8 | 469.0 | 5,674 | 5,878 |
| s3-4 (B then C) | 407,536 | 474,145 | 475.4 | 401.2 | 6,087 | 5,099 |
| s3-5 (C then B) | 581,445 | 471,824 | 324.1 | 393.7 | 3,992 | 4,896 |

| Series | Throughput change (median) | p99 change | CPU change |
| --- | --- | --- | --- |
| Series 1 (fixed order) | −10.0% | +12.1% | +17.6% |
| Series 2 (alternated) | −6.2% | +5.8% | +12.5% |
| Series 1 + 2 (10 runs) | −9.3% | +11.0% | +17.6% |
| Series 3 (alternated, after resume) | −3.1% | +2.6% | +2.3% |
| All 15 runs | −8.4% | +9.0% | +14.9% |

**Findings (reported to the coordinator, not justified here).**

1. **1,000-recipient message phase:**
   - Throughput: −8.4% on all 15 runs, −9.3% on series 1+2, −10.0% on series 1 alone, −3.1% on series 3.
   - Process CPU: +14.9% on all 15 runs, over the 10% review threshold.
   - p99: +9.0%. Allocation: unchanged.
   - The candidate was slower in 12 of the 15 pairs: 4 of 5 in series 1, 5 of 5 in series 2, 3 of 5 in series 3.
2. **Series 3 alone has two throughput regressions past 10%:**
   - 100-recipient AWAY: −43.4%.
   - 100-recipient groups: −12.0%.

   Pooled over 15 runs those are −1.5% and −2.5%, and both phases are bimodal on both revisions (table above). Still, the candidate lands in the slow mode more often.
3. **Other increases above 10% on all 15 runs:**
   - 100-recipient message CPU: +11.4%.
   - 100-recipient AWAY p99: +10.5%, max +11.7%.

Nothing on the per-message path changed in this diff:
- `MessageDelivery`, `CapabilityTagRenderer`, `OutboundMessage`, `SessionWriter`, capture and `HookDispatcher`'s non-fault code are untouched.
- The new code runs only on a hook fault or a publication.
- No fault was reported in any run: log output is identical in volume on both revisions.

The JFR execution samples of a pair hold about 200–240 samples each, too few to attribute the CPU difference. The cause is not established. Further investigation, or a decision to accept, is the coordinator's call; see [review.md](review.md).

Existing budgets on this workload: every phase's maximum latency stays under the 1 s delivery budget on both revisions (largest candidate max 502 ms, at 1,000 recipients, message phase, run a3; largest in series 3 was 479 ms).

## Lifecycle workload (T060)

1,000 live socket sessions in 10 channels:
- **Clients:** half sent `CAP LS 302`, so every change notifies 500 eligible writers. All hold `multi-prefix message-tags batch`.
- **Background traffic:** one member per channel sends a tagged `PRIVMSG` every 2 ms. A generator enqueues two-member groups to random sessions every 0.2 ms.
- **Changes:** 5 warmup and 40 measured cycles, alternating between `multi-prefix` and `message-tags`. Each cycle disables, then re-enables. Every fourth cycle goes through `REHASH` (the file is rewritten and `ConfigurationReloader.reload()` runs); the rest go through `EXTENSION`'s registry path. After each change, every session asks for the name again, so each disable prunes all 1,000.

Stages come from the new `ExtensionRegistry.observeCapabilityPublications` timings, measured inside the publication:
- **Wait:** acquiring the administrative mutex.
- **Held:** building and swapping the snapshot, plus the all-session prune.
- **Enqueue:** queuing `DEL`/`NEW`.

The test also times each whole change, and the `DEL` receipt at clients relative to the change's start.

Assertions:
- No deadlock: every worker stops and every reader drains to a final PING barrier.
- After each change, under the mutex, no session holds an unoffered name, and fresh captures agree with their snapshot.
- Every framed group seen opens, carries only tagged members, and closes, with nothing interleaved.
- Wait + held p99 ≤ 100 ms.

No output cutoff after `DEL` is asserted.

| Stage, p99 / max ms | Run 1 | Run 2 | Run 3 (`loadTest`) | Run 4 (final class) |
| --- | --- | --- | --- | --- |
| Mutex wait (80 publications) | 11.695 / 11.695 | 0.002 / 0.002 | 0.031 / 0.031 | 0.014 / 0.014 |
| Swap + prune (held) | 1.872 / 1.872 | 1.830 / 1.830 | 1.668 / 1.668 | 2.765 / 2.765 |
| **Wait + swap + prune (budget ≤ 100)** | **12.421 / 12.421** | **1.831 / 1.831** | **1.668 / 1.668** | **2.780 / 2.780** |
| `DEL`/`NEW` enqueue (500 eligible) | 1.638 / 1.638 | 1.943 / 1.943 | 1.589 / 1.589 | 1.913 / 1.913 |
| Whole `EXTENSION` change (also provider stop/start and lifecycle bookkeeping), 60 samples | 12.926 / 12.926 | 2.804 / 2.804 | 3.294 / 3.294 | 3.477 / 3.477 |
| Whole `REHASH` change (also file write, parse, validation, callbacks), 20 samples | 14.205 / 14.205 | 15.887 / 15.887 | 16.827 / 16.827 | 18.148 / 18.148 |
| `DEL` receipt after change start (also enqueue and socket drain), 20,000 samples | 21.769 / 26.161 | 20.949 / 27.013 | 20.664 / 25.876 | 21.711 / 25.997 |

The budget holds with a wide margin. Two observations:
- **Mutex wait** dominates when it appears. It is the cost #97's review asked to profile: every recipient's tag preparation takes the mutex for a coherent capture (FR-005), so under steady output a publication can queue behind captures. In the worst sample it waited 11.7 ms.
- **Socket drain** is reported separately and is not part of the budget.

Run 1 is the first complete run. It failed one of the test's own checks: readers stopped on a flag while still reading, so one framed group's close was never read. The test now drains each reader to a final PING barrier; the server and the timings were unaffected. Run 2 is the run after that fix, and run 3 is inside `./gradlew loadTest` below. Run 4 is the final class: the same workload, with ACK waits on a latch instead of polling and the generators paced with `LockSupport.parkNanos`. Run 5 (final class, inside the second `./gradlew loadTest`): wait + swap/prune p99 6.679 ms, enqueue 1.821 ms, whole REHASH change 20.900 ms, `DEL` receipt 20.085 ms.

## `./gradlew loadTest` (existing budgets)

One run per revision, baseline first, nothing else running. Both passed every load test: 13 on the baseline (with the fan-out class added as above) and 14 on the candidate (plus the lifecycle class). Raw output: [raw/loadtest-full-baseline.txt](raw/loadtest-full-baseline.txt), [raw/loadtest-full-candidate.txt](raw/loadtest-full-candidate.txt).

| Load test | Baseline | Candidate |
| --- | --- | --- |
| Story1DeliveryLatency (50 messages) | p50 0.454, p99 1.989 ms | p50 0.600, p99 0.895 ms |
| ConcurrentConnectionScale | p50 0.793, p99 1.353 ms | p50 0.755, p99 0.947 ms |
| ExceptionModes | join 0.433 ms, p50 0.096, max 0.270 ms | join 0.609 ms, p50 0.125, max 0.349 ms |
| JoinStorm (2,000 clients) | storm 2,276 ms; quiet 20 messages, p50 20.7, p99 89.0 ms | storm 2,079 ms; quiet 19 messages, p50 17.9, p99 68.1 ms |
| SingleWaveJoinStorm | 1,916 ms, 0 overflow disconnects | 1,795 ms, 0 overflow disconnects |
| CapabilityPublicationTiming (#97), no-notify disable | p99 0.587 ms | p99 0.519 ms |
| CapabilityPublicationTiming (#97), notify-302 disable | p99 3.402 ms | p99 3.054 ms |
| KeepAlive, RateLimit | pass | pass |
| CapabilityFanout, 1,000 recipients, message | 497,813/s, p99 382.1 ms, CPU 4,801 ms | 468,848/s (−5.8%), p99 405.7 ms (+6.2%), CPU 4,942 ms (+2.9%) |
| CapabilityLifecycle (run 3) | — | wait + swap/prune p99 1.668 ms; enqueue p99 1.589 ms; REHASH change p99 16.827 ms; DEL receipt p99 20.664 ms |

**Second run (15:39, after the resume, baseline first):** every load test passed on both revisions. Raw output: [raw/loadtest-full-s3-baseline.txt](raw/loadtest-full-s3-baseline.txt), [raw/loadtest-full-s3-candidate.txt](raw/loadtest-full-s3-candidate.txt).

| Load test | Baseline | Candidate |
| --- | --- | --- |
| Story1DeliveryLatency | p50 0.616, p99 1.068 ms | p50 0.590, p99 1.143 ms |
| ConcurrentConnectionScale | p50 0.735, p99 1.752 ms | p50 0.642, p99 0.926 ms |
| ExceptionModes | join 0.575 ms, p50 0.151, max 0.410 ms | join 0.619 ms, p50 0.113, max 0.313 ms |
| JoinStorm (2,000 clients) | storm 2,087 ms; quiet p50 28.6, p99 126.7 ms | storm 2,190 ms; quiet p50 23.6, p99 149.2 ms |
| SingleWaveJoinStorm | 1,921 ms, 0 overflow disconnects | 2,039 ms, 0 overflow disconnects |
| CapabilityPublicationTiming (#97), no-notify / notify-302 disable p99 | 0.680 / 3.205 ms | 0.449 / 4.203 ms |
| CapabilityFanout, 1,000 recipients, message | 495,097/s, p99 380.4 ms, CPU 4,577 ms | 538,380/s (+8.7%), p99 353.2 ms, CPU 4,365 ms |
| CapabilityLifecycle (run 5) | — | wait + swap/prune p99 6.679 ms; enqueue p99 1.821 ms; REHASH change p99 20.900 ms; DEL receipt p99 20.085 ms |

Delivery stays far under 1 s, and the JOIN-storm budgets hold with a wide margin (storm under 15 s, quiet p99 under 500 ms and max under 1 s). JoinStorm's quiet-channel count (19 against 20) is the number of samples sent inside the storm window, which was shorter on the candidate run (2,079 ms against 2,276 ms); no sample was lost.

## Series tables

### Series 1 (fixed order)

| Recipients | Phase | Throughput/s | p99 ms | max ms | CPU ms | Alloc MB |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | message | 32,439 → 32,386 (-0.2%) | 52.078 → 53.884 (+3.5%) | 52.247 → 54.261 (+3.9%) | 517.4 → 539.5 (+4.3%) | 19.9 → 20.0 (+0.5%) |
| 10 | away | 29,219 → 29,186 (-0.1%) | 5.224 → 5.395 (+3.3%) | 5.246 → 5.410 (+3.1%) | 47.6 → 45.7 (-4.0%) | 1.2 → 1.2 (+0.0%) |
| 10 | member | 2,755 → 2,847 (+3.3%) | **0.654 → 0.583 (-10.9%)** | **0.654 → 0.583 (-10.9%)** | 39.3 → 38.2 (-2.8%) | 1.1 → 1.1 (+0.0%) |
| 10 | group | 21,778 → 21,178 (-2.8%) | **0.277 → 0.219 (-20.9%)** | 1.160 → 1.236 (+6.6%) | 65.1 → 67.0 (+2.9%) | 3.3 → 3.3 (+0.0%) |
| 100 | message | 187,443 → 185,272 (-1.2%) | 97.902 → 99.910 (+2.1%) | 98.719 → 100.842 (+2.2%) | 1,159.3 → 1,181.7 (+1.9%) | 140.1 → 139.3 (-0.6%) |
| 100 | away | 367,220 → 361,747 (-1.5%) | 5.239 → 5.027 (-4.0%) | 5.275 → 5.058 (-4.1%) | **66.4 → 58.7 (-11.6%)** | 3.6 → 3.7 (+2.8%) |
| 100 | member | 1,730 → 1,809 (+4.6%) | 1.162 → 1.149 (-1.1%) | 1.162 → 1.149 (-1.1%) | 62.3 → 62.0 (-0.5%) | 7.8 → 7.8 (+0.0%) |
| 100 | group | 45,219 → 48,777 (+7.9%) | **9.757 → 1.822 (-81.3%)** | **12.405 → 2.274 (-81.7%)** | 394.6 → 366.9 (-7.0%) | 27.8 → 27.9 (+0.4%) |
| 1,000 | message | **499,901 → 449,904 (-10.0%)** | **372.898 → 418.191 (+12.1%)** | **376.348 → 422.818 (+12.3%)** | **4,487.7 → 5,275.4 (+17.6%)** | 1,258.1 → 1,257.3 (-0.1%) |
| 1,000 | away | 630,315 → 624,611 (-0.9%) | 34.366 → 31.941 (-7.1%) | 34.508 → 32.161 (-6.8%) | 414.2 → 390.9 (-5.6%) | 30.3 → 30.0 (-1.0%) |
| 1,000 | member | 1,410 → 1,488 (+5.5%) | **2.301 → 1.845 (-19.8%)** | **2.301 → 1.845 (-19.8%)** | 60.8 → 56.8 (-6.6%) | 37.0 → 34.8 (-5.9%) |
| 1,000 | group | 95,820 → 94,913 (-0.9%) | **31.190 → 27.503 (-11.8%)** | 33.909 → 34.200 (+0.9%) | 2,093.9 → 2,114.9 (+1.0%) | 290.5 → 298.5 (+2.8%) |

### Series 2 (alternated order)

| Recipients | Phase | Throughput/s | p99 ms | max ms | CPU ms | Alloc MB |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | message | 30,731 → 32,758 (+6.6%) | 55.789 → 52.588 (-5.7%) | 56.332 → 52.747 (-6.4%) | 560.8 → 535.1 (-4.6%) | 20.0 → 20.0 (+0.0%) |
| 10 | away | 29,180 → 29,639 (+1.6%) | 5.329 → 5.340 (+0.2%) | 5.337 → 5.347 (+0.2%) | 50.9 → 50.4 (-1.0%) | 1.2 → 1.2 (+0.0%) |
| 10 | member | **2,476 → 2,754 (+11.2%)** | **0.693 → 0.598 (-13.7%)** | **0.693 → 0.598 (-13.7%)** | **43.2 → 37.4 (-13.4%)** | 1.1 → 1.1 (+0.0%) |
| 10 | group | 21,848 → 21,612 (-1.1%) | **0.292 → 0.260 (-11.0%)** | **1.208 → 1.058 (-12.4%)** | 72.6 → 68.0 (-6.3%) | 3.3 → 3.3 (+0.0%) |
| 100 | message | 192,500 → 194,201 (+0.9%) | 90.873 → 90.337 (-0.6%) | 92.051 → 91.534 (-0.6%) | 1,080.3 → 1,146.6 (+6.1%) | 139.2 → 140.3 (+0.8%) |
| 100 | away | 366,800 → 367,017 (+0.1%) | **4.167 → 5.573 (+33.7%)** | **4.219 → 5.665 (+34.3%)** | **55.2 → 67.8 (+22.8%)** | 3.6 → 3.7 (+2.8%) |
| 100 | member | **1,951 → 1,659 (-15.0%)** | **1.030 → 1.134 (+10.1%)** | **1.030 → 1.134 (+10.1%)** | 59.7 → 60.7 (+1.7%) | 7.8 → 7.8 (+0.0%) |
| 100 | group | 54,219 → 49,490 (-8.7%) | **1.188 → 0.850 (-28.5%)** | **2.709 → 1.249 (-53.9%)** | 347.1 → 356.5 (+2.7%) | 28.2 → 28.1 (-0.4%) |
| 1,000 | message | 478,646 → 448,778 (-6.2%) | 388.640 → 420.720 (+8.3%) | 394.739 → 422.464 (+7.0%) | **4,420.7 → 5,201.0 (+17.7%)** | 1,261.8 → 1,259.3 (-0.2%) |
| 1,000 | away | 711,259 → 645,534 (-9.2%) | **30.177 → 34.614 (+14.7%)** | **30.340 → 34.774 (+14.6%)** | **377.8 → 416.9 (+10.3%)** | 30.1 → 30.4 (+1.0%) |
| 1,000 | member | **1,416 → 1,642 (+16.0%)** | 1.573 → 1.569 (-0.3%) | 1.573 → 1.569 (-0.3%) | **61.1 → 50.3 (-17.7%)** | 36.5 → 34.5 (-5.5%) |
| 1,000 | group | **96,150 → 106,453 (+10.7%)** | **29.163 → 24.531 (-15.9%)** | 35.121 → 33.091 (-5.8%) | 2,104.2 → 2,116.1 (+0.6%) | 298.9 → 289.0 (-3.3%) |


## Harness noise reduction and re-run of the A/A control ([#111](https://github.com/jircd/jircd-server/issues/111))

### Diagnosis

The A/A control above was noisy because the harness measured each phase over one pass lasting a few milliseconds, not because of GC or the sockets:
- **GC is not the cause.** `-Xlog:gc*` on three runs of the old class showed only young pauses of 0.7–3.3 ms, no full GC and no concurrent-cycle stall. (G1, 2 GiB heap, 31–32 pauses per run, all three runs alike.) A 1–3 ms pause does matter when the whole window is 5 ms, which is what a pass over 100 recipients' AWAY items took (2,500 items, about 7 ms; group about 30 ms; the 1,000-recipient NAMES/WHO phase 20 operations, about 13 ms).
- **Warm-up was missing.** The first phase of a JVM ran cold: with per-round samples, 10-recipient message throughput climbed from 90k to 494k items/s over 12 rounds (p99 179 → 30 ms). The old single pass sampled that ramp at a fixed, but arbitrary, point; the 10-recipient CV of about 2% reflected a consistent cold start, not precision.
- **Window length scales against us.** Each phase had a fixed item count, so the window shrank relative to a pause as recipients grew in AWAY and group.

### Changes

[CapabilityFanoutLoadTest](../../../../jircd-integration-tests/src/test/java/net/jircd/integration/CapabilityFanoutLoadTest.java) now:
- runs every phase in rounds: a warm-up of at least 3 rounds and 1 s, then 9 measured rounds, and reports the **median** over the measured rounds (p50, p99, max, throughput, CPU, allocation) in the same `recipients=… phase=…` line, plus `worstMaxMs` and the in-run round CV;
- prints one `CapabilityFanoutLoadTest.round:` line per round, warm-up included, for diagnosis;
- sizes the items per round by recipient count (`Load.of`) so each measured window is at least about 50 ms (the run prints `windowMs`; after the round-1 review fixes the medians are 48–300 ms, the 10-recipient member phase being the shortest at about 50 ms);
- reads the NAMES/WHO replies up to the real `366`/`315` numeric. `readUntil("315")` also stopped at a WHO line that merely contained those digits (a nickname such as `r315`), which left part of the listing queued, understated those latencies, and overflowed the sender's 2 MiB SendQ once a round had 60+ listings; this probably contributed to the 1,000-recipient NAMES/WHO noise;
- builds group messages and drains the sender's echoes before the timed window, so it times delivery only;
- keeps every content, order and framing assertion, now across all rounds, and still fails when any delivery, in a warm-up or a measured round, takes 1 s or more; warm-up rounds stay out of the reported statistics.

Statistics are in `FanoutStats` (unit-tested by `FanoutStatsTest`). The `loadTest` run of this class went from about 3 s to 25–30 s on the same machine; the full `./gradlew loadTest` grows by the same amount.

### How to run the A/A control (for later PRs)

```
scripts/fanout-aa.sh [pairs] [output-dir]      # default 5 pairs; MAX_LOAD (5) and MAX_WAIT (300 s) gate on the load average
python3 scripts/fanout-aa.py <dir>            # A/A: CV and noise unit per phase and metric
python3 scripts/fanout-aa.py <dir-a> <dir-b>  # A/B: median difference of b against a, in noise units
```

`fanout-aa.sh` runs a warm-up and then the pairs B A, A B, … on the current checkout, records the power source and load before every run in `conditions.txt` (runs that started above `MAX_LOAD` are marked `CONTENDED`; redo them), and prints the A/A table. Run it on AC power with no other build running. For an A/B comparison run it on each revision and compare the two directories. Report the noise unit next to every claimed difference; a difference within two units is not a finding.

### Result (AC power, 5 pairs, 2026-10-27, after review round 1)

Raw runs, the load before each run and the full report (all three metrics) are in [raw/aa-111/](raw/aa-111/) (`conditions.txt`, `report.txt`). The harness is the one in this PR. The run used `MAX_LOAD=5`: no run was marked `CONTENDED`, but the 1-minute load was still 4.2–5.0 before every run (4.18, 4.64, 4.77, 4.92, 4.87, 4.93, 5.00, 4.92, 4.69, 4.91, 4.92; the laptop's own background activity plus a Codex agent editing docs; no builds or tests ran alongside). These CVs are therefore measured at that background level and may overstate a fully idle machine. The load rose to 8.5 only after the last run. This replaces the earlier result, which used the pre-review harness (shorter 10-recipient windows and the `readUntil("315")` flaw).

Throughput over the ten runs (item counts per round differ from the old runs, so medians are not comparable with the earlier tables; the CVs are). The noise unit is 0.79 × CV for the median of five runs per side, and the detection limit is two noise units.

| Recipients | Phase | Median items/s | CV | Before (#102 A/A) | Noise unit | Detection limit (2 units) | CV ≤ 5% |
|---|---|---|---|---|---|---|---|
| 10 | message | 521,948 | 3.5% | 2.0% (cold start) | 2.8% | 5.6% | yes |
| 10 | away | 660,987 | 3.3% | 4.3% | 2.6% | 5.2% | yes |
| 10 | member | 19,862 | 1.5% | 5.6% | 1.2% | 2.4% | yes |
| 10 | group | 256,189 | 1.9% | 6.9% | 1.5% | 3.0% | yes |
| 100 | message | 655,077 | 12.0% | 10.2% | 9.8% | 19.6% | **no** |
| 100 | away | 949,330 | 5.2% | 23.7% | 4.2% | 8.4% | **no** (just over) |
| 100 | member | 7,479 | 2.2% | 7.4% | 1.7% | 3.4% | yes |
| 100 | group | 232,955 | 3.1% | 18.0% | 2.4% | 4.8% | yes |
| 1,000 | message | 666,951 | 8.1% | 12.5% | 6.6% | 13.2% | **no** |
| 1,000 | away | 897,871 | 7.0% | 6.8% | 5.5% | 11.0% | **no** |
| 1,000 | member | 1,112 | 4.6% | 19.5% | 3.6% | 7.2% | yes |
| 1,000 | group | 194,145 | 2.0% | 5.8% | 1.6% | 3.2% | yes |

**Against the goal (CV ≤ 5% in every phase used for a regression decision): met for 8 of 12 phases, not met for 4.** The three phases that drove #102 are now at 5.2% (100 away, from 23.7%), 3.1% (100 group, from 18.0%) and 4.6% (1,000 NAMES/WHO, from 19.5%). The four that still miss are the 100- and 1,000-recipient message phases (12.0% and 8.1%, with a high outlier run at 100 recipients), 1,000-recipient AWAY (7.0%) and 100-recipient AWAY (5.2%). Within-run, those phases' rounds also vary the most, so longer windows alone did not remove it; the cause is not established.

The 10-recipient message CV is now 3.5%, against 2.0% before. The old figure measured a consistent cold start, not precision, so the comparison is not like for like.

p99 and CPU are noisier than throughput (CV 1.7–46%; the 10-recipient AWAY p99 is 46%) and should be read as supporting evidence only; see `report.txt`.

**Detection limit achieved.** With five pairs a throughput difference smaller than two noise units cannot be told from noise:
- about **2–6%** in the 10-recipient phases, 100-recipient member and group, and 1,000-recipient group and member (limits 2.4–7.2%);
- **8.4%** in 100-recipient AWAY;
- **11.0%** in 1,000-recipient AWAY, **13.2%** in 1,000-recipient message and **19.6%** in 100-recipient message.

T061's 10% throughput budget can therefore be decided in 9 of 12 phases (every limit below 10%, including 100-recipient AWAY at 8.4%). For 1,000-recipient AWAY and the 100- and 1,000-recipient message phases it can only be decided above the limits stated here; a result inside them is not evidence of no regression. More pairs would narrow these limits by roughly the square root of the pair count.
