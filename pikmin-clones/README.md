# Pikmin Clones — several Pikmin Bloom accounts on one phone

Run **multiple Pikmin Bloom accounts side by side** on one unrooted phone, and
drive the **same mock GPS + Health Connect step injection into every copy at
once**. Verified on a Samsung SM-S7210 (Android 16) with Pikmin Bloom 153.0:
1 original + 3 re-packaged clones + 1 profile copy, all four of the main
instances receiving a single pin writes and a single step write.

Two independent techniques are used — pick per account:

| | Re-packaged clone | Profile copy |
|---|---|---|
| How | new package name + own signature (`com.pikmin.c1`, `c2`, …) | same signed APK installed into another Android user (Samsung dual-app profile `95`, work profile, Secure Folder) |
| Launcher icon | yes, normal icon | **none** — use the bundled **Pikmin Clones** launcher app |
| Login | vendor **web-OAuth** providers only (Nintendo Account on Pikmin Bloom). Google sign-in and Facebook will not work — a re-signed app is not registered in the vendor's OAuth project | every provider works unchanged |
| Injectors | **shared automatically** (all clones live in user 0) | per-user: separate `mock_location` selection, and Health Connect may not even be installed in that user |
| How many | unlimited | 1 clone profile + 1 Secure Folder |

## Layout

```
pikmin-clones/
├── launcher/               "Pikmin Clones" picker app (Java, aapt2/d8, no Gradle)
│   ├── MainActivity.java   lists every instance via LauncherApps, starts the tapped one
│   ├── AndroidManifest.xml
│   └── build_launcher.sh
├── build_clone.sh          build a re-packaged clone from a pulled base+split
├── patch_sharedlogin.py    crash fix that must be applied to the decoded tree (see below)
├── clones.sh               run/verify everything from one place (status, pin, steps, run, …)
├── verify_steps.sh         inject steps, screenshot all instances' step counters into one montage
├── verify_gps.sh           pin a location, screenshot all instances' map cards
└── ui.py                   screenshot + OCR helper (Unity UIs have no usable view tree)
```

## Why the launcher app exists

An app installed into a profile gets no launcher icon of its own (Samsung only
badges apps on its Dual-Messenger allowlist). `LauncherApps` can enumerate and
start activities of *any* profile in the app's profile group, and
`LauncherApps.startMainActivity()` is callable from an ordinary app — no root,
no Shizuku. The picker lists one button per discovered instance:

```java
LauncherApps la = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
for (UserHandle u : ((UserManager) getSystemService(USER_SERVICE)).getUserProfiles())
    for (LauncherActivityInfo ai : la.getActivityList(TARGET_PKG, u)) { /* button */ }
la.startMainActivity(ai.getComponentName(), userHandle, null, null);   // works
```

## Building a clone

```bash
# 1. pull the installed base + arm64 split (never mix versions)
adb shell pm path com.nianticlabs.pikmin
adb pull <base>   apks/base.apk
adb pull <split>  apks/split_config.arm64_v8a.apk

# 2. decode once and reuse the tree for every clone
java -jar apktool.jar d -f -o tree apks/base.apk
python3 patch_sharedlogin.py tree            # mandatory, see pitfall 2

# 3. unpack the split's native libs
mkdir -p libs && (cd libs && unzip -o ../apks/split_config.arm64_v8a.apk 'lib/*')

# 4. build + sign (env-driven; see the signing note below)
export ANDROID_CLONE_PREFIX=com.pikmin.c  ANDROID_APP_LABEL="Pikmin Bloom"
export ANDROID_KEYSTORE=~/keys/release.jks ANDROID_KEYALIAS=mykey ANDROID_KEYPASS=... 
./build_clone.sh c1 && adb install -r out/c1.apk && ./pikmin-clones/clones.sh grant 1
```

### Pitfall 1 — never sed a package rename through binary assets
`assets/global-metadata.dat` (Unity IL2CPP) and `*.bundle` files break if a
shorter string replaces a longer one, because every offset after it shifts.
`build_clone.sh` restricts rewriting to text files (`.xml .json .txt
.properties .cfg .html .js`) and logs the files it skips.

### Pitfall 2 — cross-app shared login crashes the clone
The app imports a login token from *other* installed vendor apps by querying
`<otherPkg>.provider.logincontentprovider`. A re-signed clone can never hold
the original's `signature`-level permission, so that query throws
`SecurityException` on a handler thread → **FATAL EXCEPTION**: the clone dies or
hangs on the first screen after "continue", with nothing shown on screen.
Symptom in logs:

