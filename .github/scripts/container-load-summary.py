#!/usr/bin/env python3
"""Markdown table comparing two ContainerDeliveryLoadTest result files (spec 016, T044).

usage: container-load-summary.py JVM.properties NATIVE.properties

Prints the table to standard output; the workflow appends it to $GITHUB_STEP_SUMMARY.
Paths must resolve inside $GITHUB_WORKSPACE (else the current directory); anything else exits 2.
A missing or empty file means that variant's run produced no result and is reported as such, not skipped.
"""
import os
import sys
from pathlib import Path

# (property, label, unit, lower is better)
METRICS = [
    ("p50Ms", "p50 latency", "ms", True),
    ("p99Ms", "p99 latency", "ms", True),
    ("maxMs", "max latency", "ms", True),
    ("throughputPerSec", "throughput", "msg/s", False),
    ("startupMs", "start-up to accepting connections", "ms", True),
    ("idleMemoryMiB", "memory, 100 idle clients", "MiB", True),
]
FLAG_OVER_PERCENT = 10.0


def allowed_base() -> Path:
    """The directory result files must live under: the workflow workspace, else the cwd."""
    return Path(os.environ.get("GITHUB_WORKSPACE") or Path.cwd()).resolve()


def safe_path(path: str) -> Path:
    """Resolves path and rejects anything outside the allowed base directory."""
    base = allowed_base()
    resolved = (base / path).resolve()
    if not resolved.is_relative_to(base):
        raise ValueError(f"{path!r} is outside {base}")
    return resolved


def read(path: str) -> dict[str, str]:
    file = safe_path(path)
    if not file.is_file():
        return {}
    values = {}
    for line in file.read_text().splitlines():
        if line and not line.startswith("#") and "=" in line:
            key, _, value = line.partition("=")
            values[key.strip()] = value.strip()
    return values


def number(values: dict[str, str], key: str) -> float | None:
    try:
        return float(values[key])
    except (KeyError, ValueError):
        return None


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        jvm, native = read(sys.argv[1]), read(sys.argv[2])
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print("## Container load: JVM vs native (amd64)\n")
    if not jvm:
        print("**The JVM run produced no result.**\n")
    if not native:
        print("**The native run produced no result** (it is experimental and does not gate the job).\n")
    print("| Metric | JVM | Native | Native vs JVM |")
    print("|---|---:|---:|---|")
    for key, label, unit, lower_is_better in METRICS:
        j, n = number(jvm, key), number(native, key)
        cells = [f"{v:g} {unit}" if v is not None else "n/a" for v in (j, n)]
        if j in (None, 0) or n is None:
            delta = "n/a"
        else:
            percent = (n - j) / j * 100.0
            worse = percent > FLAG_OVER_PERCENT if lower_is_better else -percent > FLAG_OVER_PERCENT
            delta = f"{percent:+.1f}%" + (" :warning: over 10% worse" if worse else "")
        print(f"| {label} | {cells[0]} | {cells[1]} | {delta} |")
    print("\n1,000 clients, 30 messages to a shared channel, each delivered to 999 clients; "
          "start-up includes creating the container.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
