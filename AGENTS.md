# AGENTS.md — Pikmin Android Tools

Repository for Android companion tools used with Pikmin Bloom: MockLoc
(GPS mock), HC Step Injector (Health Connect/Google Fit steps), Jogger
(jogging simulator), PikminBot Tools (MockLoc + Jogger in one app, one engine)
and Pikmin Clones (multi-account clone tooling + instance picker). Public, privacy-safe: source code + pre-built signed APKs
only. **No secrets, API keys, OAuth client secrets, emails, or absolute home
paths are ever committed.**

## Hard rules (enforced by privacy_sweep.py)
- Never commit: `*.jks`, `*.keystore`, `local.properties`, `*.apk` build
  outputs, `fit_token.json`, any file containing `client_secret`, a real
  `@gmail.com`, or `/home/<user>` paths.
- SIGNING KEY: releases are signed with ONE persistent key so `adb install -r`
  can upgrade in place without "signatures do not match". The key is NOT in
  this repo (it lives locally, e.g. `~/pikmin-bot/mockloc/keystore.jks`,
  alias `mockloc`, and is gitignored everywhere). A fresh build with a NEW
  key will NOT upgrade over an existing install — users must uninstall first.
  Build with the canonical key:
  `cd mockloc && ANDROID_SDK=$HOME/android-sdk ANDROID_KEYSTORE=~/pikmin-bot/mockloc/keystore.jks ANDROID_KEYALIAS=mockloc ANDROID_KEYPASS=<your-password> ANDROID_KSPATH=<store-type> bash build.sh`
- `local.properties` (contains `sdk.dir=...`) is gitignored — do NOT add it.

## Tools
### MockLoc (`com.pikminbot.mockloc`)
- Java, built with `aapt2` + `d8` (no Gradle). `mockloc/build.sh`.
- Foreground service pins a mock GPS fix ~every 900ms so it survives Android 12+.
- Embedded Leaflet map (Esri World Street Map tiles — keyless, permitted).
- Toggle (no timeout) via `MockService` `cmd=stop` intent.

### HC Step Injector (`com.pikminbot.hcsteps`)
- Kotlin + Gradle (`hc-step-injector/`). AndroidX Health Connect client.
- Dual-pathway: writes to Health Connect AND Google Fit cloud in one call.
- Fit cloud path needs a user-provisioned `fit_token.json` (own OAuth client).

### PikminBot Tools (`com.pikminbot.tools`)
- Kotlin + Gradle (`pikmin-tools/`). MockLoc + Jogger UIs over ONE
  `EngineService` (v2.0 merger) so the two never fight over the single
  mock-location slot. `versionName 2.4`.
- v2.2: `persistent` is per-request (a sticky `true` used to make every later
  pin immortal) and `onDestroy` hands a real fix back to the phone.
- v2.3 self-heal (the "Developer options off/on breaks it" fix). Toggling
  Developer options clears the mock-location slot and returns the
  `MOCK_LOCATION` appop to deny while the `mock_location` setting can still
  name the app, so Settings looks right and injection is refused.
  `SlotWatch.kt` watches `development_settings_enabled` + `mock_location`
  (ContentObserver + 3 s poll, refcounted across Activity/Service);
  `SelfHeal.kt` probes the REAL appop (`AppOpsManager.unsafeCheckOpNoThrow`,
  never the setting alone) and repairs via root → Shizuku →
  WRITE_SECURE_SETTINGS → notification, with progressive backoff;
  `RepairActivity.kt` is the in-app repair screen. `EngineService` no longer
  `stopSelf()`s on a lost slot — it runs degraded and resumes by itself.
  NEVER call `attemptRepair` without re-checking the appop: writing the
  secure setting alone does NOT move the appop (verified on-device).
  PC-side helper: `fix-mock-slot.sh` (adb repair + optional engine re-arm).
- v2.4: `switchMode()` banks pending jog steps (`flushSteps`) before the counters
  are reset — a jog → pin switch used to throw away up to 30 s of step records.
- MockLoc and Jogger share the ONE appop/provider, so one repair frees both;
  only the re-arm is mode-specific (last-used mode).
- `desktop/`: mode-aware re-arm (`arm_engine(..., mode=)`), `--jog` CLI, and a
  STICKY `last_mode`. Never infer the mode from a fresh "pin @" line the repair
  itself just wrote: ignore logcat lines younger than `max_age_s` and older than
  `cfg["last_mode_set"]` (an epoch stamped whenever we set the mode ourselves).

### PikminBot Repair (`desktop/`)
- Single-file Python 3 + tkinter desktop app (no third-party deps; GUI is
  drivable by keys F5/F6/F7/F8 so it can be tested with `xdotool key F5`).
- One-click repair over wireless adb. Discovery order: cached serial → mDNS →
  LAN port scan of 30000-49999 (the wireless-debug port ROTATES, so a fixed
  `IP:5555` is wrong most of the time). Scans leave `offline` transports
  behind → always `cleanup_offline()` before a bare `adb shell`.
- CLI modes used for testing and by scripts: `--status [--json]`, `--repair`,
  `--connect`, `--discover`, `--arm`, `--pin LAT LON`, `--stop`,
  `--pair IP:PORT CODE`.
- Never `adb shell` without `-s <serial>` once more than one transport exists
  ("more than one device/emulator" otherwise).
- `install.sh` installs app + launcher + icon + `.desktop`; `--uninstall`
  reverses it. Nothing in the repo hardcodes a home path.

### Pikmin Clones (`pikmin-clones/`)
- Multi-account tooling: re-package the app under a new package name so several
  accounts stay signed in side by side, plus a picker app
  (`com.peter.pikminclones`) that lists the original, every clone and any
  profile copy via `LauncherApps` and opens the tapped one.
- `build_clone.sh` (apktool re-package), `patch_sharedlogin.py` (mandatory
  crash fix), `clones.sh` (status/launch/grant/reset/pin/steps/run),
  `verify_gps.sh`, `verify_steps.sh`, `ui.py` (screenshot+OCR driver).
- Re-signed clones log in with **web-OAuth** providers only (Nintendo Account).
  Google/Facebook sign-in cannot work on a re-signed APK.
- Never sed a package rename through binary assets (`global-metadata.dat`,
  `*.bundle`) — offsets shift and the file corrupts.

### Jogger (`com.pikminbot.jogger`)
- Kotlin + Gradle (`pikmin-jogger/`). Simulates a jog: mock GPS moves at
  10 km/h (loop or straight route) + 2 steps/s streamed to Health Connect.
- Exported foreground service (`foregroundServiceType="location"`) so adb can
  drive it with `am start-foreground-service` (screen-off, no UI popup).
- Only ONE app can be the system mock-location provider at a time —
  selecting the Jogger disables MockLoc until re-selected.

## Verification
Run before committing:
```bash
python3 privacy_sweep.py        # exit 1 if secrets/PII found
```
And for the APK: `apksigner verify releases/<file>.apk`.

## Build
- MockLoc: `cd mockloc && ANDROID_SDK=$HOME/android-sdk bash build.sh`
- HC: `cd hc-step-injector && ./gradlew assembleRelease`
- PikminBot Tools: `cd pikmin-tools && ./gradlew assembleRelease`
- Pikmin Clones picker: `cd pikmin-clones/launcher && bash build_launcher.sh`
  (needs `ANDROID_KEYSTORE` / `ANDROID_KEYALIAS` / `ANDROID_KEYPASS`)
- Clone of the game: `cd pikmin-clones && ./build_clone.sh c1`
