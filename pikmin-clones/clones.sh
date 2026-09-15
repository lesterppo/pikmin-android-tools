#!/bin/bash
# clones.sh — manage multiple Pikmin Bloom instances (original + re-packaged
# clones + profile copies) and the two device-wide injectors from one place.
#
#   clones.sh status                 what is installed / running / granted
#   clones.sh launch <1|2|3|orig|profile|launcher>
#   clones.sh grant <1 2 3|all>      re-apply runtime permissions
#   clones.sh reset <n>              wipe a clone's data (sign in another account)
#   clones.sh pin <lat> <lon>        mock GPS HOLD pin  -> ALL instances at once
#   clones.sh pin90 <lat> <lon>      90 s natural-lifetime pin
#   clones.sh pinstop                release the pin (back to real GPS)
#   clones.sh steps <n> [min]        Health Connect steps -> ALL instances
#   clones.sh verify                 read back injected steps
#   clones.sh run [kph] [steps/s] [loop|straight] [radius]
#                                    jog: GPS movement + HC steps together
#   clones.sh stopall                stop jog/pin
#   clones.sh mockslot               re-assert the mock-location appop + selection
#
# Definitions (override with env vars):
#   ORIG     original package            default com.nianticlabs.pikmin
#   PREFIX   clone package prefix        default com.pikmin.c   (c1, c2, c3)
#   MOCK     mock-GPS app                default com.pikminbot.tools
#   HC       step injector app           default com.pikminbot.hcsteps
#   PUSER    Samsung dual-app profile    default 95
#   LCH      instance launcher app       default com.peter.pikminclones
set -u
SDK=${ANDROID_SDK:-$HOME/android-sdk}
ADB=${ADB:-$SDK/platform-tools/adb}
ACT=${ACT:-com.nianticproject.ichigo.IchigoUnityPlayerActivity}   # main activity
ORIG=${ORIG:-com.nianticlabs.pikmin}
PREFIX=${PREFIX:-com.pikmin.c}
MOCK=${MOCK:-com.pikminbot.tools}
HC=${HC:-com.pikminbot.hcsteps}
PUSER=${PUSER:-95}
LCH=${LCH:-com.peter.pikminclones}

cmd_status() {
  echo "== serial: $($ADB devices | awk 'NR==2{print $1}')"
  echo "== mock slot: $($ADB shell settings get secure mock_location | tr -d '\r')"
  echo "== mock appop: $($ADB shell appops get "$MOCK" android:mock_location 2>/dev/null | head -1 | tr -d '\r')"
  echo "== fused fix: $(fused)"
  echo "== instances:"
  for p in "$ORIG" "$PREFIX"1 "$PREFIX"2 "$PREFIX"3; do
    v=$($ADB shell dumpsys package "$p" 2>/dev/null | grep -m1 versionName | tr -d ' \r')
    r=$($ADB shell ps -A 2>/dev/null | grep -c "$p\$")
    [ -n "$v" ] && echo "   $p  $v  procs=$r"
  done
  echo "   $ORIG in profile user $PUSER: procs=$($ADB shell ps -A 2>/dev/null | grep -c "$ORIG\$")"
  echo "== HC steps:"
  cmd_verify
}

cmd_launch() {
  case "$1" in
    profile)  $ADB shell am start --user "$PUSER" -n "$ORIG/$ACT" >/dev/null 2>&1; echo "launched original in profile user $PUSER";;
    orig)     $ADB shell am start -n "$ORIG/$ACT" >/dev/null 2>&1; echo "launched original";;
    launcher) $ADB shell am start -n "$LCH"/.MainActivity >/dev/null 2>&1; echo "launched instance picker";;
    [1-9])    $ADB shell am start -n "$PREFIX$1/$ACT" >/dev/null 2>&1; echo "launched clone $1";;
    *) echo "usage: clones.sh launch <1|2|3|orig|profile|launcher>";;
  esac
}

cmd_grant() {
  local list="$1"
  [ "$list" = all ] && list="1 2 3"
  for n in $list; do
    p=$PREFIX$n
    for perm in android.permission.health.READ_STEPS \
                android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND \
                android.permission.ACTIVITY_RECOGNITION \
                android.permission.ACCESS_FINE_LOCATION \
                android.permission.ACCESS_COARSE_LOCATION \
                android.permission.ACCESS_BACKGROUND_LOCATION \
                android.permission.CAMERA \
                android.permission.POST_NOTIFICATIONS; do
      $ADB shell pm grant "$p" "$perm" >/dev/null 2>&1
    done
    echo "granted: $p"
  done
}