```
FATAL EXCEPTION: com.nianticlabs.platform.sharedlogin
java.lang.SecurityException: Permission Denial: opening provider
com.nianticlabs.platform.sharedlogin.LoginContentProvider ... requires
com.nianticlabs.platform.permission.LOGIN_PROVIDER
```

`patch_sharedlogin.py` wraps the query in `try/catch(Throwable)` and returns
`""` — exactly the value the method already returns when no token exists. The
cross-app import is a convenience; normal sign-in is unaffected.

### Pitfall 3 — duplicate signature permission blocks installation
The app re-declares a `signature`-level permission it owns; a second app
declaring the same name fails with
`INSTALL_FAILED_DUPLICATE_PERMISSION`. `build_clone.sh` renames it per clone
(`com.nianticlabs.platform.permission.LOGIN_PROVIDER` →
`com.pikmin.cN.permission.LOGIN_PROVIDER`).

### Pitfall 4 — 16 KB-page devices
Always `zipalign -f -p 4` (page-align uncompressed native libs) before signing,
otherwise Android 15+/16 KB-page kernels reject the install with
`INSTALL_FAILED_INVALID_APK: Failed to extract native libraries, res=-2`.

## Making the injectors cover every instance

Both injectors act at **Android-user level**, so in user 0 one action reaches
every clone simultaneously:

```bash
./clones.sh pin 22.3193 114.1694     # mock GPS: HOLD pin -> all instances
./clones.sh steps 3000 20            # Health Connect: 3000 steps -> all instances
./clones.sh run 6 2 loop 100         # jog: GPS movement + steps together
./clones.sh stopall
```

* **Mock GPS** — a single `mock_location` selection per user; the fix it
  injects is what every app in that user receives.
  * A pin started **without** `persistent=true` re-injects for ~90 s and then
    auto-releases, silently returning the phone to real GPS. If "coordinates
    did not change", check `dumpsys location | grep 'last location=Location\[fused'`
    for the `mock` flag and an advancing `et=` **before** debugging anything else.
  * Clone apps request location with `minUpdateDistance=1000000.0`, i.e. they
    take a single first fix per session. Pin first, then (re)open the app — or
    force-stop and reopen an instance that is still showing the old spot.
* **Health Connect steps** — one per-user store, so one write is read by every
  app in that user. Each **account must link Health Connect in its own in-game
  settings** (設定 → 隱私與步數); granting `android.permission.health.READ_STEPS`
  with `pm grant` is necessary but not sufficient, and an account that is not
  linked just sits at a handful of steps.
* Profile copies are **not** covered: they are a different Android user with
  their own `mock_location` setting, and Health Connect may not be installed
  there at all (`pm list packages --user <id> | grep health`).

## Verifying

```bash
./verify_gps.sh 22.2783 114.1747     # pin + screenshot every instance's map card
./verify_steps.sh 3000 20            # inject + montage every instance's step counter
```

`ui.py` is a screenshot+OCR helper (`shot`, `ocr`, `find`, `tap`, `focus`) used
because Unity/IL2CPP screens expose no useful accessible view tree. Wake and
unlock the phone first — a locked screen silently swallows taps and OCR returns
nothing.

## Signing

Use ONE persistent key so `adb install -r` can replace an older build in place
(a new key means uninstalling first, which resets app permissions and the
mock-location appop). The key is **not** in this repository; pass it through the
environment:

```bash
export ANDROID_KEYSTORE=~/keys/release.jks
export ANDROID_KEYALIAS=mykey
export ANDROID_KEYPASS=...          # never commit this
```

## Limitations, honestly

* Re-signed clones report `appRecognitionVerdict = UNRECOGNIZED_VERSION` to Play
  Integrity. Pikmin Bloom accepted them, but a vendor that enforces app
  recognition will refuse at login regardless of how clean the client looks.
* Google sign-in cannot work in a re-signed clone — the certificate is not
  registered in the vendor's OAuth project. Use a web-OAuth provider, or take
  the profile-copy route.
* Clone management is `adb`-driven. Use a wireless-debugging session (or
  Shizuku) if you want to drive it without a cable; note that wireless
  debugging rotates its port, so re-discover the endpoint with
  `adb mdns services` when a saved `IP:port` stops answering.
