#!/usr/bin/env bash
# fix-mock-slot.sh — restore the MOCK_LOCATION slot after the phone's
# Developer options are switched off and on again.
#
# WHY: that toggle clears Android's "Select mock location app" slot, which puts
# the MOCK_LOCATION appop back to its default (deny). The stored
# `mock_location` setting can still name the app, so Settings looks correct
# while every injection throws SecurityException. Only a shell (adb / Shizuku /
# root) can flip the appop — an app can never allow its own op.
#
# Usage:
#   ./fix-mock-slot.sh                # auto-detect device, repair, verify
#   ./fix-mock-slot.sh -s <serial>
#   MOCK=com.pikminbot.tools ./fix-mock-slot.sh
#   ./fix-mock-slot.sh --restart-pin 22.3193 114.1694   # repair + re-arm engine
set -uo pipefail

MOCK="${MOCK:-com.pikminbot.tools}"
ADB="${ADB:-${ANDROID_SDK:-$HOME/android-sdk}/platform-tools/adb}"
SERIAL="${SERIAL:-}"
RESTART_LAT=""; RESTART_LON=""

while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2;;
    --restart-pin) RESTART_LAT="$2"; RESTART_LON="$3"; shift 3;;
    *) echo "unknown arg: $1"; exit 2;;
  esac
done

ADB_ARGS=()
[ -n "$SERIAL" ] && ADB_ARGS=(-s "$SERIAL")

devices=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
if [ -z "$SERIAL" ]; then
  n=$(printf '%s\n' "$devices" | grep -c . || true)
  if [ "$n" -eq 0 ]; then
    echo "no device: connect wireless debugging (adb connect <ip>:<port>) first"
    exit 1
  fi
  SERIAL=$(printf '%s\n' "$devices" | head -1)
  ADB_ARGS=(-s "$SERIAL")
  [ "$n" -gt 1 ] && echo "note: multiple devices, using $SERIAL"
fi

sh() { "$ADB" "${ADB_ARGS[@]}" shell "$@"; }

echo "device: $SERIAL"
echo "before: $(sh "appops get $MOCK android:mock_location | head -1") | selection=$(sh "settings get secure mock_location")"

sh "appops set $MOCK android:mock_location allow" >/dev/null
sh "settings put secure mock_location $MOCK" >/dev/null
sleep 1

after_op=$(sh "appops get $MOCK android:mock_location | head -1")
after_sel=$(sh "settings get secure mock_location")
echo "after:  $after_op | selection=$after_sel"

case "$after_op" in
  *allow*) ;;
  *) echo "FAIL: appop not allowed"; exit 1;;
esac
case "$after_sel" in
  "$MOCK") ;;
  *) echo "FAIL: selection is '$after_sel', not $MOCK"; exit 1;;
esac

if [ -n "$RESTART_LAT" ]; then
  echo "re-arming engine pin at $RESTART_LAT, $RESTART_LON"
  sh "am start-foreground-service -n $MOCK/.EngineService --es mode pin --ef lat $RESTART_LAT --ef lon $RESTART_LON --ez persistent true" >/dev/null
  sleep 4
  sh "dumpsys location 2>/dev/null | grep -m1 'gps provider \[mock\]' -A3"
fi

echo "OK: mock location slot restored (app self-heals itself once the slot is back)"
