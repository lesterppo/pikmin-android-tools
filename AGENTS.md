# AGENTS.md — Pikmin Android Tools

Repository for three Android companion tools used with Pikmin Bloom: MockLoc
(GPS mock), HC Step Injector (Health Connect/Google Fit steps) and Jogger
(jogging simulator). Public, privacy-safe: source code + pre-built signed APKs
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
  `cd mockloc && ANDROID_SDK=$HOME/android-sdk ANDROID_KEYSTORE=~/pikmin-bot/mockloc/keystore.jks ANDROID_KEYALIAS=mockloc ANDROID_KEYPASS=pikminbot ANDROID_KSPATH=pikminbot bash build.sh`
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
