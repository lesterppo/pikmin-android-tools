#!/bin/bash
# verify_steps.sh — prove the step injector reaches EVERY instance.
# Injects Health Connect steps, then relaunches each instance and screenshots
# its step counter (步數) into one montage you can compare at a glance.
#   verify_steps.sh [count] [minutes]
SDK=${ANDROID_SDK:-$HOME/android-sdk}
ADB=${ADB:-$SDK/platform-tools/adb}
ACT=${ACT:-com.nianticproject.ichigo.IchigoUnityPlayerActivity}
ORIG=${ORIG:-com.nianticlabs.pikmin}
PREFIX=${PREFIX:-com.pikmin.c}
HC=${HC:-com.pikminbot.hcsteps}
OUT=/tmp/stepcheck
COUNT=${1:-3000}; MIN=${2:-20}
mkdir -p "$OUT"

echo "== injecting $COUNT steps into Health Connect (one write, every user-0 instance reads it)"
$ADB shell am broadcast -n "$HC"/.StepInjectReceiver -a com.pikminbot.INJECT_STEPS \
  --ei count "$COUNT" --ei minutes "$MIN" --ei chunk_minutes 10 | tail -1
sleep 4
$ADB logcat -d -s HCStepWriter:* 2>/dev/null | grep -E 'TOTAL' | tail -1

for inst in orig 1 2 3; do
  case $inst in orig) pkg=$ORIG;; *) pkg=$PREFIX$inst;; esac
  echo "== $inst ($pkg)"
  $ADB shell am force-stop "$pkg"; sleep 2
  $ADB shell am start -n "$pkg/$ACT" >/dev/null 2>&1
  sleep 32
  $ADB exec-out screencap -p > "$OUT/full_$inst.png"
  convert "$OUT/full_$inst.png" -resize 38% "$OUT/s_$inst.png"
  $ADB shell am force-stop "$pkg"
done

montage "$OUT"/s_orig.png "$OUT"/s_1.png "$OUT"/s_2.png "$OUT"/s_3.png \
  -tile 2x2 -geometry +8+8 -background '#333' -label '%f' "$OUT/grid.png"
echo "montage: $OUT/grid.png  (top-left=orig, top-right=c1, bottom-left=c2, bottom-right=c3)"
