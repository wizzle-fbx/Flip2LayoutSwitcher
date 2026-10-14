# Development

## How it works

- `ForegroundService` (accessibility service) sees which app is in front. It reads window package/class names only; it cannot read screen content or keys. Overlays such as the notification shade, keyboard or game assistant are ignored.
- `ControllerShell` runs as a Shizuku UserService (UID 2000) and can only write three settings: `temp_abxy_layout_mode`, `flip_button_layout` and `no_create_gamepad_button_layout` (always 0). It refuses to run on any firmware other than the verified fingerprint, fails closed on unknown values, and checks the result (settings, `persist.sys.gamepad.type` and controller product ID) before reporting success. A failed partial write is rolled back.
- `ModeSession` remembers your normal Retroid mode before the first profiled app and restores it afterwards; it survives process death.
- `SwitchPlanner` debounces foreground changes and avoids repeated switches.

Verified firmware: `qti/kona/kona:13/TKQ1.240621.001/eng.RPFlip.20250501.115911:user/release-keys`. See [FEASIBILITY.md](FEASIBILITY.md) for findings and device test results.

Retroid's launcher force-stops apps cleared from Recents unless they are whitelisted, and a force-stop turns the accessibility service off; that is why the whitelist is setup step 1. Handheld Settings → Power → *Clean process when standby* uses its own ignore list and also force-stops apps when the screen turns off.

## Build and test

JDK 17+ and Android SDK platform 36. Set `sdk.dir` in an untracked `local.properties` or use `ANDROID_HOME`.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat assembleDebugAndroidTest
```

GitHub Actions runs the same checks on every push and pull request.

Run the UI test only on an explicitly selected device. `am instrument` force-stops the app, which turns the accessibility service off; re-enable it afterwards. The test is skipped if Eden Nightly (the test app) is not installed.

```powershell
adb -s YOUR_SERIAL install -r app/build/outputs/apk/debug/Flip2LayoutSwitcher-<version>-debug.apk
adb -s YOUR_SERIAL install -r app/build/outputs/apk/androidTest/debug/Flip2LayoutSwitcher-<version>-debug-androidTest.apk
adb -s YOUR_SERIAL shell am instrument -w -e class app.flip2layout.switcher.ProfileUiTest app.flip2layout.switcher.test/androidx.test.runner.AndroidJUnitRunner
```

`tools/Snapshot.ps1` and `tools/Compare-Snapshots.ps1` capture and compare read-only device diagnostics.

## Releases

1. Bump `appVersion` in `app/build.gradle.kts` (`MAJOR.MINOR.PATCH[-alpha|-beta|-rc[.N]]`); `versionCode` is derived from it.
2. `.\gradlew.bat assembleRelease testDebugUnitTest lintRelease` with `keystore.properties` present.
3. Create a GitHub release tagged with the version (e.g. `v0.4.0`) and attach `app/build/dist/Flip2LayoutSwitcher-<version>.apk` (a copy of the signed release APK without the build-type suffix). Leave "pre-release" unticked. The in-app updater uses the first `.apk` of the newest non-draft release and shows the release notes in its dialog.

The updater checks this repository's releases at most every 6 hours, downloads only from `github.com/<repo>/releases/download/`, checks the package name and a higher versionCode, and hands the APK to Android's package installer. Debug builds never check.

### Signing

Create the key once and keep it safe (a lost key means existing installs cannot be updated):

```powershell
keytool -genkeypair -v -keystore flip2-layout-switcher.keystore -alias flip2-layout-switcher -keyalg RSA -keysize 4096 -validity 10000
```

Untracked `keystore.properties` in the project root:

```properties
storeFile=flip2-layout-switcher.keystore
storePassword=...
keyAlias=flip2-layout-switcher
keyPassword=...
```

Without it the release APK is unsigned. A release build cannot update a debug-signed install.

## Source layout

- `MainActivity`: app list, Save bar, setup, settings, diagnostics (Compose, English-only).
- `ProfileStore` / `ModeSession`: saved profiles and normal-mode restore.
- `ControllerShell` / `ControllerAccess`: Shizuku service and serialized, verified switching.
- `ForegroundService` / `SwitchPlanner`: foreground detection and debounce.
- `Updater`: GitHub Releases update check and install.
