#!/usr/bin/env bash
# Macrobenchmark-equivalent runs for a device where Macrobenchmark's on-device trace processor never answers
# (its trace_processor_shell HTTP server accepts the connection and hangs).
# Same protocol as ShadowScenarioBenchmark / StressGridBenchmark idle cases: AOT `speed`, per iteration a fresh
# process started on the scenario, 1 s settle, then a 5 s Perfetto capture with Macrobenchmark's own config
# (scripts/macrobenchmark-perfetto.pbtxt, with shorter flush/stop timeouts). The app finishes itself 30 s after start.
# Metrics: WINDOW_S=5 scripts/frame_metrics.py <case dirs> (validated against Macrobenchmark's JSON;
# the window keeps Macrobenchmark's 5 s from the first kernel event even if the capture runs longer).
#
#   TRACES=<dir> scripts/run_perfetto.sh <case> ...    case = scenario name (radius, morph6, ...) or grid_idle_<none|mix>
#                                                      prefix cpu_ (cpu_morph6): Obvid's dropShadow (shadowImpl=cpu), else Compose's
#
# TRACES names the run's directory under results/<device>/ (e.g. traces-<run>); required.
# ITERATIONS=<n> (default 5). KEEP_AWAKE=1 leaves the screen on at the end; gate: GATE_ZONE / GATE_MAX / GATE_TRIES
# (GATE_ZONE=battery GATE_MAX=<°C> where the thermal HAL reports nothing), see lib.sh.
#
# Compose verbose tracing (diagnostic captures only, never a timing run): `adb shell settings put global
# obvid_verbose_tracing 1` before, `settings delete global obvid_verbose_tracing` after; see sample's SampleApplication.kt.
#
# Needs ANDROID_SERIAL and the device unlocked. Output: results/<device>/<TRACES>/perfetto/<case>/iterN.pftrace,
# iterN.perfetto.txt (perfetto's own output and the capture's wall time), skin.txt (one gate reading per iteration).
# After each capture, scripts/perfetto_util.py check prints the kernel span and WARNs on failed flushes or other data
# the tracing service reports lost (host trace_processor_shell, TRACE_PROCESSOR to override; skipped if absent).
set -euo pipefail
: "${ANDROID_SERIAL:?set ANDROID_SERIAL}"
: "${TRACES:?set TRACES=<run directory under results/<device>/>, e.g. traces-cleanup}"
source "$(dirname "$0")/lib.sh"
root=$(cd "$(dirname "$0")/.." && pwd)
device=$(device_name)
out=$root/results/$device/$TRACES/perfetto
pkg=dev.serhiiyaremych.obvid.sample
cfg=$root/scripts/macrobenchmark-perfetto.pbtxt
ITERATIONS=${ITERATIONS:-5}
tp=${TRACE_PROCESSOR:-$HOME/.local/share/perfetto/prebuilts/trace_processor_shell}
[[ -x $tp ]] || echo "no trace_processor_shell at $tp: captures are not checked for lost data"

require_unlocked
adb shell cmd package compile -m speed -f "$pkg" > /dev/null
begin_session
for name in "$@"; do
  scenario=${name#cpu_}
  if [[ $name == cpu_* ]]; then impl=cpu; else impl=compose; fi
  case $scenario in
    grid_idle_*) extras=(--es scenario grid --es shadows "${scenario#grid_idle_}" --es first 12 --el finishAfterMs 30000) ;;
    *) extras=(--es scenario "$scenario" --el finishAfterMs 30000) ;;
  esac
  extras+=(--es shadowImpl "$impl")
  dir=$out/$name
  rm -rf "$dir" && mkdir -p "$dir"
  for ((i = 0; i < ITERATIONS; i++)); do
    wait_cool
    adb shell input keyevent KEYCODE_WAKEUP
    require_unlocked
    echo "$GATE_READING" >> "$dir/skin.txt"
    adb shell am force-stop "$pkg"
    adb shell am start -W -n "$pkg/.MainActivity" "${extras[@]}" > /dev/null
    sleep 1
    adb shell rm -f /data/misc/perfetto-traces/fs.pftrace
    log=$dir/iter$i.perfetto.txt
    began=$SECONDS
    adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/fs.pftrace < "$cfg" > "$log" 2>&1 ||
      echo "  iter$i: perfetto exited with an error, see $log"
    echo "capture wall time: $((SECONDS - began)) s" >> "$log"
    if adb pull /data/misc/perfetto-traces/fs.pftrace "$dir/iter$i.pftrace" > /dev/null 2>&1; then
      if [[ -x $tp ]]; then
        TRACE_PROCESSOR=$tp python3 "$root/scripts/perfetto_util.py" check "$dir/iter$i.pftrace" | sed "s/^/  $name /" ||
          echo "  iter$i: trace check failed"
      fi
    else
      echo "  iter$i: no trace pulled, see $log"
    fi
  done
  adb shell am force-stop "$pkg"
  echo "$name: $ITERATIONS iterations, skin at start $(head -1 "$dir/skin.txt") °C"
done
end_session
