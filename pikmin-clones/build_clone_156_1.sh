#!/bin/bash
# build_clone_VER.sh <name>  e.g.  build_clone_156_1.sh c1
# Builds a standalone clone of Pikmin Bloom 156.1 (pulled base + arm64 split)
# under package com.pikmin.<name>, merged libs, own signature.
set -e
NAME="$1"
[ -z "$NAME" ] && { echo "usage: $0 <name>"; exit 1; }
NEW="com.pikmin.$NAME"
LABEL="Pikmin Bloom $NAME"
export JAVA_HOME=~/android-sdk/jdk17
export PATH=$JAVA_HOME/bin:~/android-sdk/build-tools/34.0.0:~/android-sdk/platform-tools:$PATH
W=~/pikmin-clones/work
SRC=$W/base_156_1
ARM=$W/arm64_156_1
D=$W/src_$NAME
OUT=$W/out
mkdir -p $OUT

echo "[1/6] copy decoded tree"
rm -rf "$D"; cp -a "$SRC" "$D"

cd "$D"
echo "[2/6] rename package com.nianticlabs.pikmin -> $NEW"
sed -i "s/com\.nianticlabs\.pikmin/$NEW/g" AndroidManifest.xml
sed -i "s/com\.nianticlabs\.platform\.permission\.LOGIN_PROVIDER/$NEW.permission.LOGIN_PROVIDER/g" AndroidManifest.xml
sed -i 's/ android:requiredSplitTypes="[^"]*"//; s/ android:splitTypes="[^"]*"//' AndroidManifest.xml
grep -rl "com\.nianticlabs\.pikmin" res/ assets/ 2>/dev/null | while read f; do
  case "$f" in
    *.xml|*.json|*.txt|*.properties|*.cfg|*.html|*.js|*.plist)
      sed -i "s/com\.nianticlabs\.pikmin/$NEW/g" "$f" && echo "   patched $f" ;;
    *) echo "   SKIP binary $f" ;;
  esac
done
sed -i "s|<string name=\"app_name\">[^<]*</string>|<string name=\"app_name\">$LABEL</string>|" res/values/strings.xml
grep -m1 'name="app_name"' res/values/strings.xml

echo "[3/6] merge arm64 native libs"
mkdir -p lib/arm64-v8a
cp -a $ARM/lib/arm64-v8a/. lib/arm64-v8a/
ls lib/arm64-v8a | wc -l

echo "[4/6] apktool build"
java -Xmx3g -jar ~/apktool.jar b -f --use-aapt2 -o "$OUT/$NAME-unsigned.apk" "$D" 2>&1 | tail -3

echo "[5/6] neutralize split flags in binary manifest (if present)"
python3 - "$OUT/$NAME-unsigned.apk" <<'PY'
import sys, subprocess
apk = sys.argv[1]
out = subprocess.run(['/usr/bin/env','bash','-c',
   f"~/android-sdk/build-tools/34.0.0/aapt2 dump xmltree {apk} --file AndroidManifest.xml"],
   capture_output=True, text=True).stdout
flags = [l.strip() for l in out.splitlines() if 'isSplitRequired' in l or 'requiredSplitTypes' in l or 'splitTypes' in l]
print('   manifest split attrs:', flags if flags else 'none')
if any('isSplitRequired' in f and 'true' in f for f in flags):
    import os
    sys.path.insert(0, os.path.expanduser('~/pikmin-dev'))
    import fix_split_flags3
    fix_split_flags3.main(apk)
    print('   applied fix_split_flags3')
PY

echo "[6/6] zipalign + sign"
zipalign -f -p 4 "$OUT/$NAME-unsigned.apk" "$OUT/$NAME-aligned.apk"
apksigner sign --ks ~/android-sdk/keystore.p12 --ks-key-alias quotes \
  --ks-pass pass:pikminbot --key-pass pass:pikminbot --ks-type PKCS12 \
  --out "$OUT/$NAME.apk" "$OUT/$NAME-aligned.apk"
apksigner verify --print-certs "$OUT/$NAME.apk" | head -3
echo "DONE $OUT/$NAME.apk $(du -h $OUT/$NAME.apk | cut -f1)"
