"""Noise report for CapabilityFanoutLoadTest runs.

  python3 fanout-aa.py <dir>             A/A: CV of every phase metric over the A-n and B-n runs
  python3 fanout-aa.py <dir-a> <dir-b>   A/B: median difference of <dir-b> against <dir-a>
  python3 fanout-aa.py --self-test       checks of the statistics and the input validation

<dir> holds the per-run summaries scripts/fanout-aa.sh writes (A-1.txt, B-1.txt, ...; warmup.txt
is ignored). Every run must contain all 12 phases with finite, positive metrics, or the script
names the file and stops.

The noise unit is the standard deviation of a difference between two medians. A median of n runs
has standard error 1.2533 * sd / sqrt(n) (normal approximation), and the two builds' errors are
combined from each build's own sd and run count, so a real shift between the builds does not
inflate the noise. A/A uses one pooled sd for both halves, which is right because the code is
identical. A difference within about two units cannot be told from noise. In A/A mode the exit
status is 1 when a throughput CV exceeds the 5% target.
"""
import glob
import math
import os
import re
import statistics as st
import sys

METRICS = ("throughputPerSec", "p99Ms", "cpuMs")
PHASES = ("message", "away", "member", "group")
RECIPIENTS = (10, 100, 1000)
EXPECTED = [(r, p) for r in RECIPIENTS for p in PHASES]
CV_TARGET = 5.0
MEDIAN_SE = 1.2533  # standard error of a normal sample's median, in units of sd / sqrt(n)


def parse(path):
    """One run's {(recipients, phase): {metric: value}}; exits naming the file on bad input."""
    run = {}
    with open(path) as handle:
        for line in handle:
            if line.startswith("CapabilityFanoutLoadTest.round") or "phase=" not in line:
                continue
            fields = dict(re.findall(r"(\w+)=(\S+)", line))
            try:
                key = (int(fields["recipients"]), fields["phase"])
                values = {m: float(fields[m]) for m in METRICS}
            except (KeyError, ValueError) as error:
                sys.exit(f"{path}: unreadable line ({error!r}): {line.strip()}")
            if key not in EXPECTED:
                sys.exit(f"{path}: unexpected phase {key}")
            if key in run:
                sys.exit(f"{path}: duplicate phase {key}")
            for metric, value in values.items():
                if not math.isfinite(value) or value <= 0:
                    sys.exit(f"{path}: {key} {metric}={value} is not a finite positive number")
            run[key] = values
    missing = [k for k in EXPECTED if k not in run]
    if missing:
        sys.exit(f"{path}: missing phases {missing}")
    return run


def load(directory):
    paths = sorted(glob.glob(os.path.join(directory, "[AB]-*.txt")))
    if not paths:
        sys.exit(f"{directory}: no A-n.txt / B-n.txt run files")
    return [parse(p) for p in paths]


def cv(values):
    return 100 * st.stdev(values) / st.mean(values)


def median_se(values):
    """Standard error of the median of `values`, as an absolute amount."""
    return MEDIAN_SE * st.stdev(values) / math.sqrt(len(values))


def diff_unit_percent(a, b):
    """Noise unit of median(b) against median(a): combined standard errors, % of median(a)."""
    return 100 * math.hypot(median_se(a), median_se(b)) / st.median(a)


def in_units(diff, unit):
    """`diff` in noise units; "n/a" for no difference and no noise, "inf" for a difference with none."""
    if unit == 0:
        return "n/a" if diff == 0 else "inf"
    return f"{diff / unit:+.1f}"


def aa(directory):
    runs = load(directory)
    n = len(runs)
    if n < 4:
        sys.exit(f"{directory}: need at least 4 runs, found {n}")
    print(f"A/A over {n} runs in {directory}")
    print(f"{'recipients':>10} {'phase':<8} {'metric':<17} {'median':>11} {'min-max':>23} "
          f"{'CV %':>6} {'noise unit %':>12}")
    worst = 0.0
    for key in EXPECTED:
        for metric in METRICS:
            values = [r[key][metric] for r in runs]
            c = cv(values)
            if metric == "throughputPerSec":
                worst = max(worst, c)
            # Two halves of n / 2 runs each, with the pooled sd (identical code on both sides).
            unit = 100 * math.sqrt(2) * MEDIAN_SE * st.stdev(values) / math.sqrt(n / 2)
            unit /= st.median(values)
            flag = " *" if c > CV_TARGET else ""
            print(f"{key[0]:>10} {key[1]:<8} {metric:<17} {st.median(values):>11.1f} "
                  f"{min(values):>10.1f}-{max(values):<12.1f} {c:>6.1f} {unit:>12.1f}{flag}")
    print(f"* = CV above {CV_TARGET:.0f}%. Worst throughput CV: {worst:.1f}%.")
    return worst <= CV_TARGET


