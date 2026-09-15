#!/bin/bash
# verify_gps.sh <lat> <lon> — pin a mock location with PikminBot Tools, then
# capture every Pikmin instance's map card so the place name can be compared.
#   verify_gps.sh 22.2783 114.1747
SDK=$HOME/android-sdk
ADB=$SDK/platform-tools/adb
ACT=com.nianticproject.ichigo.IchigoUnityPlayerActivity
MOCK=com.pikminbot.tools
LAT=${1:?lat}; LON=${2:?lon}
OUT=/tmp/gpscheck
mkdir -p $OUT

echo "== appop/selection"
$ADB shell settings get secure mock_location | tr -d '\r'
$ADB shell appops get $MOCK android:mock_location | head -1 | tr -d '\r'

echo "== (re)start pin service at $LAT,$LON  (fresh process clears the sticky persistent flag)"
$ADB shell am force-stop $MOCK
sleep 1
$ADB shell "am start-foreground-service -n $MOCK/.EngineService --es mode pin --ef lat $LAT --ef lon $LON --ez persistent true"
sleep 6
echo "== pin mode: HOLD (persistent) — a plain pin self-expires after 90 s and the phone silently returns to real GPS"
echo "== fused fix now:"
$ADB shell dumpsys location 2>/dev/null | grep -m1 'last location=Location\[fused' | sed 's/^ *//'
sleep 6
echo "== fused fix 6s later (et must advance):"
$ADB shell dumpsys location 2>/dev/null | grep -m1 'last location=Location\[fused' | sed 's/^ *//'
$ADB logcat -d -t 20 -s PikminBotTools 2>/dev/null | tail -2

for inst in orig 1 2 3; do
  case $inst in orig) pkg=com.nianticlabs.pikmin;; *) pkg=com.pikmin.c$inst;; esac
  echo "--------------------------------------------------"
  echo "== $inst ($pkg)"
  echo -n "   fused before launch: "; $ADB shell dumpsys location 2>/dev/null | grep -m1 'last location=Location\[fused' | sed 's/^ *//'
  $ADB shell am force-stop $pkg; sleep 2
  $ADB shell am start -n $pkg/$ACT >/dev/null 2>&1
  sleep 30
  $ADB exec-out screencap -p > $OUT/map_$inst.png
  convert $OUT/map_$inst.png -crop 960x520+60+200 +repage -resize 150% $OUT/card_$inst.png
  python3 - "$inst" <<'PY'
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)) if "__file__" in dir() else ".")
import ui
inst = sys.argv[1]
txt = [l["text"] for l in ui.lines(ui.ocr(f"/tmp/gpscheck/map_{inst}.png"))]
print("   placenames/top text:", " | ".join(txt[:12]))
PY
  $ADB shell am force-stop $pkg
done
montage $OUT/card_orig.png $OUT/card_1.png $OUT/card_2.png $OUT/card_3.png \
  -tile 2x2 -geometry +8+8 -background '#333' $OUT/cards.png
echo "montage: $OUT/cards.png"
