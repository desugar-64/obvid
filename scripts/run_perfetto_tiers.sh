#!/usr/bin/env bash
# TierBenchmark's cases without Macrobenchmark, for a device where Macrobenchmark's on-device trace processor never
# answers. The sample runs the benchmark's input script itself (intent extra `autoplay`, see the sample's
# Autoplay.kt) and plain Perfetto records with Macrobenchmark's config (scripts/macrobenchmark-perfetto.pbtxt; the
# capture is stopped by SIGTERM instead of duration_ms). The script runs inside the async section demo:measure, the
# measured window that scripts/analyze_tiers.py and scripts/report_shadow_profile.py cut each trace to.
#
#   TRACES=<dir> scripts/run_perfetto_tiers.sh [case ...]     no case = all 39, in TierBenchmark's order
#
# case = <scenario> (Compose), cpu_<scenario> (Obvid's) or none_<scenario> (no shadows); scenario = t0_single,
# t1_static, t2_menu, t3_gears, t4_field, t5_list, t6_nav, t7_reveal, t7_scroll, t7_interact, t7_nav, t8_glow_spin,
# t8_glow_pulse.
#
# Protocol, as run_bench.sh tiers: AOT `speed` once; per case the thermal gate once (Macrobenchmark runs a case's
# iterations back to back), then ITERATIONS (default 5) times: force-stop, start the sample on the scenario with
# autoplay (fresh process), wait for its `ready` log line (targets found and 1 s settled, as the benchmark's setup),
# start Perfetto in the background, wait START_S (default 1) s, broadcast go; the app waits until app tracing is on,
# 0.5 s more, runs the script and logs `done`; TAIL_S (default 1) s later Perfetto is stopped and the trace pulled.
#
# TRACES names the run's directory under results/<device>/ (e.g. traces-tiers); required.
# RUN_SUFFIX=<s> appends ".<s>" to each case's directory (a repeat run). NAV_GAP_MS=<ms> sets t6_nav / t7_nav's gap
# (TierBenchmark's navGapMs, default 1500). KEEP_AWAKE=1 leaves the screen on at the end; gate: GATE_ZONE / GATE_MAX /
# GATE_TRIES (GATE_ZONE=battery where the thermal HAL reports nothing), see lib.sh. EST_OVERHEAD_S (default 20):
# device time per iteration besides the script, for the estimate printed up front.
#
# Compose verbose tracing (diagnostic captures only, never a timing run): `adb shell settings put global
# obvid_verbose_tracing 1` before, `settings delete global obvid_verbose_tracing` after; see sample's SampleApplication.kt.
#
# Needs ANDROID_SERIAL, the device unlocked and the sample release APK installed. Output:
# results/<device>/<TRACES>/tiers/<case>/iterN.pftrace, iterN.perfetto.txt (perfetto's output, the autoplay log lines,
# the capture's wall time), skin.txt (the gate reading at the start of the case). After each capture,
# scripts/perfetto_util.py check prints the kernel span and the measured window and WARNs on lost data or a missing
# window (host trace_processor_shell, TRACE_PROCESSOR to override; skipped if absent).
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
: "${TRACES:?set TRACES=<run directory under results/<device>/>, e.g. traces-tiers}"
source "$(dirname "$0")/lib.sh"
root=$(cd "$(dirname "$0")/.." && pwd)
device=$(device_name)
out=$root/results/$device/$TRACES/tiers
pkg=dev.serhiiyaremych.obvid.sample
go_action=$pkg.AUTOPLAY_GO
remote=/data/misc/perfetto-traces/fs-tiers.pftrace
ITERATIONS=${ITERATIONS:-5}
NAV_GAP_MS=${NAV_GAP_MS:-1500}
START_S=${START_S:-1}
TAIL_S=${TAIL_S:-1}
tp=${TRACE_PROCESSOR:-$HOME/.local/share/perfetto/prebuilts/trace_processor_shell}
[[ -x $tp ]] || echo "no trace_processor_shell at $tp: captures are not checked"
# Macrobenchmark's config with a 60 s cap: the capture ends on SIGTERM once the script is done.
cfg=$(sed -E 's/^duration_ms: .*/duration_ms: 60000/' "$root/scripts/macrobenchmark-perfetto.pbtxt")

