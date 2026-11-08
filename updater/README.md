# Pikmin Updater

One-tap updater for the re-signed Pikmin Bloom clones (`com.pikmin.c1`, `com.pikmin.c2`,
`com.pikmin.c3`).

The clones carry the same signing identity across versions, so an update installs **in
place**: accounts, 起司 progress and settings survive. Without a helper you would have to
`adb install -r` each clone on each phone; with this app it is one button and then one
system confirm per clone.

```
┌─────────────────────────────────┐
│ Pikmin Updater                  │
│ Pikmin Bloom C1  155.0 → 156.1  │
│ Pikmin Bloom C2  155.0 → 156.1  │
│ Pikmin Bloom C3  155.0 → 156.1  │
│        [ UPDATE ALL (3) ]       │
│  [ADD APK] [FROM PC] [RESCAN]   │
└─────────────────────────────────┘
```

## How the APKs get onto the phone

Three ways, pick one:

| Method | Steps |
|---|---|
| **adb push** (fastest, from a PC) | `adb push c1.apk c2.apk c3.apk /sdcard/Android/data/com.pikminbot.updater/files/apks/` |
| **Add APK** (no PC) | copy the APKs to the phone (USB / Drive / chat), tap `ADD APK`, pick them |
| **From PC** (LAN) | on the PC: `cd pikmin-clones && ./publish.sh`, then in the app set the PC address and tap `FROM PC` |

> **Order matters for adb push:** launch the app once *before* pushing. If adb creates
> `/sdcard/Android/data/com.pikminbot.updater/files/` itself, the directory is owned by
> `shell` and the app cannot list it — the app then shows "No APK files visible" and
> silently falls back to internal storage. Launch the app first (it creates the folder as
> its own uid), then push.

## Updating

1. Put the APKs on the phone by one of the methods above.
2. Open **Pikmin Updater** — each row shows `installed → available`.
3. Tap **UPDATE ALL**. The app copies each APK into a `PackageInstaller` session and the
   system asks once per app: tap **更新 / Update**.
4. When it finishes the rows turn green (`up to date`).

APKs are matched by package name (`getPackageArchiveInfo`), never by file name, and the
highest `versionCode` wins, so a folder full of old builds is harmless. A row is only
queued when `apkVersionCode > installedVersionCode`.

## After an update

`ACCESS_BACKGROUND_LOCATION` can drop back to denied for a clone (Android's one-time
grant). If a clone shows the red "background location not allowed" banner:

```bash
adb shell pm grant com.pikmin.c1 android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant com.pikmin.c2 android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant com.pikmin.c3 android.permission.ACCESS_BACKGROUND_LOCATION
```

or allow it in the app's permission screen (Location → Allow all the time).

## Install

```bash
adb install -r releases/pikmin-updater-v1.0.apk
# one-time: let the app start installs (or accept the prompt on first use)
adb shell appops set com.pikminbot.updater REQUEST_INSTALL_PACKAGES allow
```

Android 8.0+ (minSdk 26). No root, no Shizuku.

## Build

```bash
cd updater
./build.sh          # -> build/updater.apk
```

Pure CLI toolchain (aapt2 → javac → d8 → zipalign -p 4 → apksigner), signed with the
shared `~/android-sdk/keystore.p12` (alias `quotes`). `-p 4` page alignment is required on
Android 15/16 devices.

## Why not a silent installer

Silent `pm install` needs shell/root. Shizuku can do it, but it has to be running
(started over adb or by the Shizuku app after every reboot), and a stale Shizuku is a
worse failure than three taps. The app therefore sticks to `PackageInstaller`, which works
everywhere.

## Related

- [Pikmin Clones](../pikmin-clones) — building the clones (`build_clone_*.sh`)
- [PikminBot Tools](../pikmin-tools) — mock GPS + jogger
