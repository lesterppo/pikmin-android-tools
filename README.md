# Pikmin Android Tools

Privacy-safe Android companion apps for **Pikmin Bloom**. All are
open-source and ship pre-built, signed APKs under [`releases/`](releases/).

| Tool | Package | APK | What it does |
|------|---------|-----|--------------|
| **MockLoc** | `com.pikminbot.mockloc` | `releases/mockloc-v1.10.apk` | On-device GPS mock with an embedded map — tap to pick a spot, natural 90 s injection window (the mushroom-friendly behaviour), persistent mode opt-in. No computer needed. |
| **HC Step Injector** | `com.pikminbot.hcsteps` | `releases/hc-step-injector-v2.1.apk` | Writes step counts into **Health Connect** (and optionally Google Fit cloud) so Pikmin Bloom reads them. |
| **Jogger** | `com.pikminbot.jogger` | `releases/pikmin-jogger-v1.2.apk` | Simulates a jog: the mock GPS moves at a configurable speed (default 10 km/h) along a heading (0-360°) or a loop, while step records (default 2/s) stream into Health Connect. |

> **Privacy & safety notice**
> - These are mock/spoof tools. Using them may violate Pikmin Bloom / Niantic
>   Terms of Service. Use at your own risk.
> - No personal data, account credentials, API keys, or OAuth client secrets are
>   compiled into the APKs or stored in this repo. The Git history contains only
>   source code and the public, signed binaries.
> - The HC Step Injector's Google Fit cloud path requires *you* to provision your
>   own OAuth `client_id`/`refresh_token` on the device (see below). It is never
>   bundled.
> - All APKs are signed with ONE persistent key so `adb install -r` upgrades in
>   place. The key itself is never committed (see AGENTS.md).

---

## 1. MockLoc — on-device GPS mock

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
- Coordinates outside `lat -90..90 / lon -180..180` are rejected with a clear
  message (v1.10+); far-away points are valid to inject but Niantic clamps
  unrealistic teleports — use points near your real location.

### The mushroom fix (v1.7+)
Pikmin Bloom (Niantic) **rejects gameplay actions when the GPS fix is
continuously mock-flagged forever**. The app uses a **natural 90-second
lifetime** — the mock is kept fresh for 90 s, then released so the device
reverts to real GPS. Persistent map-coverage mode (`persistent=true`) remains
available for scripts that want continuous re-pinning.

### Build from source
```bash
cd mockloc
ANDROID_SDK=$HOME/android-sdk bash build.sh     # needs build-tools 34.0.0 + android-34
adb install -r out/mockloc.apk
```
Use the canonical keystore via env vars (`ANDROID_KEYSTORE`, `ANDROID_KEYPASS`,
`ANDROID_KEYALIAS`, `ANDROID_KSPATH`) so upgrades keep working — see AGENTS.md.

---

## 2. HC Step Injector — Health Connect step writer

Writes step deltas into Health Connect (which Pikmin Bloom reads through the
Fit SDK) and, optionally, Google Fit cloud. Dual-pathway = writes to **both**
sinks in one shot.

### Install
```bash
adb install -r releases/hc-step-injector-v2.1.apk
```

### One-time setup
1. Install **Health Connect** from the Play Store (if not preinstalled).
2. Open HC Step Injector → grant **Health Connect → Steps (read & write)**.
   The app includes a `VIEW_PERMISSION_USAGE` activity Android requires for the
   read API to work.
3. *(Google Fit cloud path only — optional)* push your own OAuth token file:
   ```bash
   adb push fit_token.json /sdcard/Android/data/com.pikminbot.hcsteps/files/fit_token.json
   ```
   `fit_token.json` shape (you create this with **your own** Google Cloud
   Desktop OAuth client — no shared secret ever ships in the repo):
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
- Read-back and delete own records from the app UI.

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
Requires the Android SDK, JDK 17 and a Gradle 8.11.x distribution
(`~/.gradle` caches the AGP/Kotlin plugins on first build).

---

## Repository layout
```
mockloc/                      # MockLoc source (Java, aapt2/d8 build)
hc-step-injector/             # HC Step Injector source (Kotlin/Gradle)
pikmin-jogger/                # Jogger source (Kotlin/Gradle, exported FGS)
releases/                     # pre-built, signed APKs (downloadable)
privacy_sweep.py              # CI/local check: fails if any secret/PII is committed
```

## License
MIT — see [LICENSE](LICENSE).
