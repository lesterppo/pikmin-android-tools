#!/usr/bin/env bash
# Build mockloc.apk with a user-local Android SDK (no root/sudo needed).
#
# PRIVACY: this script generates a throwaway, self-signed debug key on first
# run. The keystore password is intentionally NOT a real secret — replace it
# with your own value, or set ANDROID_KEYSTORE / ANDROID_KEYPASS to use an
# existing key. The keystore file is gitignored and never committed.
#
# Requires: ~/android-sdk/{jdk17, build-tools/34.0.0, platforms/android-34}
#   (point ANDROID_SDK elsewhere if your SDK lives somewhere else)
set -euo pipefail

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
JAVA="$SDK/jdk17/bin"
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

# --- Signing key (THROW-AWAY debug key, NOT a secret) ---
# Override with env vars to use your own keystore:
#   ANDROID_KEYSTORE=/path/to/key.jks  ANDROID_KEYALIAS=alias
#   ANDROID_KEYPASS=pass  ANDROID_KSPATH=pass
KEYSTORE="${ANDROID_KEYSTORE:-$HERE/keystore.jks}"
KEYALIAS="${ANDROID_KEYALIAS:-mockloc}"
KEYPASS="${ANDROID_KEYPASS:-changeit}"
KSPATH="${ANDROID_KSPATH:-$KEYPASS}"

for t in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$JAVA/javac" "$JAVA/keytool" "$PLATFORM"; do
  [ -e "$t" ] || { echo "missing: $t (point ANDROID_SDK at your SDK)"; exit 1; }
done

rm -rf build out
mkdir -p build/gen build/obj build/dex out

echo "[1/6] aapt2 compile + link"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" \
    --manifest AndroidManifest.xml -R build/res.zip \
    --auto-add-overlay --java build/gen

echo "[2/6] javac (Java 8 source/target, no lambdas)"
find java -name '*.java' > build/sources.txt
"$JAVA/javac" -source 1.8 -target 1.8 \
    -bootclasspath "$PLATFORM" \
    -classpath "build/gen" \
    -d build/obj @build/sources.txt

echo "[3/6] d8 dex"
find build/obj -name '*.class' > build/classes.txt
"$BT/d8" --release --lib "$PLATFORM" --output build/dex @build/classes.txt

echo "[4/6] merge dex into apk"
(cd build/dex && zip -q "$HERE/build/base.apk" classes.dex)

echo "[5/6] zipalign"
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

echo "[6/6] sign"
if [ ! -f "$KEYSTORE" ]; then
  "$JAVA/keytool" -genkeypair -keystore "$KEYSTORE" -alias "$KEYALIAS" \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$KEYPASS" -keypass "$KSPATH" \
      -dname "CN=MockLoc, OU=Bot, O=Bot, L=HK, ST=HK, C=HK" > /dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEYALIAS" \
    --ks-pass "pass:$KEYPASS" --key-pass "pass:$KSPATH" \
    --out out/mockloc.apk build/aligned.apk
"$BT/apksigner" verify --print-certs out/mockloc.apk | head -3

echo
echo "OK: $HERE/out/mockloc.apk"
echo "Install: adb install -r out/mockloc.apk"
