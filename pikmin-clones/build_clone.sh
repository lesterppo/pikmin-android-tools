#!/bin/bash
# build_clone.sh <name> — build a re-packaged clone of an installed app so that
# several accounts can stay signed in on ONE phone at the same time.
#
#   ./build_clone.sh c1        -> com.<prefix>.c1, label "<App label> c1"
#
# Prep once:
#   adb shell pm path <pkg>                       # base.apk + split_config.arm64_v8a.apk
#   adb pull <base> apks/base.apk
#   adb pull <split> apks/split_config.arm64_v8a.apk
#   java -jar apktool.jar d -f -o tree apks/base.apk     # decoded tree, reused for every clone
#   mkdir -p libs && (cd libs && unzip -o ../apks/split_config.arm64_v8a.apk 'lib/*')
#   python3 patch_sharedlogin.py tree                     # crash fix, see pikmin-clones/README.md
#
# Signing: one persistent key, passed through the environment (see README).
set -e
NAME=${1:?usage: build_clone.sh <name>}
: "${ANDROID_CLONE_PREFIX:?set ANDROID_CLONE_PREFIX (e.g. com.example.app)}"
: "${ANDROID_KEYSTORE:?set ANDROID_KEYSTORE}"; : "${ANDROID_KEYALIAS:?set ANDROID_KEYALIAS}"
: "${ANDROID_KEYPASS:?set ANDROID_KEYPASS}"
ROOT=$(cd "$(dirname "$0")" && pwd)
TREE=${ANDROID_TREE:-$ROOT/tree}          # decoded base tree (already patched)
SPLIT_LIBS=${ANDROID_SPLIT_LIBS:-$ROOT/libs/lib/arm64-v8a}
APP_LABEL=${ANDROID_APP_LABEL:-Pikmin Bloom}
ORIG_PKG=${ANDROID_ORIG_PKG:-com.nianticlabs.pikmin}
ORIG_PERM=${ANDROID_ORIG_PERM:-com.nianticlabs.platform.permission.LOGIN_PROVIDER}
SDK=${ANDROID_SDK:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
export JAVA_HOME=${JAVA_HOME:-$SDK/jdk17}
export PATH=$JAVA_HOME/bin:$BT:$SDK/platform-tools:$PATH
NEW="$ANDROID_CLONE_PREFIX.$NAME"
D=$ROOT/tree_$NAME
OUT=$ROOT/out; mkdir -p "$OUT"

echo "[1/6] copy decoded tree"
rm -rf "$D"; cp -a "$TREE" "$D"; cd "$D"

echo "[2/6] rename package $ORIG_PKG -> $NEW (TEXT files only, never binary assets)"
sed -i "s/${ORIG_PKG//./\\.}/$NEW/g" AndroidManifest.xml
# the app re-declares a signature-level permission owned by the ORIGINAL app;
# leaving it as-is fails installation with INSTALL_FAILED_DUPLICATE_PERMISSION
sed -i "s/${ORIG_PERM//./\\.}/${NEW}.permission.LOGIN_PROVIDER/g" AndroidManifest.xml
# the merged APK is no longer a split bundle
sed -i 's/ android:requiredSplitTypes="[^"]*"//; s/ android:splitTypes="[^"]*"//' AndroidManifest.xml
grep -rl "$ORIG_PKG" res/ assets/ 2>/dev/null | while read -r f; do
  case "$f" in
    *.xml|*.json|*.txt|*.properties|*.cfg|*.html|*.js)
      sed -i "s/${ORIG_PKG//./\\.}/$NEW/g" "$f" && echo "   patched $f" ;;
    *) echo "   SKIP binary $f (a shorter replacement would corrupt it)" ;;
  esac
done
sed -i "s|<string name=\"app_name\">[^<]*</string>|<string name=\"app_name\">$APP_LABEL $NAME</string>|" res/values/strings.xml

echo "[3/6] merge the split's native libs"
mkdir -p lib/arm64-v8a && cp -a "$SPLIT_LIBS/." lib/arm64-v8a/ && ls lib/arm64-v8a | wc -l

echo "[4/6] apktool build"
java -Xmx4g -jar "${APKTOOL_JAR:-$HOME/apktool.jar}" b -f --use-aapt2 -o "$OUT/$NAME-unsigned.apk" "$D" 2>&1 | tail -3

echo "[5/6] zipalign -p 4 (mandatory on 16 KB-page devices)"
$BT/zipalign -f -p 4 "$OUT/$NAME-unsigned.apk" "$OUT/$NAME-aligned.apk"

echo "[6/6] sign"
$BT/apksigner sign --ks "$ANDROID_KEYSTORE" --ks-key-alias "$ANDROID_KEYALIAS" \
  --ks-pass "pass:$ANDROID_KEYPASS" --key-pass "pass:$ANDROID_KEYPASS" \
  --ks-type "${ANDROID_KSPATH:-PKCS12}" --out "$OUT/$NAME.apk" "$OUT/$NAME-aligned.apk"
$BT/apksigner verify --print-certs "$OUT/$NAME.apk" | head -3
echo "DONE $OUT/$NAME.apk"
