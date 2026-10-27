# Recomputes the A/A table in performance.md from the per-run summaries in this directory.
# Usage: python3 aa-analysis.py <this raw directory>
import re, statistics as st, sys

RAW = sys.argv[1]


def load(name):
    d = {}
    for line in open(f"{RAW}/fanout-{name}.txt"):
        m = dict(re.findall(r"(\w+)=([\d.]+|\w+)", line))
        d[(int(m["recipients"]), m["phase"])] = {
            k: float(m[k]) for k in ("throughputPerSec", "p99Ms", "cpuMs")
        }
    return d


runs = "12345"
A = [load(f"baseline-aaA-{i}") for i in runs]
B = [load(f"baseline-aaB-{i}") for i in runs]
base = [load(f"baseline-s4-{i}") for i in runs]
cand = [load(f"candidate-s4-{i}") for i in runs]


def median(rs, k, m):
    return st.median(r[k][m] for r in rs)


for r in (10, 100, 1000):
    for p in ("message", "away", "member", "group"):
        k = (r, p)
        for m in ("throughputPerSec", "p99Ms", "cpuMs"):
            aa = 100 * (median(B, k, m) - median(A, k, m)) / median(A, k, m)
            vals = [x[k][m] for x in A + B]
            cv = 100 * st.stdev(vals) / st.mean(vals)
            unit = 0.79 * cv  # sd of a difference of two medians of five, normal approximation
            ab = 100 * (median(cand, k, m) - median(base, k, m)) / median(base, k, m)
            outside = sum(1 for x in cand if not min(vals) <= x[k][m] <= max(vals))
            print(r, p, m, f"A/A {aa:+.1f}%", f"CV {cv:.1f}%", f"A/B {ab:+.1f}%",
                  f"units {ab / unit:+.1f}", f"outside {outside}/5")
