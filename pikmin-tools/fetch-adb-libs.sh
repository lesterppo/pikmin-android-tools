#!/usr/bin/env bash
# Stage the bundled adb client used by the local-ADB tier (v3.0+).
#
# The app execs a real `adb` binary from its native library dir, so the binary
# and its shared libraries must be present as lib*.so in app/src/main/jniLibs.
# They are NOT committed (12 MB of third-party binaries); run this once after
# cloning, before `build.sh`.
#
# Source: Termux `android-tools` 37.0.0p1 (AOSP adb 1.0.41, Apache-2.0) plus its
# runtime dependencies, all from the official Termux apt repository for
# aarch64. Nothing is fetched from anywhere else.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JNI="$HERE/app/src/main/jniLibs/arm64-v8a"
BASE="https://packages-cf.termux.dev/apt/termux-main"
WORK="${WORK:-$HERE/build/termux-adb}"

mkdir -p "$WORK" "$JNI"
cd "$WORK"

echo "[1/4] fetching Termux android-tools + deps"
PKGS="android-tools abseil-cpp brotli fmt libc++ liblz4 libprotobuf pcre2 zlib zstd"
curl -sS --max-time 60 -o Packages "$BASE/dists/stable/main/binary-aarch64/Packages"
for p in $PKGS; do
  f=$(grep -A20 "^Package: $p$" Packages | grep -m1 '^Filename:' | awk '{print $2}')
  [ -z "$f" ] && { echo "missing package: $p" >&2; exit 1; }
  out=$(basename "$f")
  [ -f "$out" ] || curl -sS --max-time 120 -o "$out" "$BASE/$f"
done

echo "[2/4] extracting"
rm -rf x; mkdir -p x
for d in *.deb; do dpkg-deb -x "$d" x 2>/dev/null || { ar x "$d" && tar xf data.tar.* -C x; }; done

echo "[3/4] computing the shared-library closure of adb"
rm -f "$JNI"/*.so
python3 - "$WORK" "$JNI" <<'PY'
import os, re, shutil, subprocess, sys
work, jni = sys.argv[1], sys.argv[2]
usr = os.path.join(work, "x/data/data/com.termux/files/usr")
adb = os.path.join(usr, "bin/adb")

def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return re.findall(r"Shared library: \[([^\]]+)\]", out)

def find(lib):
    p = os.path.join(usr, "lib", lib)
    return p if os.path.exists(p) else None

seen, stack = {}, needed(adb)
while stack:
    lib = stack.pop()
    if lib in seen:
        continue
    p = find(lib)
    if not p:
        continue
    seen[lib] = p
    stack += needed(p)

# exec target: adb itself, packaged under a lib*.so name
shutil.copy2(adb, os.path.join(jni, "libadb.so"))

# Gradle only packages names ending in .so, so rename the two versioned libs and
# patch the matching DT_NEEDED strings in place (equal length, size unchanged).
renames = {"libz.so.1": "libz.so", "libzstd.so.1": "libzstd.so"}
NUL = bytes([0])
patched = 0
for name, src in seen.items():
    dst_name = renames.get(name, name)
    dst = os.path.join(jni, dst_name)
    shutil.copy2(src, dst)
for fname in ("libadb.so", "libprotobuf.so"):
    path = os.path.join(jni, fname)
    d = bytearray(open(path, "rb").read()); L = len(d)
    for old, new in ((b"libzstd.so.1" + NUL, b"libzstd.so" + NUL * 3),
                     (b"libz.so.1" + NUL, b"libz.so" + NUL * 3)):
        i = bytes(d).find(old)
        if i >= 0:
            assert len(new) == len(old)
            d[i:i + len(old)] = new
            patched += 1
    assert len(d) == L
    open(path, "wb").write(bytes(d))
print(f"staged {len(os.listdir(jni))} files, patched {patched} DT_NEEDED entries")
PY

echo "[4/4] done: $JNI"
ls "$JNI" | head -3
echo "..."
du -sh "$JNI"
cat <<'NOTE'

Verify on a device (shell uid is enough for the smoke test):

  adb push app/src/main/jniLibs/arm64-v8a/. /data/local/tmp/tadb/
  adb shell 'cd /data/local/tmp/tadb && HOME=/data/local/tmp/home \
    LD_LIBRARY_PATH=/data/local/tmp/tadb ./libadb.so version'

Expect: "Android Debug Bridge version 1.0.41".
NOTE
