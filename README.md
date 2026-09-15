# Pikmin Android Tools

[![License: MIT](https://img.shields.io/github/license/lesterppo/pikmin-android-tools)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://github.com/lesterppo/pikmin-android-tools/releases)
[![MockLoc v1.10](https://img.shields.io/badge/MockLoc-v1.10-4caf50)](https://github.com/lesterppo/pikmin-android-tools/releases/tag/mockloc-v1.10)
[![HC Step Injector v2.1](https://img.shields.io/badge/HC%20Step%20Injector-v2.1-2196f3)](https://github.com/lesterppo/pikmin-android-tools/releases/tag/hc-step-injector-v2.1)
[![Jogger v1.2](https://img.shields.io/badge/Jogger-v1.2-ff9800)](https://github.com/lesterppo/pikmin-android-tools/releases/tag/pikmin-jogger-v1.2)
[![PikminBot Tools v2.2](https://img.shields.io/badge/PikminBot%20Tools-v2.2-9c27b0)](https://github.com/lesterppo/pikmin-android-tools/releases/tag/pikmin-tools-v2.2)
[![Pikmin Clones v1.0](https://img.shields.io/badge/Pikmin%20Clones-v1.0-795548)](https://github.com/lesterppo/pikmin-android-tools/releases/tag/pikmin-clones-launcher-v1.0)

Open-source Android companion apps for **Pikmin Bloom**: a **mock GPS
location** app with a built-in map (MockLoc), a **Health Connect + Google Fit
step injector** (HC Step Injector), a **jogging simulator** that moves your GPS
position and streams steps at a configurable pace (Jogger), a **combined app**
that drives one engine from either UI (PikminBot Tools), and **Pikmin Clones** —
tooling plus a picker app for running **several Pikmin Bloom accounts on the
same phone**, with one pin and one step write reaching every copy at once.

All three apps ship as pre-built, signed APKs you can install directly with
`adb`, work without root, and are driven by both a small UI and headless
`adb shell am` commands for automation. Built with Java (`aapt2`/`d8`) and
Kotlin (Gradle), released under the MIT license.

## Table of contents

- [Downloads](#downloads)
- [1. MockLoc — mock GPS location with map](#1-mockloc--mock-gps-location-with-map)
- [2. HC Step Injector — Health Connect & Google Fit steps](#2-hc-step-injector--health-connect--google-fit-steps)
- [3. Jogger — jogging simulator](#3-jogger--jogging-simulator)
- [4. Pikmin Clones — several accounts on one phone](#4-pikmin-clones--several-accounts-on-one-phone)
- [FAQ](#faq)
- [Repository layout](#repository-layout)
- [License](#license)

## Downloads

| Tool | Package | Latest APK |
|------|---------|------------|
| **MockLoc** | `com.pikminbot.mockloc` | [mockloc-v1.10.apk](https://github.com/lesterppo/pikmin-android-tools/releases/tag/mockloc-v1.10) |
| **HC Step Injector** | `com.pikminbot.hcsteps` | [hc-step-injector-v2.1.apk](https://github.com/lesterppo/pikmin-android-tools/releases/tag/hc-step-injector-v2.1) |
| **Jogger** | `com.pikminbot.jogger` | [pikmin-jogger-v1.2.apk](https://github.com/lesterppo/pikmin-android-tools/releases/tag/pikmin-jogger-v1.2) |
| **PikminBot Tools** (MockLoc + Jogger in one app, one engine) | `com.pikminbot.tools` | [pikmin-tools-v2.2.apk](https://github.com/lesterppo/pikmin-android-tools/releases/tag/pikmin-tools-v2.2) |
| **Pikmin Clones** (instance picker for profile copies) | `com.peter.pikminclones` | [pikmin-clones-launcher-v1.0.apk](https://github.com/lesterppo/pikmin-android-tools/releases/tag/pikmin-clones-launcher-v1.0) |

Install any of them with:

```bash
adb install -r releases/<file>.apk
```

> **Terms-of-service notice**
> These are mock/spoof tools. Using them may violate Pikmin Bloom / Niantic
> Terms of Service. Use at your own risk.

---

## 1. MockLoc — mock GPS location with map

On-device mock GPS provider for Pikmin Bloom with an embedded Leaflet map
(Esri World Street Map tiles, no API key). Tap a spot on the map, flip the
switch, and the device reports that location to any app — including Pikmin
Bloom. No computer needed.

- **minSdk 21 (Android 5.0+)** · targetSdk 34 · Java, `aapt2`/`d8` build
- Foreground service re-pins the fix every ~0.9 s so it survives Android 12+
- **v1.7+ natural lifetime**: 90 s injection window, then release — the
  mushroom-friendly behaviour that avoids Niantic rejecting gameplay actions
- **v1.10**: coordinates outside `lat -90..90 / lon -180..180` are rejected
  with a clear message
- Persistent map-coverage mode (`persistent=true`) available for scripts

### Install

```bash
adb install -r releases/mockloc-v1.10.apk
```

### One-time phone setup (Developer Options)

1. Enable **Developer options** (tap Build Number 7×).
2. **Developer options → Select mock location app → PikminBot MockLoc**.
3. Launch the app once and grant the **Location** permission (and
   **Notifications** for the foreground-service notice).

> The app requests the location permission at first launch; the mock-location
> app selection must be done manually in Developer Options (Android requires it).

### Use

- Open **PikminBot MockLoc**.
- Pan/zoom the map (Esri World Street Map tiles — no API key needed).
- **Tap** anywhere to drop a marker → coordinates fill in.
- Flip the **Inject** switch ON. A foreground service refreshes the fix every
  ~0.9 s so it survives on Android 12+.
- Open Pikmin Bloom — it now reads the pinned location.
- Far-away points are valid to inject but Niantic clamps unrealistic
  teleports — use points near your real location.

### Build from source

```bash
cd mockloc
ANDROID_SDK=$HOME/android-sdk bash build.sh     # needs build-tools 34.0.0 + android-34
adb install -r out/mockloc.apk
```

---

## 2. HC Step Injector — Health Connect & Google Fit steps

Writes step deltas into **Health Connect** (which Pikmin Bloom reads through
the Fit SDK) and, optionally, **Google Fit cloud**. Dual-pathway = writes to
**both** sinks in one shot.

- **minSdk 28 (Android 9.0+)** · targetSdk 34 · Kotlin/Gradle
- AndroidX Health Connect client with the `VIEW_PERMISSION_USAGE` activity
  Android requires for the read API
- Headless: `adb shell am broadcast` with `--ei count/minutes/chunk_minutes`
- Read-back and delete own records

### Install

```bash
adb install -r releases/hc-step-injector-v2.1.apk
```

### One-time setup

1. Install **Health Connect** from the Play Store (if not preinstalled).
2. Open HC Step Injector → grant **Health Connect → Steps (read & write)**.
3. *(Google Fit cloud path only — optional)* push your own OAuth token file:
   ```bash
   adb push fit_token.json /sdcard/Android/data/com.pikminbot.hcsteps/files/fit_token.json
   ```
   `fit_token.json` is created with **your own** Google Cloud Desktop OAuth
   client:
   ```json
   {"refresh_token":"...","client_id":"...","client_secret":"...","token_uri":"https://oauth2.googleapis.com/token"}
   ```
   Without it, the Health Connect path still works fully standalone.

### Use

- UI: pick a step count / duration / chunk size, tap **Inject**.
- Headless / automation (explicit component — required on Android 14+):
  ```bash
  adb shell am broadcast -n com.pikminbot.hcsteps/.StepInjectReceiver \
    -a com.pikminbot.INJECT_STEPS \
    --ei count 600 --ei minutes 15 --ei chunk_minutes 5
  ```

### Build from source

Standard Android Gradle project (`hc-step-injector/`). Open in Android Studio
or build with `./gradlew assembleRelease` (needs the Android SDK + JDK 17).

---

## 3. Jogger — jogging simulator

Simulates a real jog for Pikmin Bloom:

- **Movement**: the mock GPS position moves at a **configurable speed**
  (default 10 km/h, range 0.5-30 km/h), re-injected every second. Two route
  modes: **Loop** (circles around the start point, default radius 100 m) or
  **Straight** (constant **heading 0-360°**: 0=N, 90=E, 180=S, 270=W).
- **Steps**: a **configurable rate** (default 2 steps/second, range 0-10)
  is written to Health Connect in 30 s batches with proper timestamps, so the
  step counter climbs like a real jog (2/s ≈ 120 steps/min ≈ 7,200/hour).
- Movement and steps are computed from **real elapsed time**, so the measured
  speed matches the configured value even when the system is busy.
- A foreground service keeps running with the screen off; it stops
  automatically after the chosen duration (1-240 min).

- **minSdk 28 (Android 9.0+)** · targetSdk 34 · Kotlin/Gradle, exported
  foreground service (`foregroundServiceType="location"`)

### Install + one-time setup

```bash
adb install -r releases/pikmin-jogger-v1.2.apk

# 1. Select it as the mock location app (takes over from MockLoc — only one
#    app can be the mock provider at a time):
adb shell settings put secure mock_location com.pikminbot.jogger
adb shell appops set com.pikminbot.jogger android:mock_location allow

# 2. Grant permissions:
adb shell pm grant com.pikminbot.jogger android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.pikminbot.jogger android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.pikminbot.jogger android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant com.pikminbot.jogger android.permission.health.WRITE_STEPS
adb shell pm grant com.pikminbot.jogger android.permission.health.READ_STEPS
```

### Use

**UI**: open **PikminBot Jogger**, pick Loop/Straight, then enter the **speed
(km/h)**, **steps per second**, **radius (m)** (loop mode), **heading 0-360°**
(straight mode; also sets the loop's starting bearing) and **duration**.
Tap **Start jog**. The status shows live speed, distance and steps.

**Headless / automation** (exported foreground service — works screen-off):

```bash
# 30-min loop at 8 km/h, 2.5 steps/s, 100 m radius, starting at last location:
adb shell am start-foreground-service -n com.pikminbot.jogger/.JogService \
  --es mode loop --ef speed_kph 8.0 --ef steps_per_sec 2.5 \
  --ei radius_m 100 --ei duration_min 30

# 15-min straight jog at 12 km/h heading East (90°):
adb shell am start-foreground-service -n com.pikminbot.jogger/.JogService \
  --es mode straight --ef speed_kph 12.0 --ei heading 90 --ei duration_min 15

# Start at explicit coordinates (optional; defaults to last known location):
adb shell am start-foreground-service -n com.pikminbot.jogger/.JogService \
  --es mode loop --ef lat 22.3193 --ef lon 114.1694 --ef speed_kph 10.0 --ei duration_min 30

# Stop early:
adb shell am start-foreground-service -n com.pikminbot.jogger/.JogService --es cmd stop
```

Verify what was written to Health Connect:

```bash
adb shell am start -n com.pikminbot.jogger/.MainActivity --ez verify true
adb logcat -d | grep "PikminJogger: VERIFY"    # e.g. "VERIFY: 12 own records, 690 steps"
```

### Build from source

```bash
cd pikmin-jogger
ANDROID_SDK=$HOME/android-sdk bash build.sh     # gradle release + zipalign -p + apksigner
adb install -r out/pikmin-jogger.apk
```

Requires the Android SDK, JDK 17 and a Gradle 8.11.x distribution.

---

## 4. Pikmin Clones — several accounts on one phone

Run the original app plus any number of re-packaged clones (`com.pikmin.c1`,
`c2`, …) and/or a copy installed into a profile (Samsung dual-app profile,
work profile, Secure Folder), each signed into a different account, and keep
**one mock GPS pin and one Health Connect step write driving all of them at
once** — both injectors act at Android-user level, so in user 0 a single action
covers every clone.

Verified on a Samsung SM-S7210 (Android 16), Pikmin Bloom 153.0: original +
3 clones + 1 profile copy, all four main instances receiving the same pin and
the same steps.

- **Login**: use the vendor's **web-OAuth** provider (Nintendo Account for
  Pikmin Bloom). Google sign-in cannot work in a re-signed clone — the signing
  certificate is not registered in the vendor's OAuth project — and Facebook
  fails its key-hash check. The **profile copy** keeps every provider working.
- **Launcher**: an app installed into a profile gets no icon, so the bundle
  includes a tiny **Pikmin Clones** picker that lists every instance on the
  device (via `LauncherApps`, no root/Shizuku) and opens the one you tap.
- **Two crashes that must be patched** for any re-packaged clone of this app:
  a `signature`-level permission clash (`INSTALL_FAILED_DUPLICATE_PERMISSION`)
  and a cross-app shared-login query that throws `SecurityException` and kills
  the app on its first screen. Both fixes ship here.
- **Step source per account**: Health Connect must be linked in each account's
  own in-game settings (設定 → 隱私與步數); `pm grant` alone leaves the account
  sitting at a few steps.
- **Stale-pin trap**: a pin without `persistent=true` re-injects for ~90 s and
  then auto-releases, silently returning the phone to real GPS — the usual
  cause of "the coordinates did not change".

Full guide, build scripts and verification helpers:
[`pikmin-clones/`](pikmin-clones/) — `clones.sh status | pin | steps | run`,
`build_clone.sh`, `patch_sharedlogin.py`, `verify_gps.sh`, `verify_steps.sh`.

---

## FAQ

### Do these apps work with Pikmin Bloom?

Yes. Pikmin Bloom reads the device's reported GPS location and its step count
through Google Fit / Health Connect:

- **MockLoc** and **Jogger** change the GPS location Pikmin Bloom sees.
- **HC Step Injector** and **Jogger** write step records into Health Connect,
  which Pikmin Bloom reads via the Fit SDK.

### Can I run several Pikmin Bloom accounts at the same time?

Yes. Re-package the app under a new package name (or install the original into
a profile) so each copy has its own data, sign in to each copy with a different
account, and use a web-OAuth provider (Nintendo Account) for the clones — see
[Pikmin Clones](#4-pikmin-clones--several-accounts-on-one-phone). One mock GPS
pin and one Health Connect step write then cover every copy at once, because
both act at Android-user level. Note that multi-accounting may itself breach the
game's Terms of Service.

### Do I need root?

No. Mock location requires only the standard **Developer Options → Select mock
location app** setting. Health Connect steps require the Health Connect app
and its steps permission. No root, no custom ROMs, no Xposed.

### Do I need a computer?

Not for MockLoc — tap the map and flip the switch. Jogger and HC Step
Injector also have full UIs; a computer (`adb`) is only needed for headless
automation and for pushing your own Google Fit OAuth token file.

### Which Android versions are supported?

- **MockLoc**: Android 5.0+ (minSdk 21), designed for Android 12+ persistence.
- **HC Step Injector**: Android 9.0+ (minSdk 28), Health Connect required.
- **Jogger**: Android 9.0+ (minSdk 28), Health Connect required.

### Why are my steps not counting in the game?

Pikmin Bloom reads steps from the device's local Google Fit store — it must
sync from Health Connect / the cloud first. Open the Google Fit app once to
force a sync, then relaunch Pikmin Bloom. Also confirm the health permission
was granted (`adb shell pm grant` for Jogger; the HC Step Injector UI for the
injector).

### Why is only one mock-location app working at a time?

Android allows exactly **one** app to be the mock location provider. Selecting
Jogger as the mock app disables MockLoc until you re-select it in Developer
Options.

### Can these apps be used with other games or apps?

Technically yes — the mock GPS fix and Health Connect step records are
system-wide, so any app that reads them sees them. Be aware of each app's
terms of service before using spoofing tools.

### Is this open source? Can I build it myself?

Yes — MIT licensed, and each app builds from source with just the Android SDK
and JDK 17 (see the per-app "Build from source" sections). Releases are signed
with a persistent key so `adb install -r` upgrades in place.

---

## Repository layout

```
mockloc/                      # MockLoc source (Java, aapt2/d8 build)
hc-step-injector/             # HC Step Injector source (Kotlin/Gradle)
pikmin-jogger/                # Jogger source (Kotlin/Gradle, exported FGS)
pikmin-tools/                 # PikminBot Tools source (Kotlin/Gradle, unified engine)
pikmin-clones/                # multi-account clone tooling + Pikmin Clones picker app
releases/                     # pre-built, signed APKs (downloadable)
```

## License

MIT — see [LICENSE](LICENSE).
