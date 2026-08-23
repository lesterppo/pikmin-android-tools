# AGENTS.md — Pikmin Android Tools

Repository for two Android companion tools used with Pikmin Bloom. Public,
privacy-safe: source code + pre-built signed APKs only. **No secrets, API keys,
OAuth client secrets, emails, or absolute home paths are ever committed.**

## Hard rules (enforced by privacy_sweep.py)
- Never commit: `*.jks`, `*.keystore`, `local.properties`, `*.apk` build
  outputs, `fit_token.json`, any file containing `client_secret`, a real
  `@gmail.com`, or `/home/<user>` paths.
- The published APKs under `releases/` are signed with throwaway debug keys.
  Rebuilders generate their own (see `mockloc/build.sh`).
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

## Verification
Run before committing:
```bash
python3 privacy_sweep.py        # exit 1 if secrets/PII found
```
And for the APK: `apksigner verify releases/<file>.apk`.

## Build
- MockLoc: `cd mockloc && ANDROID_SDK=$HOME/android-sdk bash build.sh`
- HC: `cd hc-step-injector && ./gradlew assembleRelease`
