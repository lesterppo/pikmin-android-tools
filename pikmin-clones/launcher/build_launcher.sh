#!/bin/bash
# build_launcher.sh — build the tiny "Pikmin Clones" launcher APK.
#
# The launcher lists every Pikmin Bloom instance on the device (personal user,
# Samsung dual-app clone profile, Secure Folder, ...) with LauncherApps and
# starts the one you tap. launcher.startMainActivity() works from an ordinary
# app — no root, no Shizuku — which matters because an app installed into a
# profile gets no launcher icon of its own.
#
# Signing: same persistent-key discipline as the other tools in this repo
# (adb install -r can then replace an older build in place):
#   export ANDROID_KEYSTORE=~/keys/release.jks ANDROID_KEYALIAS=mykey \
#          ANDROID_KEYPASS=... ANDROID_KSPATH=...
set -e
SDK=${ANDROID_SDK:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
PLATFORM=$SDK/platforms/android-34/android.jar
JAVA=${JAVA_HOME:-$SDK/jdk17}/bin/java
JAVAC=${JAVA_HOME:-$SDK/jdk17}/bin/javac
D=$(cd "$(dirname "$0")" && pwd)
OUT=$D/build
: "${ANDROID_KEYSTORE:?set ANDROID_KEYSTORE}"
: "${ANDROID_KEYALIAS:?set ANDROID_KEYALIAS}"
: "${ANDROID_KEYPASS:?set ANDROID_KEYPASS}"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/5] aapt2 link"
$BT/aapt2 link -o "$OUT/base.apk" --manifest "$D/AndroidManifest.xml" \
  -I "$PLATFORM" --min-sdk-version 28 --target-sdk-version 34

echo "[2/5] javac"
"$JAVAC" -source 1.8 -target 1.8 -nowarn -bootclasspath "$PLATFORM" -cp "$PLATFORM" \
  -d "$OUT/classes" "$D/MainActivity.java"

echo "[3/5] d8"
$BT/d8 --min-api 28 --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[4/5] package + zipalign (page-align native libs for 16 KB-page devices)"
cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unaligned.apk" classes.dex)
$BT/zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "[5/5] sign"
$BT/apksigner sign --ks "$ANDROID_KEYSTORE" --ks-key-alias "$ANDROID_KEYALIAS" \
  --ks-pass "pass:$ANDROID_KEYPASS" --key-pass "pass:$ANDROID_KEYPASS" \
  --ks-type "${ANDROID_KSPATH:-PKCS12}" --out "$D/pikmin-clones-launcher.apk" "$OUT/aligned.apk"
$BT/apksigner verify --print-certs "$D/pikmin-clones-launcher.apk" | head -2
echo "DONE $D/pikmin-clones-launcher.apk"
