# PikminBot Tools — consolidated GPS mock + jog simulator

One app, two services. Android allows only **one** mock-location app per
device, so separate mock/jogger packages keep fighting over the slot and
silently lose the `MOCK_LOCATION` permission. PikminBot Tools merges both into
a single package (`com.pikminbot.tools`) so they can never conflict, and adds
on-device self-healing.

## Services

- **MockService** — pins a mock GPS fix every ~0.9 s (survives Android 12+),
  auto-releases after 90 s unless `persistent=true`.
- **JogService** — simulates a jog: GPS moves at a configurable speed on a
  loop or straight route while steps stream to Health Connect in 30 s batches
  (dt-based timing so measured speed matches config).

## Self-heal (no computer required)

Both services catch `SecurityException` on mock injection and attempt repair
(max 3 per service lifetime, all attempts logged under tag `PikminBotTools`):

1. **Shizuku** (if installed and running) — runs
   `appops set <pkg> android:mock_location allow` +
   `settings put secure mock_location <pkg>` and retries.
2. **WRITE_SECURE_SETTINGS** (if granted) — self-selects itself as the
   system mock-location app and retries.
3. **Notification** — "Mock location permission lost — tap to fix" deep-links
   to Developer settings.

## One-time setup (adb, from any computer)

```bash
adb install -r releases/pikmin-tools-v1.0.apk
adb shell pm grant com.pikminbot.tools android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.pikminbot.tools android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.pikminbot.tools android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant com.pikminbot.tools android.permission.WRITE_SECURE_SETTINGS   # optional: enables self-heal tier 2
adb shell pm grant com.pikminbot.tools android.permission.health.READ_STEPS
adb shell pm grant com.pikminbot.tools android.permission.health.WRITE_STEPS
adb shell appops set com.pikminbot.tools android:mock_location allow
adb shell settings put secure mock_location com.pikminbot.tools
```

Optional: install [Shizuku](https://shizuku.rikka.app/) for self-heal tier 1
(re-arms permissions after reinstalls without any computer).

## Usage (screen-off friendly, exported foreground services)

```bash
# Mock: pin a position forever (persistent) — launch from Activity context on Android 14+
adb shell am start-foreground-service -n com.pikminbot.tools/.MockService \
  --ef lat 35.658581 --ef lon 139.745438 --ez persistent true
adb shell am start-foreground-service -n com.pikminbot.tools/.MockService --es cmd stop

# Jog: 8 km/h loop, 60 m radius, 2 steps/s, 30 minutes
adb shell am start-foreground-service -n com.pikminbot.tools/.JogService \
  --es mode loop --ef speed_kph 8.0 --ei radius_m 60 --ef steps_per_sec 2.0 --ei duration_min 30
adb shell am start-foreground-service -n com.pikminbot.tools/.JogService --es cmd stop
```

Notes:
- Run Mock **or** Jog, not both — they share one provider (last writer wins).
- If another app (e.g. the standalone MockLoc) is pinning at the same time,
  the fixes will overwrite each other; stop the other app first.
- Re-applying the `appops`/`settings` lines after any reinstall is expected
  (Android resets them); with Shizuku or WRITE_SECURE_SETTINGS granted the
  app re-arms itself.

## Build

```bash
cd pikmin-tools && ANDROID_SDK=$HOME/android-sdk \
  ANDROID_KEYSTORE=$HOME/pikmin-bot/mockloc/keystore.jks \
  ANDROID_KEYALIAS=mockloc ANDROID_KEYPASS=pikminbot ANDROID_KSPATH=pikminbot \
  bash build.sh
```

Signed with the same canonical key as MockLoc/Jogger releases, so upgrades
install cleanly over existing installs.
