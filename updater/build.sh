#!/usr/bin/env bash
# ============================================================
# Pikmin Updater - build script (pure CLI toolchain, no Gradle)
# One-tap updater for the re-signed Pikmin Bloom clones
# (com.pikmin.c1 / c2 / c3).
# Signs with ~/android-sdk/keystore.p12 (alias quotes).
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME=${JAVA_HOME:-$HOME/android-sdk/jdk17}
export PATH="$JAVA_HOME/bin:$PATH"
SDK=${ANDROID_SDK:-$HOME/android-sdk}
BT="$SDK/build-tools/34.0.0"
AAPT2="$BT/aapt2"
KEYSTORE=${KEYSTORE:-$HOME/android-sdk/keystore.p12}
KS_ALIAS=${KS_ALIAS:-quotes}
KS_PASS=${KS_PASS:-pikminbot}
ANDROID_JAR="$SDK/platforms/android-34/android.jar"

echo "== 1. aapt2 compile + link resources =="
rm -rf build && mkdir -p build/gen build/obj build/dex build/pkg
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -o build/app.unaligned.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version 26 --target-sdk-version 34 \
    build/res.zip

echo "== 2. javac =="
find java -name "*.java" > build/sources.txt
find build/gen -name R.java >> build/sources.txt
if ! javac -source 8 -target 8 -classpath "$ANDROID_JAR" \
    -d build/obj @build/sources.txt 2> build/javac.err; then
  grep -v "bootstrap class path" build/javac.err | grep -v "^Note:" | head -40
  echo "JAVAC FAILED - aborting build"
  exit 1
fi
grep -v "bootstrap class path" build/javac.err | grep -v "^Note:" | head -10 || true

echo "== 3. d8 dex =="
"$BT/d8" --lib "$ANDROID_JAR" --release --min-api 26 \
    --output build/dex $(find build/obj -name "*.class")

echo "== 4. package APK =="
cd build/pkg
unzip -o -q ../app.unaligned.apk
rm -f classes.dex classes2.dex
cp ../dex/classes.dex .
ls -la classes.dex
zip -0 -r -q ../app.withdex.apk .
cd ..
rm -rf pkg

echo "== 5. zipalign (page-align for Android 16) =="
"$BT/zipalign" -f -p 4 app.withdex.apk app.aligned.apk

echo "== 6. sign =="
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --out updater.apk app.aligned.apk

echo "== 7. verify =="
"$BT/apksigner" verify --print-certs updater.apk | head -4
"$AAPT2" dump badging updater.apk | grep -E "package:|application-label:|sdkVersion|targetSdk" | head

SZ=$(stat -c%s updater.apk 2>/dev/null || stat -f%z updater.apk)
echo ""
echo "OK: build/updater.apk ($((SZ/1024)) KB)"
