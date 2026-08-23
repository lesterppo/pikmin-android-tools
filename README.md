# Pikmin Android Tools

Two privacy-safe Android companion apps for **Pikmin Bloom**. Both are
open-source and ship pre-built, signed APKs under [`releases/`](releases/).

| Tool | Package | APK | What it does |
|------|---------|-----|--------------|
| **MockLoc** | `com.pikminbot.mockloc` | `releases/mockloc-v1.4.1.apk` | On-device GPS mock with an embedded map — tap to pick a spot, toggle injection on/off. No computer needed. |
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
adb install -r releases/mockloc-v1.4.1.apk
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
- Flip the **Inject** switch **ON**. The app pins that GPS fix from inside the
  phone (a foreground service refreshes it ~every second, so it survives on
  Android 12+).
- Open Pikmin Bloom — it now reads the pinned location.
- Flip **Inject OFF** to release the mock and return to real GPS.

> After stopping, the `gps` provider is cleared immediately. The system's
> *fused* location may briefly show the last value until a fresh real fix
> arrives (outdoors / via WiFi). On Android 14+, the app holds the mock
> indefinitely while the toggle is on — there is **no auto-timeout**.

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
  mockloc-v1.4.1.apk
  hc-step-injector-v2.1.apk
privacy_sweep.py              # CI/local check: fails if any secret/PII is committed
```

## License
MIT — see [LICENSE](LICENSE).
