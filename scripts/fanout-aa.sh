#!/usr/bin/env bash
# A/A control for CapabilityFanoutLoadTest: runs the fan-out load test on the current checkout in
# alternating B A / A B pairs and reports the run-to-run noise of every phase, so a later PR can
# state the smallest regression the test could have detected.
#
#   scripts/fanout-aa.sh [pairs] [output-dir]
#
# Defaults: 5 pairs, output in build/fanout-aa/<timestamp>. Run it on AC power with nothing else
# heavy running; the load average and power source are recorded before every run, and the script
# waits for the 1-minute load average to fall to MAX_LOAD (default 5) for up to MAX_WAIT seconds
# (default 300) before each run, and marks a run that starts above it CONTENDED so it can be
# redone. For an A/B comparison, run the same script on each revision
# and compare the two output directories with scripts/fanout-aa.py.
set -euo pipefail

pairs="${1:-5}"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="${2:-$root/build/fanout-aa/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
out="$(cd "$out" && pwd)" # absolute, so the cd below cannot move it
cd "$root"

power() {
    if command -v pmset >/dev/null; then pmset -g batt | head -1; else echo 'power unknown'; fi
}

# The 1-minute load average as a plain number: /proc/loadavg on Linux, sysctl on macOS. Anything
# that does not parse as a number stops the script, since a wrong load would skip the gating.
load1() {
    local raw value
    if [[ -r /proc/loadavg ]]; then
        raw="$(cut -d' ' -f1 /proc/loadavg)"
    else
        raw="$(sysctl -n vm.loadavg)"
    fi
    value="$(echo "$raw" | tr -d '{},' | awk '{print $1}')"
    if ! [[ "$value" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
        echo "cannot read the load average from: $raw" >&2
        exit 1
    fi
    echo "$value"
}

condition() {
    echo "$(date +%H:%M:%S) before $1 | $(power) | load1 $(load1)"
}

settle() {
    local waited=0
    while ((waited < ${MAX_WAIT:-300})) && awk -v l="$(load1)" -v m="${MAX_LOAD:-5}" 'BEGIN{exit !(l > m)}'; do
        sleep 5
        waited=$((waited + 5))
    done
}

run() {
    local label="$1" mark=""
    settle
    if awk -v l="$(load1)" -v m="${MAX_LOAD:-5}" 'BEGIN{exit !(l > m)}'; then mark=" CONTENDED"; fi
    condition "$label$mark" | tee -a "$out/conditions.txt"
    ./gradlew :jircd-integration-tests:cleanLoadTest :jircd-integration-tests:loadTest \
        --tests '*CapabilityFanoutLoadTest*' -q
    grep -hE 'CapabilityFanoutLoadTest(\.round)?: recipients=[0-9]+ phase=' \
        jircd-integration-tests/build/test-results/loadTest/TEST-*.xml >"$out/$label.txt"
}

run warmup
for pair in $(seq 1 "$pairs"); do
    if ((pair % 2)); then order=(B A); else order=(A B); fi
    for side in "${order[@]}"; do run "$side-$pair"; done
done
condition "after last" | tee -a "$out/conditions.txt"
if grep -q CONTENDED "$out/conditions.txt"; then
    echo "warning: runs marked CONTENDED started under load above ${MAX_LOAD:-5}; redo them" >&2
fi

if pgrep -fl 'CapabilityFanoutLoadTest|Gradle Test Executor' >/dev/null; then
    echo "warning: a test JVM is still running" >&2
fi
python3 "$root/scripts/fanout-aa.py" "$out"
