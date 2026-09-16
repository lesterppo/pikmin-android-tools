#!/usr/bin/env bash
# Install the PikminBot Repair desktop app for the CURRENT user.
#
#   ./install.sh            install / upgrade
#   ./install.sh --uninstall
#
# Installs:
#   ~/.local/share/pikmin-repair/pikmin-repair.py   the app
#   ~/.local/bin/pikmin-repair                      launcher (in PATH)
#   ~/.local/share/applications/pikmin-repair.desktop  app-menu entry
#   ~/.local/share/icons/hicolor/scalable/apps/pikmin-repair.svg  icon
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APPDIR="$HOME/.local/share/pikmin-repair"
BIN="$HOME/.local/bin/pikmin-repair"
DESKTOP_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/scalable/apps"

if [ "${1:-}" = "--uninstall" ]; then
  rm -rf "$APPDIR" "$BIN" "$DESKTOP_DIR/pikmin-repair.desktop" "$ICON_DIR/pikmin-repair.svg"
  command -v update-desktop-database >/dev/null && update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
  echo "uninstalled."
  exit 0
fi

command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }
python3 -c "import tkinter" 2>/dev/null || {
  echo "python3-tk is required. Install it with:  sudo apt install python3-tk"; exit 1; }
if ! command -v adb >/dev/null && [ ! -x "${ANDROID_SDK:-$HOME/android-sdk}/platform-tools/adb" ]; then
  echo "note: adb not on PATH and not in \$ANDROID_SDK — the app will look for it in"
  echo "      ~/android-sdk/platform-tools/adb; set ADB_BIN to override."
fi

mkdir -p "$APPDIR" "$(dirname "$BIN")" "$DESKTOP_DIR" "$ICON_DIR"
install -m 0755 "$HERE/pikmin-repair.py" "$APPDIR/pikmin-repair.py"
install -m 0644 "$HERE/pikmin-repair.svg" "$ICON_DIR/pikmin-repair.svg"

cat > "$BIN" <<EOF
#!/usr/bin/env bash
exec python3 "$APPDIR/pikmin-repair.py" "\$@"
EOF
chmod 0755 "$BIN"

cat > "$DESKTOP_DIR/pikmin-repair.desktop" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=PikminBot Repair
GenericName=Mock location repair
Comment=One-click repair of the PikminBot Tools mock location slot over wireless ADB
Exec=$BIN
Icon=pikmin-repair
Terminal=false
Categories=Utility;Development;
Keywords=pikmin;mock;location;gps;adb;repair;android;wireless;
StartupWMClass=pikmin-repair
EOF
chmod 0644 "$DESKTOP_DIR/pikmin-repair.desktop"

command -v update-desktop-database >/dev/null && update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

echo "installed:"
echo "  app        : $APPDIR/pikmin-repair.py"
echo "  launcher   : $BIN   (also in your app menu as \"PikminBot Repair\")"
echo
echo "run it:      pikmin-repair            # GUI"
echo "             pikmin-repair --repair   # headless one-click repair"
echo
echo "First time only: the PC must be paired with the phone's wireless debugging."
echo "Use the app's \"Pair new device\" button with the IP:PORT + code shown under"
echo "Developer options - Wireless debugging - Pair device with pairing code."
