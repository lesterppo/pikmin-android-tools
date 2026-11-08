#!/usr/bin/env bash
# ============================================================
# publish.sh - serve freshly rebuilt clone APKs to the
# "Pikmin Updater" Android app (From PC button).
#
#   1. build the clones (optional):  ./rebuild.sh
#   2. ./publish.sh [PORT]           -> http server + manifest
#   3. on the phone: Pikmin Updater -> From PC -> Update all
# ============================================================
set -euo pipefail
PORT="${1:-8942}"
OUT="$HOME/pikmin-clones/work/out"
[ -d "$OUT" ] || { echo "no $OUT"; exit 1; }

cd "$OUT"
for n in c1 c2 c3; do
  [ -f "$n.apk" ] || { echo "missing $n.apk in $OUT"; exit 1; }
done

VER=$(unzip -p c1.apk AndroidManifest.xml 2>/dev/null | strings | grep -oE '1[0-9]{2}\.[0-9]' | head -1 || true)
VER=${VER:-unknown}

cat > pikmin-manifest.json <<JSON
{
  "version": "$VER",
  "generated": "$(date -Iseconds)",
  "files": { "c1": "c1.apk", "c2": "c2.apk", "c3": "c3.apk" }
}
JSON

IP=$(ip route get 1.1.1.1 2>/dev/null | grep -oP 'src \K[0-9.]+' | head -1)
echo "serving $OUT on port $PORT"
echo "  pc address for the app: http://${IP:-<pc-ip>}:$PORT/"
echo "  manifest:               http://${IP:-<pc-ip>}:$PORT/pikmin-manifest.json"
exec python3 -m http.server "$PORT" --bind 0.0.0.0
