#!/usr/bin/env bash
# Runs single Microbenchmark tests, or Macrobenchmark cases, under simpleperf on the connected phone.
#
#   scripts/profile.sh counters <out-file> <Class#test>...   cycles + instructions per test (2 events: no counter time-sharing);
#                                                             EVENTS=a,b picks two other events, DURATION=s shortens the 120 s window
#   scripts/profile.sh heat     <out-dir>  <Class#test>...   samples per instruction address, one report per test
#   scripts/profile.sh macro    <out-dir>  <case>...         call-graph samples of the sample app during Macrobenchmark cases
#
# counters, heat: install the benchmark APK first (./gradlew :benchmark:assembleBenchmarkAndroidTest, then
# adb install -r -t); this script AOT-compiles it like Microbenchmark does.
#
# macro: runs each case of the Macrobenchmark suite SUITE (default reentry, or tiers; see run_bench.sh, which runs it
# and keeps its traces) while the sample app is recorded: one `simpleperf record --app` per app process, so one file per
# iteration (Macrobenchmark restarts the app for each). Events: cpu-clock every 250 µs of on-CPU time, and with
# FAULTS=1 also every page fault (a sample per fault slows fault-heavy code down: take times from a run without it),
# with DWARF call graphs (ART-compiled Java frames have no frame-pointer chain) from a STACK-byte stack copy
# (default 16384: enough from the leaf up to the draw node; the 64 KiB default overflows the kernel buffers and loses
# samples). Raw data goes to results/<device>/traces-<name of out-dir>/: the Macrobenchmark output under
# <suite>/<case>/ (TRACES of run_bench.sh) and the records under simpleperf/<case>/. RUNNER=perfetto runs the tier
# cases through run_perfetto_tiers.sh instead (for a device where Macrobenchmark does not run), its traces under
# tiers/<case>/. Report with scripts/report_shadow_profile.py. Install the sample release APK and the macrobenchmark
# APK first; keep the sample's R8 mapping of that build for the report.
#
# Every test or case waits for the thermal gate of lib.sh (skin < 36 °C by default; GATE_ZONE / GATE_MAX / GATE_TRIES).
# Needs ANDROID_SERIAL and the device unlocked. KEEP_AWAKE=1 leaves the screen on at the end (inside a batch); see
# lib.sh.
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
source "$(dirname "$0")/lib.sh"

PACKAGE=dev.serhiiyaremych.obvid.benchmark.test
INSTRUMENTATION_RUNNER=$PACKAGE/androidx.benchmark.junit4.AndroidBenchmarkRunner
TESTS=dev.serhiiyaremych.obvid.benchmark

mode=$1 out=$2
shift 2

require_unlocked

if [[ $mode == macro ]]; then
  root=$(cd "$(dirname "$0")/.." && pwd)
  app=dev.serhiiyaremych.obvid.sample
  name=$(basename "$out")
  raw=$root/results/$(device_name)/traces-$name
  tmp=/data/local/tmp/fsprof
  faults=$([[ ${FAULTS:-} == 1 ]] && echo "-c 1 -e page-faults" || true)
  mkdir -p "$out"
  begin_session
  for case in "$@"; do
    wait_cool
    echo "== $case (skin $GATE_READING °C)"
    adb shell "rm -rf $tmp; mkdir -p $tmp"
    # One record per app process: simpleperf waits for the app to start and stops when the process dies. The stop
    # file ends the loop once the last process is stopped.
    adb shell "i=0; while [ ! -e $tmp/stop ]; do simpleperf record --app $app -c 250000 -e cpu-clock $faults \
      --call-graph dwarf,${STACK:-16384} -o $tmp/iter\$i.data > $tmp/iter\$i.log 2>&1; i=\$((i + 1)); done" &
    loop=$!
    if [[ ${RUNNER:-} == perfetto ]]; then
      KEEP_AWAKE=1 TRACES=traces-$name "$(dirname "$0")/run_perfetto_tiers.sh" "$case"
    else
      KEEP_AWAKE=1 TRACES=traces-$name "$(dirname "$0")/run_bench.sh" "${SUITE:-reentry}" "$case"
    fi
    adb shell "touch $tmp/stop; am force-stop $app"
    # A record the loop started before the stop file waits for an app start, and runs as the app's user (shell cannot
    # kill it): start the app once more and stop it, which ends that record too.
    for _ in $(seq 20); do
      kill -0 "$loop" 2> /dev/null || break
      adb shell "am start -n $app/.MainActivity > /dev/null; sleep 2; am force-stop $app"
    done
    wait "$loop" || true
    rm -rf "$raw/simpleperf/$case" && mkdir -p "$raw/simpleperf/$case"
    adb pull "$tmp/." "$raw/simpleperf/$case/" > /dev/null
    # Records of an app start the loop caught but no iteration followed (empty or unreadable) are dropped.
    find "$raw/simpleperf/$case" -name '*.data' -size -100k -delete
    grep -h "Samples recorded" "$raw/simpleperf/$case"/*.log | sed 's/.*] /  /' || true
  done
  end_session
  exit 0
fi
adb shell cmd package compile -m speed -f "$PACKAGE" > /dev/null
[[ $mode == heat ]] && mkdir -p "$out" || : > "$out"
begin_session

total=$# index=0
for test in "$@"; do
  index=$((index + 1))
  wait_cool
  echo "[$index/$total] $test (skin $GATE_READING °C)"
  if [[ $mode == counters ]]; then
    adb shell "rm -f /data/local/tmp/profile.txt; simpleperf stat --app $PACKAGE -e ${EVENTS:-cpu-cycles,instructions} --duration ${DURATION:-120} -o /data/local/tmp/profile.txt" > /dev/null 2>&1 &
  else
    adb shell "rm -f /data/local/tmp/profile.data; simpleperf record --app $PACKAGE -e cpu-cycles -f 4000 --duration 120 -o /data/local/tmp/profile.data" > /dev/null 2>&1 &
  fi
  sleep 2
  adb shell am instrument -w -e class "$TESTS.$test" "$INSTRUMENTATION_RUNNER" | grep -E "^OK|FAIL" | head -1 || echo "  no test result"
  wait
  if [[ $mode == counters ]]; then
    { echo "== $test"; adb shell cat /data/local/tmp/profile.txt | grep -E "^\s+[0-9,]+ " || echo "  no counters"; } >> "$out"
  else
    adb shell "simpleperf report -i /data/local/tmp/profile.data --sort dso,symbol" 2> /dev/null | grep -E "^\s*[0-9.]+%" > "$out/${test#*#}-symbols.txt" ||
      echo "  no samples in the symbol report"
    adb shell "simpleperf report -i /data/local/tmp/profile.data --sort dso,vaddr_in_file" 2> /dev/null | grep -E "^\s*[0-9.]+%" > "$out/${test#*#}-heat.txt" ||
      echo "  no samples in the address report"
  fi
done
end_session
