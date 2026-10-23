# Baseline record (T001)

| Item | Value |
| --- | --- |
| Baseline server revision | `5912b68016133d7cf495bee5efe3f77ca9f09312` ("Support the multi-prefix capability (#99)") |
| Branch base used by this PR | `0d070b11a4b75d038603dc1621773356be21078d` (5912b68 plus the spec-only commit; no code differs) |
| Machine | Apple M4 Pro, 14 cores, 24 GiB, macOS 26.7.1 (Darwin 25.6.0, arm64) |
| JDK / JVM | Temurin 25.0.4+7 LTS, OpenJDK 64-Bit Server VM, mixed mode, sharing |
| Gradle | 9.8.1 (launcher and daemon JVM 25.0.4) |
| Workspace | `.worktrees/014-capability-plugin-spi` (project worktree, no temporary code workspace) |

## Baseline coverage (SonarCloud, main at `5912b68`)

Supplied by the coordinator from SonarCloud: **94.7% line, 87.5% branch, 92.6% overall**.

| | Baseline (SonarCloud) | Candidate (JaCoCo aggregate, `testCodeCoverageReport`) |
| --- | --- | --- |
| Line | 94.7% | 95.08% |
| Branch | 87.5% | 87.67% |

The two tools measure slightly differently (SonarCloud's overall figure is a blend of line and branch), so this is a like-for-like comparison only in direction: neither line nor branch coverage fell. See [results.md](results.md) for the candidate run.

## Load evidence (coordinator run of `./gradlew loadTest`)

All 8 load tests passed on main at `5912b68` and on this branch (measured before the round-2 review fixes; the coordinator re-runs them after). Same machine and JDK as above. Raw numbers: the coordinator's `load-baseline-summary.txt` and `load-repeat.txt`.

| Load test | main `5912b68` (full run) | branch (full run) |
| --- | --- | --- |
| ExceptionModes | join 0.629 ms, p50 0.243, max 0.401 | join 0.677 ms, p50 0.237 |
| ConcurrentConnectionScale | p50 0.984, p99 1.657 ms | p50 0.867, p99 1.448 ms |
| JoinStorm (2000 clients) | storm 2282 ms; quiet p50 22.3, p99 134.7 ms | storm 2287 ms; quiet p50 31.0, p99 132.1 ms |
| SingleWaveJoinStorm | 1943 ms, 0 overflow disconnects | 1972 ms, 0 overflow disconnects |
| Story1DeliveryLatency (50 messages) | p50 0.770, p99 0.891 ms | p50 0.556, p99 1.918 ms |

Repeats (3 extra runs each of the two noisiest tests, p99 in ms):

| Test | main | branch |
| --- | --- | --- |
| Story1 p99 | 1.295, 1.973, 1.292 | 1.858, 1.570, 2.135 |
| Story1 p50 | 0.814, 0.874, 0.806 | 0.614, 0.793, 0.831 |
| ConcurrentConnectionScale p99 | 2.216, 3.122, 1.714 | 1.344, 1.389, 1.416 |

**Conclusion: no meaningful regression.** Story1's p99 is the maximum of 50 samples, so it is noisy; the branch's 1.57-2.14 ms overlaps main's 0.89-1.97 ms range across runs, and its p50 is equal or lower. Join storms, quiet-channel latency and the overflow count are unchanged. Everything is far under the 1 s budget, and the JOIN-storm budgets (storm under 15 s, quiet p99 under 500 ms) hold with large margin. These runs predate the round-2 fixes.

## What is not recorded

- No pre-change local `./gradlew build` of `5912b68` was run by me; the coverage baseline above is the SonarCloud one.
- Candidate build, test and coverage reports live under `build/reports/` (paths in results.md).
