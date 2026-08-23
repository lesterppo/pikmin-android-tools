#!/usr/bin/env bash
# Test foreground-service mock persistence: single broadcast, watch 90s.
export PATH="$HOME/android-sdk/platform-tools:$PATH"
adb install -r out/mockloc.apk 2>&1 | tail -2
echo "=== single SET broadcast (foreground service should keep it alive) ==="
adb shell am broadcast -n com.pikminbot.mockloc/.MockLocationReceiver \
  -a com.pikminbot.mock.SET_LOCATION --ef lat 39.033868 --ef lon 125.753338 2>&1 | tail -1
sleep 2
echo "mockloc process running? $(adb shell ps -A 2>&1 | grep -c mockloc) instance(s)"
for i in 1 2 3 4 5 6 7 8 9; do
  fused=$(adb shell dumpsys location 2>&1 | grep -E 'last location=Location\[fused ' | head -1 | grep -oE 'fused [0-9]{2}\.[0-9]+' | head -1)
  echo "  t=$((i*10))s  $fused"
  sleep 10
done
echo "=== final mockloc process? ==="
adb shell ps -A 2>&1 | grep mockloc || echo "  (mockloc NOT running)"