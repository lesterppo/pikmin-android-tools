#!/usr/bin/env bash
# Build pikmin-jogger APK: Gradle release + page-aligned zipalign + sign with
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
VERSION="1.2"

# aapt2/zipalign are native, but d8/apksigner/avdmanager are shell wrappers
# that need `java` resolvable on PATH.
export JAVA_HOME="$JAVA"
export PATH="$JAVA/bin:$PATH"

echo "[1/4] gradle assembleRelease"
"$GRADLE" -p "$HERE" assembleRelease --console=plain -q

APK_UNSIGNED="$HERE/app/build/outputs/apk/release/app-release-unsigned.apk"

echo "[2/4] zipalign -p 4 (Android 16 page-aligned .so requirement)"
"$BT/zipalign" -f -p 4 "$APK_UNSIGNED" "$HERE/build/aligned.apk"

echo "[3/4] sign with canonical keystore"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEYALIAS" \
    --ks-pass "pass:$KEYPASS" --key-pass "pass:$KSPATH" \
    --out "$HERE/out/pikmin-jogger.apk" "$HERE/build/aligned.apk"

echo "[4/4] verify"
"$BT/apksigner" verify --print-certs "$HERE/out/pikmin-jogger.apk" | head -2

cp "$HERE/out/pikmin-jogger.apk" "$HERE/../releases/pikmin-jogger-v$VERSION.apk"
echo
echo "OK: $HERE/out/pikmin-jogger.apk  (release copy: releases/pikmin-jogger-v$VERSION.apk)"
echo "Install: adb install -r out/pikmin-jogger.apk"
