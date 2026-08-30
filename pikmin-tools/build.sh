#!/usr/bin/env bash
# Build pikmin-tools APK: Gradle release + page-aligned zipalign + sign with
# the canonical keystore (same key family as MockLoc so installs coexist).
# Requires: ~/android-sdk/{jdk17, build-tools, platforms} + gradle dist.
set -euo pipefail

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
JAVA="$SDK/jdk17"
BT="$SDK/build-tools/34.0.0"
GRADLE="${ANDROID_GRADLE:-$SDK/gradle/gradle-8.11.1/bin/gradle}"
KEYSTORE="${ANDROID_KEYSTORE:-$HOME/pikmin-bot/mockloc/keystore.jks}"
KEYALIAS="${ANDROID_KEYALIAS:-mockloc}"
KEYPASS="${ANDROID_KEYPASS:-pikminbot}"
KSPATH="${ANDROID_KSPATH:-$KEYPASS}"
HERE="$(cd "$(dirname "$0")" && pwd)"
VERSION="1.0"

export JAVA_HOME="$JAVA"
export PATH="$JAVA/bin:$PATH"

mkdir -p "$HERE/out" "$HERE/build" "$HERE/releases"

echo "[1/5] gradle assembleRelease"
"$GRADLE" -p "$HERE" assembleRelease --console=plain -q

APK_UNSIGNED="$HERE/app/build/outputs/apk/release/app-release-unsigned.apk"

echo "[2/5] zipalign -f -p 4 (Android 16 page-aligned .so requirement)"
"$BT/zipalign" -f -p 4 "$APK_UNSIGNED" "$HERE/build/aligned.apk"

echo "[3/5] sign with canonical keystore"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEYALIAS" \
    --ks-pass "pass:$KEYPASS" --key-pass "pass:$KSPATH" \
    --out "$HERE/out/pikmin-tools.apk" "$HERE/build/aligned.apk"

echo "[4/5] verify signature"
"$BT/apksigner" verify --print-certs "$HERE/out/pikmin-tools.apk" | head -2

echo "[5/5] manifest check"
"$BT/aapt2" dump badging "$HERE/out/pikmin-tools.apk" > "$HERE/build/badging.txt"
grep -E "^package|^sdkVersion|^targetSdkVersion" "$HERE/build/badging.txt"

cp "$HERE/out/pikmin-tools.apk" "$HERE/releases/pikmin-tools-v$VERSION.apk"
echo
echo "OK: $HERE/releases/pikmin-tools-v$VERSION.apk"
echo "Install: adb install -r releases/pikmin-tools-v$VERSION.apk"
