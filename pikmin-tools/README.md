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

## Mock time / date / timezone (v2.5)

The **TIME** tab (and an exported receiver) injects a system clock, date and
timezone. This is how you move the device clock that every local app reads.

```
[MockLoc] [Jogger] [Time]
```

Controls: **Set date** / **Set time** pickers, **Now**, a timezone field with
HK/Tokyo/Seoul/Shanghai chips, **TZ only**, relative **-1d / -1h / +1h / +1d**
shifts, **Apply mock time + timezone**, **Grant Shizuku**, **Restore real time**.
The header always shows the live device clock, the zone in use and which
privilege tier is active (`MOCK ACTIVE` while an injection is in place).

### Why a privilege tier is needed

`SET_TIME` and `SET_TIME_ZONE` are `signature` permissions, so no app can hold
them — not even with `pm grant`. `WRITE_SECURE_SETTINGS` is **not** enough on
Android 12+: writing `Settings.Global.TIME_ZONE` no longer reaches the running
system (the timezone is owned by `time_zone_detector`) and writing `AUTO_TIME`
alone does not move the clock. Both verified on device.

The app therefore executes the same commands the **shell uid** uses, through a
ladder and reports honestly which rung actually ran:

| tier | requirement | note |
|---|---|---|
| 0 | `su` (root) | fully automatic on a rooted phone |
| 1 | **Shizuku** running + authorised | fully automatic on stock Android; start Shizuku once after each reboot |
| 2 | none | the tab states exactly what is missing and how to fix it |

Commands executed (tier 0/1):

```bash
cmd time_detector set_auto_detection_enabled false
settings put global auto_time 0
settings put global auto_time_zone 0
cmd alarm set-timezone Asia/Tokyo
cmd alarm set-time <epoch-ms>
```

`Restore real time` puts the saved zone back and re-enables automatic
time/timezone detection, after which the platform resyncs from the network.

### Headless / automation

```bash
# shift the clock by -24 h through the app (needs tier 0/1 inside the app)
adb shell am broadcast -n com.pikminbot.tools/.MockTimeReceiver \
  -a com.pikminbot.mock.SET_TIME --ef shift_hours -24

# absolute time + timezone
adb shell am broadcast -n com.pikminbot.tools/.MockTimeReceiver \
  -a com.pikminbot.mock.SET_TIME --el epoch 1795101296799 --es tz Asia/Tokyo

# back to real time
adb shell am broadcast -n com.pikminbot.tools/.MockTimeReceiver \
  -a com.pikminbot.mock.RESTORE_TIME
```

Always send the explicit component (`-n`): Android 14+ does not deliver
implicit broadcasts to another app's manifest receiver.

Without the app at all, the shell uid can do the same directly — compute the
epoch on the host, because Android's `sh` arithmetic is 32-bit and overflows:

```bash
adb shell cmd time_detector set_auto_detection_enabled false
adb shell 'settings put global auto_time 0; settings put global auto_time_zone 0'
adb shell cmd alarm set-timezone Asia/Tokyo
adb shell cmd alarm set-time 1795101296799
```

### Verified behaviour (SM-A3760, Android 16, Shizuku tier)

- `TZ only` → `persist.sys.timezone=Asia/Tokyo`, `date` flips to JST while the
  epoch is unchanged.
- `+1d` → clock moved one day; `Apply` with a picked date → clock moved to
  `2026-11-20`, log shows `set-time ... [shizuku] ok=true`.
- `Restore real time` → zone back to `Asia/Hong_Kong`, clock back to real time,
  `auto_time=1`, `auto_time_zone=1`.
- Broadcast path → `shift_hours -24` moved the clock exactly one day back.
- **Pikmin Bloom does not follow the device clock**: with the phone set to
  Nov 19 the game still showed `11月2日` (server time). Mock time shifts local
  apps, not the game's server-authoritative day boundary.

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