scenarios=(t0_single t1_static t7_reveal t2_menu t3_gears t4_field t8_glow_spin t8_glow_pulse t5_list t6_nav t7_scroll t7_interact t7_nav)
if (($# == 0)); then
  set -- "${scenarios[@]}" "${scenarios[@]/#/cpu_}" "${scenarios[@]/#/none_}"
fi

# Script length in s (the scroll ones depend on the screen: phone- and tablet-sized values).
script_s() {
  case $1 in
    t0_single | t1_static | t7_reveal) echo 1 ;;
    t2_menu | t3_gears | t4_field | t8_glow_spin | t8_glow_pulse | t7_interact) echo 6 ;;
    t5_list) echo 7 ;;
    t6_nav | t7_nav) echo $((3 + NAV_GAP_MS / 1000)) ;;
    t7_scroll) echo 9 ;;
    *) echo "unknown scenario '$1'" >&2; exit 1 ;;
  esac
}
total_s=0
for name in "$@"; do
  scenario=${name#cpu_}
  scenario=${scenario#none_}
  s=$(script_s "$scenario")
  total_s=$((total_s + ITERATIONS * (${EST_OVERHEAD_S:-20} + s)))
done
echo "$# cases on $device, estimate $((total_s / 60)) min plus cooling waits"

# Waits until the app logs `<word> <token>` (or `failed <token>`); prints the line. Fails after $3 s.
await_log() {
  local word=$1 token=$2 limit=$3 began=$SECONDS line
  while ((SECONDS - began < limit)); do
    line=$(adb logcat -d -v raw -s FsAutoplay:I | tr -d '\r' | grep -E "^($word|failed) $token" | tail -1 || true)
    if [[ -n $line ]]; then
      echo "$line"
      [[ $line == "$word "* ]]
      return
    fi
    sleep 0.3
  done
  echo "no '$word' from the app in $limit s"
  return 1
}

stop_capture() {
  local pid=$1
  adb shell "kill -TERM $pid 2> /dev/null; i=0; while kill -0 $pid 2> /dev/null && [ \$i -lt 150 ]; do sleep 0.2; i=\$((i + 1)); done"
}

require_unlocked
adb shell cmd package compile -m speed -f "$pkg" > /dev/null
begin_session
index=0 began=$SECONDS
for name in "$@"; do
  index=$((index + 1))
  scenario=${name#cpu_}
  scenario=${scenario#none_}
  if [[ $name == cpu_* ]]; then impl=cpu; elif [[ $name == none_* ]]; then impl=none; else impl=compose; fi
  case $scenario in
    t7_reveal) sample_scenario=t7_discover_reveal ;;
    t7_*) sample_scenario=t7_discover ;;
    *) sample_scenario=$scenario ;;
  esac
  dir=$out/$name${RUN_SUFFIX:+.$RUN_SUFFIX}
  rm -rf "$dir" && mkdir -p "$dir"
  wait_cool
  echo "$GATE_READING" > "$dir/skin.txt"
  echo "[$index/$#] $name (skin $GATE_READING °C, $(((SECONDS - began) / 60)) min elapsed)"
  for ((i = 0; i < ITERATIONS; i++)); do
    adb shell input keyevent KEYCODE_WAKEUP
    require_unlocked
    log=$dir/iter$i.perfetto.txt
    token=$name.$i.$RANDOM$RANDOM
    adb shell am force-stop "$pkg"
    adb shell rm -f "$remote"
    adb shell am start -W -n "$pkg/.MainActivity" --es scenario "$sample_scenario" --es shadowImpl "$impl" \
      --es autoplay "$scenario" --es autoplayToken "$token" --el navGapMs "$NAV_GAP_MS" --el finishAfterMs 60000 > /dev/null
    if ! await_log ready "$token" 30 > "$log"; then
      echo "  iter$i: app not ready, see $log"
      continue
    fi
    capture_began=$SECONDS
    pid=$(adb shell "perfetto --background --txt -c - -o $remote" <<< "$cfg" 2>> "$log" | tr -d '\r' | grep -E '^[0-9]+$' | tail -1 || true)
    if [[ -z $pid ]]; then
      echo "  iter$i: perfetto did not start, see $log"
      continue
    fi
    echo "perfetto pid $pid" >> "$log"
    sleep "$START_S"
    adb shell am broadcast -a "$go_action" -p "$pkg" > /dev/null
    if ! await_log done "$token" 60 >> "$log"; then
      echo "  iter$i: script did not finish, see $log"
    fi
    sleep "$TAIL_S"
    stop_capture "$pid"
    echo "capture wall time: $((SECONDS - capture_began)) s" >> "$log"
    if adb pull "$remote" "$dir/iter$i.pftrace" > /dev/null 2>&1; then
      if [[ -x $tp ]]; then
        TRACE_PROCESSOR=$tp python3 "$root/scripts/perfetto_util.py" check "$dir/iter$i.pftrace" | sed "s/^/  $name /" ||
          echo "  iter$i: trace check failed"
      fi
    else
      echo "  iter$i: no trace pulled, see $log"
    fi
  done
  adb shell am force-stop "$pkg"
done
echo "done: $# cases in $(((SECONDS - began) / 60)) min"
end_session
