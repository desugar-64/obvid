# Shared by run_bench.sh, run_perfetto.sh and profile.sh (sourced; they set -euo pipefail and ANDROID_SERIAL).
#
# Thermal gate: wait_cool blocks until the reading is below GATE_MAX (°C, default 36), polling every 30 s, and leaves
# the reading that passed in GATE_READING (the run's start temperature).
#   default            VIRTUAL-SKIN from `dumpsys thermalservice` (Pixel)
#   GATE_ZONE=battery  the battery temperature (a device whose thermal HAL reports nothing; no adb root needed:
#                      Macrobenchmark on a rooted adbd without `su` fails to read its traces)
#   GATE_ZONE=<type>   the /sys/class/thermal zone of that type (needs adb root)
# A reading that is empty or not a number fails the gate like a hot one; GATE_TRIES (default 10) such readings in a row
# abort the script, so a broken sensor can neither pass the gate nor hold a batch forever.
#
# Session end: end_session turns `stayon` off and sends KEYCODE_SLEEP (screens off after testing: burn-in), unless
# KEEP_AWAKE=1. A batch of several scripts sets KEEP_AWAKE=1 on all but the last: a phone that sleeps between suites
# locks, adb cannot unlock it, and the next instrumented run would sit behind the keyguard.

GATE_MAX=${GATE_MAX:-36}
GATE_TRIES=${GATE_TRIES:-10}

device_name() { adb shell getprop ro.product.model | tr ' ' '_' | tr -d '\r'; }

# One reading in °C, or an empty / non-numeric string when the source reports nothing; never fails.
skin() {
  if [[ ${GATE_ZONE:-} == battery ]]; then
    adb shell dumpsys battery | tr -d '\r' | awk '/temperature:/ { printf "%.1f", $2 / 10; exit }'
  elif [[ -n ${GATE_ZONE:-} ]]; then
    adb shell "for z in /sys/class/thermal/thermal_zone*; do [ \"\$(cat \$z/type)\" = $GATE_ZONE ] && cat \$z/temp; done; true" |
      tr -d '\r' | awk 'NF { printf "%.1f", $1 / 1000; exit }'
  else
    adb shell dumpsys thermalservice | tr -d '\r' | grep -A12 "Current temperatures from HAL" | grep VIRTUAL-SKIN |
      sed -E 's/.*mValue=([0-9.]+).*/\1/' | head -1
  fi || true
}

wait_cool() {
  local t bad=0
  while :; do
    t=$(skin)
    if [[ ! $t =~ ^-?[0-9]+(\.[0-9]+)?$ ]]; then
      bad=$((bad + 1))
      echo "  gate: no temperature from ${GATE_ZONE:-VIRTUAL-SKIN} (got '$t'), try $bad of $GATE_TRIES" >&2
      if ((bad >= GATE_TRIES)); then
        echo "gate: no valid reading from ${GATE_ZONE:-VIRTUAL-SKIN} in $GATE_TRIES tries; check the device or GATE_ZONE" >&2
        exit 1
      fi
    elif awk -v t="$t" -v m="$GATE_MAX" 'BEGIN { exit !(t + 0 < m + 0) }'; then
      GATE_READING=$t
      return 0
    else
      bad=0
    fi
    sleep 30
  done
}

# adb cannot unlock: a run behind the keyguard draws nothing, so stop instead.
require_unlocked() {
  if [[ $(adb shell dumpsys window) == *isKeyguardShowing=true* ]]; then
    echo "device is locked (isKeyguardShowing=true); unlock it and rerun" >&2
    exit 1
  fi
}

begin_session() { adb shell svc power stayon true; }

end_session() {
  if [[ ${KEEP_AWAKE:-} == 1 ]]; then
    echo "KEEP_AWAKE=1: screen left on (stayon true)"
  else
    adb shell svc power stayon false
    adb shell input keyevent KEYCODE_SLEEP
  fi
}
