#!/usr/bin/env bash
# Test: does the on-device service keep the mock pinned (no computer loop)?
# Launches the app's MainActivity (foreground context) with autostart; the
# MockService then loops every ~1s internally. This is the real phone path.
export PATH="$HOME/android-sdk/platform-tools:$PATH"

PKG=com.pikminbot.mockloc
echo "=== clearing any prior service ==="
adb shell am broadcast -a com.pikminbot.mock.CLEAR_LOCATION -n $PKG/.MockLocationReceiver >/dev/null 2>&1
sleep 2

echo "=== start on-device (no computer loop) ==="
adb shell am start -n $PKG/.MainActivity --ef lat 39.033868 --ef lon 125.753338 --ez autostart true >/dev/null 2>&1

for i in $(seq 1 9); do
  sleep 10
  f=$(adb shell dumpsys location 2>&1 | grep -E 'last location=Location\[fused ' | head -1 | grep -oE 'fused [-0-9.]+,[-0-9.]+')
  echo "  t=$((i*10))s: $f"
done

echo "=== stop ==="
adb shell am broadcast -a com.pikminbot.mock.CLEAR_LOCATION -n $PKG/.MockLocationReceiver >/dev/null 2>&1
sleep 4
f=$(adb shell dumpsys location 2>&1 | grep -E 'last location=Location\[fused ' | head -1 | grep -oE 'fused [-0-9.]+,[-0-9.]+')
echo "  reverted: $f"
