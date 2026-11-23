#!/usr/bin/env bash
# Microbenchmark and Macrobenchmark runs, one case per `am instrument` call so every case starts below the thermal
# gate. Results (JSON, Perfetto traces, the gate reading at start in skin.txt, the runner's output in instrument.txt)
# go to results/<device>/<TRACES>/<suite>/<case>/.
#
#   TRACES=<dir> scripts/run_bench.sh <suite> [case ...]     no case = every case the suite's class lists
#
# Suites:
#   micro    DropShadowRebuildBenchmark#draw (benchmark APK): one rebuild, Compose's `<case>` vs Obvid's `cpu_<case>`
#   inner    InnerShadowRebuildBenchmark#draw (benchmark APK): the same for the inner shadow
#   macro    ShadowScenarioBenchmark#animate (macrobenchmark APK + sample): `cpu_<scenario>` = sample shadowImpl=cpu
#   grid     StressGridBenchmark#stress (macrobenchmark APK + sample)
#   reentry  ReentryScrollBenchmark#scroll (macrobenchmark APK + sample): scripted back-and-forth drags over a list,
#            `cpu_<scenario>` as in macro; analyze with scripts/analyze_reentry.py
#   tiers    TierBenchmark#measure (macrobenchmark APK + sample): the sample's tier screens, `<scenario>` (Compose),
#            `cpu_<scenario>`, `none_<scenario>` (no shadows); analyze with scripts/analyze_tiers.py
#
# TRACES names the run's directory under results/<device>/ (e.g. traces-<run>); required, so a run never
# overwrites another run's raw data by default.
# RUN_SUFFIX=<s> appends ".<s>" to each case's directory (a repeat run, e.g. the second order of a pair).
# EST_SEC=<s> prints the device time estimate up front (s per case, default 25).
# INSTRUMENT_ARGS="-e <key> <value> ..." adds instrumentation arguments to every case (e.g. tiers: -e navGapMs 4000).
# COMPILATION=ignore (macro, grid, reentry, tiers): passes `-e compilation ignore`, so Macrobenchmark runs with
#   CompilationMode.Ignore() instead of Full(), and compiles the sample with `cmd package compile -m speed -f` before
#   every case itself (the AOT state Full() produces). Full() resets compilation by reinstalling the sample on
#   API < 34, which some vendor builds confirm in a dialog on every install. Unset or `full`: Full().
# KEEP_AWAKE=1 leaves the screen on at the end (for every suite of a batch but the last); see lib.sh.
# Gate: GATE_ZONE / GATE_MAX / GATE_TRIES, see lib.sh.
#
# Needs ANDROID_SERIAL set, the device unlocked, and the APKs installed:
#   micro, inner: benchmark/build/outputs/apk/androidTest/benchmark/benchmark-benchmark-androidTest.apk
#   macro, grid, reentry, tiers: the sample release APK and the macrobenchmark APK.
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
: "${TRACES:?set TRACES=<run directory under results/<device>/>, e.g. traces-cleanup}"
source "$(dirname "$0")/lib.sh"

suite=$1
shift
root=$(cd "$(dirname "$0")/.." && pwd)
device=$(device_name)
out=$root/results/$device/$TRACES/$suite

case $suite in
  micro) class=dev.serhiiyaremych.obvid.benchmark.DropShadowRebuildBenchmark method=draw ;;
  inner) class=dev.serhiiyaremych.obvid.benchmark.InnerShadowRebuildBenchmark method=draw ;;
  macro) class=dev.serhiiyaremych.obvid.macrobenchmark.ShadowScenarioBenchmark method=animate ;;
  grid) class=dev.serhiiyaremych.obvid.macrobenchmark.StressGridBenchmark method=stress ;;
  reentry) class=dev.serhiiyaremych.obvid.macrobenchmark.ReentryScrollBenchmark method=scroll ;;
  tiers) class=dev.serhiiyaremych.obvid.macrobenchmark.TierBenchmark method=measure ;;
  *) echo "suite must be micro, inner, macro, grid, reentry or tiers" >&2; exit 1 ;;
esac
compilation=${COMPILATION:-full}
case $compilation in
  full | ignore) ;;
  *) echo "COMPILATION must be full or ignore" >&2; exit 1 ;;
esac
if [[ $suite == micro || $suite == inner ]]; then
  [[ $compilation == full ]] || { echo "COMPILATION=ignore applies to the Macrobenchmark suites only" >&2; exit 1; }
  pkg=dev.serhiiyaremych.obvid.benchmark.test
  runner=$pkg/androidx.benchmark.junit4.AndroidBenchmarkRunner
  adb shell cmd package compile -m speed -f "$pkg" > /dev/null
else
  pkg=dev.serhiiyaremych.obvid.macrobenchmark
  runner=$pkg/androidx.test.runner.AndroidJUnitRunner
fi
target=dev.serhiiyaremych.obvid.sample
compile_args=
if [[ $compilation == ignore ]]; then compile_args="-e compilation ignore"; fi
media=/sdcard/Android/media/$pkg
mkdir -p "$out"
require_unlocked

# Case list: arguments, else every parameter name the class reports in a dry listing.
if (($# == 0)); then
  set -- $(adb shell am instrument -w -r -e log true -e class "$class" "$runner" |
    grep -oE "test=$method\[[^]]+\]" | sed -E "s/test=$method\[(.*)\]/\1/" | sort -u)
fi

# Screen on and unlocked for every case: Macrobenchmark frame metrics need a drawing app, and the screen timeout
# would otherwise leave it dark. `stayon usb` does not hold on a phone that reports AC power; `true` does. No
# `wm dismiss-keyguard`: with a secure lock it raises the bouncer over the (showWhenLocked) sample.
begin_session
wake() { adb shell input keyevent KEYCODE_WAKEUP; }

total=$# index=0 began=$SECONDS
echo "$total cases on $device, estimate $((total * ${EST_SEC:-25} / 60)) min plus cooling waits"
for name in "$@"; do
  index=$((index + 1))
  # Before the gate: dex2oat heats the device.
  if [[ $compilation == ignore ]]; then
    adb shell cmd package compile -m speed -f "$target" > /dev/null
  fi
  wait_cool
  wake
  require_unlocked
  start_skin=$GATE_READING
  echo "[$index/$total] $name (skin $start_skin °C, $(((SECONDS - began) / 60)) min elapsed)"
  dir=$out/$name${RUN_SUFFIX:+.$RUN_SUFFIX}
  rm -rf "$dir" && mkdir -p "$dir"
  echo "$start_skin" > "$dir/skin.txt"
  adb shell "rm -rf $media/*"
  # shellcheck disable=SC2086 # INSTRUMENT_ARGS and compile_args are lists of words
  adb shell am instrument -w ${INSTRUMENT_ARGS:-} $compile_args -e class "$class#$method[$name]" "$runner" > "$dir/instrument.txt" 2>&1 || true
  grep -qE "^OK \(" "$dir/instrument.txt" || echo "  FAILED, see $dir/instrument.txt"
  # A case that failed early writes no media directory; the batch goes on.
  adb pull "$media/." "$dir/" > /dev/null 2>&1 || echo "  no output pulled from $media"
done
echo "done: $total cases in $(((SECONDS - began) / 60)) min"
end_session