cmd_reset() {
  local p="$PREFIX$1"
  $ADB shell am force-stop "$p"
  $ADB shell pm clear "$p" | tail -1
  cmd_grant "$1"
  echo "reset $p — relaunch and sign in with another account"
}

# --- injectors -----------------------------------------------------------------
# Both act at USER level (user 0), so every instance of the app in that user is
# affected by ONE pin / ONE step write at the same time.
mock_slot() {
  $ADB shell appops set "$MOCK" android:mock_location allow >/dev/null 2>&1
  $ADB shell settings put secure mock_location "$MOCK" >/dev/null 2>&1
  echo "mock slot re-asserted: $MOCK"
}

fused() { $ADB shell dumpsys location 2>/dev/null | grep -m1 'last location=Location\[fused' | sed 's/^ *//'; }

cmd_pin() {   # HOLD pin: stays until stopped
  $ADB shell am force-stop "$MOCK"; sleep 1
  $ADB shell "am start-foreground-service -n $MOCK/.EngineService --es mode pin --ef lat $1 --ef lon $2 --ez persistent true" >/dev/null 2>&1
  sleep 5; mock_slot >/dev/null; echo "fused: $(fused)"
}

cmd_pin90() { # natural-lifetime pin: re-pins ~900 ms, auto-releases after 90 s
  $ADB shell am force-stop "$MOCK"; sleep 1
  $ADB shell "am start-foreground-service -n $MOCK/.EngineService --es mode pin --ef lat $1 --ef lon $2" >/dev/null 2>&1
  sleep 5; mock_slot >/dev/null; echo "fused: $(fused)"
}

cmd_pinstop() { $ADB shell am force-stop "$MOCK"; sleep 2; echo "fused: $(fused)"; }

cmd_steps() {  # count minutes chunk
  local count=${1:-600} minutes=${2:-10} chunk=${3:-10}
  $ADB shell am broadcast -n "$HC"/.StepInjectReceiver -a com.pikminbot.INJECT_STEPS \
    --ei count "$count" --ei minutes "$minutes" --ei chunk_minutes "$chunk" | tail -1
  sleep 3
  $ADB logcat -d -s HCStepWriter:* 2>/dev/null | grep -E 'TOTAL' | tail -1
}

cmd_verify() {
  $ADB shell am start -n "$HC"/.MainActivity --ez verify true >/dev/null 2>&1
  sleep 4
  $ADB logcat -d -s HCStepWriter:* 2>/dev/null | grep -E 'TOTAL|VERIFY' | tail -2
}

cmd_run() {    # jog: movement + HC steps in one engine
  local kph=${1:-6} sps=${2:-2} mode=${3:-loop} radius=${4:-100}
  local lat=${5:-22.3193} lon=${6:-114.1694}
  $ADB shell am force-stop "$MOCK"; sleep 1
  $ADB shell "am start-foreground-service -n $MOCK/.EngineService --es mode jog --es jogmode $mode --ef speed_kph $kph --ef steps_per_sec $sps --ei radius_m $radius --ef lat $lat --ef lon $lon" >/dev/null 2>&1
  sleep 6; mock_slot >/dev/null; echo "fused: $(fused)"
}

cmd_stopall() { $ADB shell am force-stop "$MOCK"; echo "jog/pin stopped"; }

case "${1:-status}" in
  status) cmd_status;;
  launch) cmd_launch "${2:-launcher}";;
  grant) cmd_grant "${2:-all}";;
  reset) cmd_reset "${2:?clone number}";;
  pin) cmd_pin "${2:?lat}" "${3:?lon}";;
  pin90) cmd_pin90 "${2:?lat}" "${3:?lon}";;
  pinstop) cmd_pinstop;;
  steps) cmd_steps "${2:-600}" "${3:-10}" "${4:-10}";;
  verify) cmd_verify;;
  run) cmd_run "${2:-6}" "${3:-2}" "${4:-loop}" "${5:-100}" "${6:-}" "${7:-}";;
  stopall) cmd_stopall;;
  mockslot) mock_slot;;
  *) sed -n '2,30p' "$0";;
esac