def ab(dir_a, dir_b):
    a, b = load(dir_a), load(dir_b)
    if min(len(a), len(b)) < 3:
        sys.exit("need at least 3 runs per build")
    print(f"A/B: {dir_b} against {dir_a}")
    print(f"{'recipients':>10} {'phase':<8} {'metric':<17} {'median diff %':>13} "
          f"{'noise unit %':>12} {'in units':>9}")
    for key in EXPECTED:
        for metric in METRICS:
            va = [r[key][metric] for r in a]
            vb = [r[key][metric] for r in b]
            unit = diff_unit_percent(va, vb)
            diff = 100 * (st.median(vb) - st.median(va)) / st.median(va)
            print(f"{key[0]:>10} {key[1]:<8} {metric:<17} {diff:>+13.1f} {unit:>12.1f} "
                  f"{in_units(diff, unit):>9}")


def self_test():
    import tempfile
    base = [100.0, 102.0, 98.0, 101.0, 99.0]
    shifted = [v * 0.9 for v in base]  # a pure 10% slowdown, same relative spread
    same = diff_unit_percent(base, base)
    assert abs(diff_unit_percent(base, shifted) - same) < 0.1 * same + 0.2, "a shift raised noise"
    wide = [100.0, 130.0, 70.0, 120.0, 80.0]
    assert diff_unit_percent(base, wide) > same, "a noisier build must raise the noise"
    assert median_se([5.0, 5.0, 5.0]) == 0
    # Identical (or zero-spread) builds have no noise: no ZeroDivisionError.
    assert in_units(0.0, 0.0) == "n/a" and in_units(3.0, 0.0) == "inf"
    assert in_units(-4.0, 2.0) == "-2.0"
    same = [100.0, 100.0, 100.0]
    assert diff_unit_percent(same, same) == 0

    def run_file(directory, name, drop=None, extra=""):
        lines = [
            f"CapabilityFanoutLoadTest: recipients={r} phase={p} items=1 p50Ms=1 p99Ms=2.5 "
            f"maxMs=3 throughputPerSec=1000 cpuMs=10 allocMB=1"
            for r, p in EXPECTED if (r, p) != drop
        ]
        with open(os.path.join(directory, name), "w") as f:
            f.write("\n".join(lines) + "\n" + extra)

    def rejected(directory):
        try:
            load(directory)
        except SystemExit as error:
            return str(error)
        return None

    with tempfile.TemporaryDirectory() as d:
        assert "no A-n.txt" in rejected(d)
        with open(os.path.join(d, "A-1.txt"), "w") as f:
            f.write("")
        assert "missing phases" in rejected(d) and "A-1.txt" in rejected(d)
    with tempfile.TemporaryDirectory() as d:
        run_file(d, "A-1.txt")
        assert rejected(d) is None
        run_file(d, "B-1.txt", drop=(100, "member"))
        assert "B-1.txt" in rejected(d) and "missing" in rejected(d)
    with tempfile.TemporaryDirectory() as d:
        dup = ("CapabilityFanoutLoadTest: recipients=10 phase=away items=1 p50Ms=1 p99Ms=2 "
               "maxMs=3 throughputPerSec=1 cpuMs=1 allocMB=1\n")
        run_file(d, "A-1.txt", extra=dup)
        assert "duplicate" in rejected(d)
    with tempfile.TemporaryDirectory() as d:
        run_file(d, "A-1.txt", extra="")
        text = open(os.path.join(d, "A-1.txt")).read().replace(
            "throughputPerSec=1000", "throughputPerSec=nan", 1)
        open(os.path.join(d, "A-1.txt"), "w").write(text)
        assert "finite positive" in rejected(d)
    with tempfile.TemporaryDirectory() as a, tempfile.TemporaryDirectory() as b:
        for directory in (a, b):
            for name in ("A-1.txt", "A-2.txt", "A-3.txt"):
                run_file(directory, name)  # every metric identical in every run
        import contextlib
        import io
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            ab(a, b)  # must print n/a rather than raise
        assert "n/a" in captured.getvalue()
    print("self-test passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 2:
        sys.exit(0 if aa(sys.argv[1]) else 1)
    elif len(sys.argv) == 3:
        ab(*sys.argv[1:])
    else:
        sys.exit(__doc__)
