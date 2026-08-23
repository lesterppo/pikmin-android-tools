# Pikmin Android Tools

Two privacy-safe Android companion apps for **Pikmin Bloom**. Both are
open-source and ship pre-built, signed APKs under [`releases/`](releases/).

| Tool | Package | APK | What it does |
|------|---------|-----|--------------|
| **MockLoc** | `com.pikminbot.mockloc` | `releases/mockloc-v1.7.apk` | On-device GPS mock with an embedded map — tap to pick a spot, natural 90 s injection window (the mushroom-friendly behaviour), persistent mode opt-in. No computer needed. |
| **HC Step Injector** | `com.pikminbot.hcsteps` | `releases/hc-step-injector-v2.1.apk` | Writes step counts into **Health Connect** (and optionally Google Fit cloud) so Pikmin Bloom reads them. |

> **Privacy & safety notice**
> - These are mock/spoof tools. Using them may violate Pikmin Bloom / Niantic
>   Terms of Service. Use at your own risk.
> - No personal data, account credentials, API keys, or OAuth client secrets are
>   compiled into the APKs or stored in this repo. The Git history contains only
>   source code and the public, signed binaries.
> - The HC Step Injector's Google Fit cloud path requires *you* to provision your
>   own OAuth `client_id`/`refresh_token` on the device (see below). It is never
>   bundled.

---

## 1. MockLoc — on-device GPS mock

### Install
```bash
adb install -r releases/mockloc-v1.7.apk
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
- Tap **Set point** (or flip the **Inject** switch ON). A foreground service
  refreshes the fix every ~0.9 s so it survives on Android 12+.
- Open Pikmin Bloom — it now reads the pinned location.

### The mushroom fix (v1.7)
Pikmin Bloom (Niantic) **rejects gameplay actions when the GPS fix is
continuously mock-flagged forever** — destroying a mushroom with a
persistent, never-ending mock showed a generic 錯誤/Error. v1.7 restores the
behaviour that works:

- **Natural 90-second lifetime** — the mock is kept fresh for 90 s, then the
  service releases it and the device reverts to real GPS on its own.
- **No forced persistent mock** — each location pick gets its own fresh 90 s
  window, so the game sees a natural, short-lived fix instead of an endless
  mock-flagged one.
- Picking a new position restarts a fresh 90 s window.
- Persistent map-coverage mode (`persistent=true` intent extra) remains
  available for scripts that want continuous re-pinning.

Verified: with v1.7, moving to a mushroom and destroying it works.

> After the 90 s window ends, the `gps` provider is cleared and the system's
> *fused* location falls back to real GPS (outdoors / via WiFi). On Android
> 14+, the app releases the mock automatically — there is no need to toggle
> it off manually.

### Build from source
```bash
cd mockloc
ANDROID_SDK=$HOME/android-sdk bash build.sh     # needs build-tools 34.0.0 + android-34
adb install -r out/mockloc.apk
```
The script generates a throwaway self-signed debug key; override with env vars
(`ANDROID_KEYSTORE`, `ANDROID_KEYPASS`, …) to use your own.

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

## Repository layout
```
mockloc/                      # MockLoc source (Java, aapt2/d8 build)
  build.sh                    # privacy-safe build (no hardcoded secret)
  AndroidManifest.xml
  java/com/pikminbot/mockloc/
  res/...
hc-step-injector/             # HC Step Injector source (Kotlin/Gradle)
  app/...
releases/                     # pre-built, signed APKs (downloadable)
  mockloc-v1.7.apk
  mockloc-v1.4.1.apk          # previous stable (kept for reference)
  hc-step-injector-v2.1.apk
privacy_sweep.py              # CI/local check: fails if any secret/PII is committed
```

## License
MIT — see [LICENSE](LICENSE).
