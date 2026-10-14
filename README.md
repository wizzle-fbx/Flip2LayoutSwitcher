# Flip2 Layout Switcher

A small native Android app for per-app Xbox / Retro controller profiles on **Retroid Pocket Flip 2**.

Find a game, choose **Retro** or **Xbox**, tap **Save**, then open the game from your usual launcher. Set it once and forget it. Flip2 Layout Switcher controls the existing firmware setting; it does not create or remap a virtual controller.

**Status: development beta.** Automatic switching in both directions (game → Retro, back to the normal mode outside profiled apps) has been verified on the firmware below; see [docs/FEASIBILITY.md](docs/FEASIBILITY.md) for what is still untested. Public release requires independent setup tests and additional runtime coverage. This repository does not include extracted Retroid firmware/APKs or private device dumps.

## Requirements

- Retroid Pocket Flip 2, Android 13, currently this verified firmware only:
  `qti/kona/kona:13/TKQ1.240621.001/eng.RPFlip.20250501.115911:user/release-keys`
- [Shizuku](https://shizuku.rikka.app/download/), started through wireless debugging / ADB. Root mode is rejected.
- User-enabled Flip2 Layout Switcher accessibility service for automatic app detection.

Profiles can be saved without these permissions; hardware switching stays disabled until setup is complete. Other firmware is not assumed compatible. Shizuku must be started again after reboot on a stock, unrooted device.

## Setup (once)

Download the APK from Releases and open it with the Files app (allow installing unknown apps when Android asks). Open Flip2 Layout Switcher: a setup banner is shown until everything works, and the **Setup** screen detects completed steps when you come back to it:

1. **Add Flip2 Layout Switcher to Retroid's whitelist.** *Open Handheld Settings* → *Advanced* → *Whitelist application* → menu (⋮) → *Add whitelist*, tick Flip2 Layout Switcher and tap the save icon. On this firmware the stock launcher force-stops every app that is swiped away or cleared in Recents, and Android then turns the app's accessibility service off; whitelisted apps are not force-stopped. This step comes first so clearing Recents during setup cannot undo step 3.
2. **Allow controller changes (Shizuku).** *Get Shizuku* opens its Google Play page. Then start Shizuku with Wireless debugging; its own guide walks you through it:
   - Connect to Wi-Fi and turn on Developer options (Settings → *About handheld console* → tap *Build number* 7 times).
   - In Shizuku tap *Pairing* and **allow Shizuku's notifications** (a fresh install has them off, and the pairing code is typed into a notification).
   - In Settings → System → Developer options → *Wireless debugging*, choose *Pair device with pairing code* and enter the code in Shizuku's notification. Tap *Start* in Shizuku.
   - Back in Flip2 Layout Switcher tap *Allow access* and allow it in Shizuku's dialog.
3. **Allow app detection.** *Open permission settings*, tap Flip2 Layout Switcher under *Downloaded apps* and turn on *Use Flip2 Layout Switcher*. If Android 13 shows "Restricted setting", use **App info → three-dot menu → Allow restricted settings** and try again. The app never grants this permission through ADB.

If the accessibility service is turned off later, the setup banner returns. If Handheld Settings → Power → *Clean process when standby* is on, it force-stops apps when the screen turns off (it uses its own ignore list, not the whitelist); the Setup screen warns about this. Turn it off or add Flip2 Layout Switcher to the apps it ignores.

## Daily use

The main screen is a searchable two-column list of installed games and apps (apps that already have a profile are always listed). Choose Retro or Xbox; unsaved choices are marked with a dot and counted in the bar at the bottom until you tap **Save**. Leaving the app also saves. Tap the selected mode again to remove an app's profile.

There is no automation switch: automatic switching runs whenever setup is complete. Unassigned apps keep the system preference. Before the first profile is applied, the app remembers your Retroid mode; after leaving profiled apps it restores that mode. Switching between profiled apps does not replace the remembered preference. Choose your normal mode in the usual Retroid settings. The saved session survives process death.

Settings shows whether setup is complete, the update check (on by default, with *Check now*), About, open-source licenses and technical diagnostics. The app is English-only. Native controls work with touch or D-pad focus.

## Safety and privacy

Only three firmware settings can be written: `temp_abxy_layout_mode`, `flip_button_layout`, and `no_create_gamepad_button_layout` (always 0). Their meaning and write order were confirmed in the device's own preference code and through reversible runtime tests. There is no arbitrary command interface, property write, root command, controller-disconnect option, private Binder transaction, system-file edit, driver replacement or kernel modification.

The shell helper requires UID 2000 and the verified firmware fingerprint. Unknown/inconsistent settings fail closed. Settings readback, firmware-derived property readback and public InputManager vendor/product identity must agree before success is reported. A failed switch is reported and retried on the next app change. Partial setting failures attempt to restore the previous known mode.

The accessibility service uses window package/class metadata only; it cannot retrieve window contents or intercept keys. Notification shade, keyboard, permission windows, dialogs and game-assistant overlays neither reset the controller mode nor cancel a pending switch. Pending transitions are debounced and superseded by the next app. Cooldown prevents repeated reinitialization. Background operation is event-driven; transition verification is bounded, not continuous polling.

Installed apps, profiles and recent diagnostic events stay on the device (release builds do not write them to the system log; cloud backup and device-to-device transfer are excluded). No ads, telemetry, accounts or cloud storage. The only network access is the update check described below, which sends an anonymous request to the GitHub API and can be turned off in Settings. QUERY_ALL_PACKAGES is used for the explicit installed-app profile list. Diagnostic events contain recent package identifiers, not screen text. The optional button tester consumes controller buttons only inside the app while explicitly enabled; it does not log them.

## Updates and releases

Releases are distributed as a signed APK on GitHub Releases (not Google Play: Play restricts AccessibilityService and QUERY_ALL_PACKAGES use, and the app depends on Shizuku and one device's firmware).

When the app opens it checks this repository's GitHub Releases (at most every 6 hours; *Settings → Check for updates* turns it off, *Check now* forces it). If a newer version exists, a dialog offers **Update**: the APK is downloaded, checked to be a newer build of `app.flip2layout.switcher`, and handed to Android's package installer, which asks for confirmation (and, the first time, permission to install from Flip2 Layout Switcher). Android refuses an update signed with a different key. Release APKs are signed with the certificate whose SHA-256 is `11082b7a7043adc5bfe26841d634551724129e41d48d6b68c1624b157a78002c` (check with `apksigner verify --print-certs <apk>`). Debug builds and builds without a configured repository never check.

To publish a release:

1. Bump `appVersion` in `app/build.gradle.kts` (`MAJOR.MINOR.PATCH[-alpha|-beta|-rc[.N]]`); `versionCode` is derived from it. `updateRepo` in `gradle.properties` already points at this repository.
2. `.\gradlew.bat assembleRelease testDebugUnitTest lintRelease` with `keystore.properties` present (see *Release signing*).
3. Create a GitHub release whose tag is the version (for example `v0.4.0-beta`; pre-releases count) and attach `Flip2LayoutSwitcher-<version>-release.apk`. The first `.apk` asset of the newest non-draft release is used. Release notes are shown in the update dialog.

## Build and test

JDK 17+ and Android SDK platform 36. Set `sdk.dir` in an untracked `local.properties` or use `ANDROID_HOME`.

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
.\gradlew.bat assembleDebugAndroidTest
```

Debug APK: `app/build/outputs/apk/debug/Flip2LayoutSwitcher-<version>-debug.apk`. It uses the Android debug signer and is intended for beta/device tests.

### Release signing

Create the key once and keep the keystore and its passwords safe (a lost key means existing installs cannot be updated):

```powershell
keytool -genkeypair -v -keystore flip2-layout-switcher.keystore -alias flip2-layout-switcher -keyalg RSA -keysize 4096 -validity 10000
```

Then create an untracked `keystore.properties` in the project root (both files are git-ignored):

```properties
storeFile=flip2-layout-switcher.keystore
storePassword=...
keyAlias=flip2-layout-switcher
keyPassword=...
```

`.\gradlew.bat assembleRelease` then writes a signed `app/build/outputs/apk/release/Flip2LayoutSwitcher-<version>-release.apk`; without `keystore.properties` it produces an `...-release-unsigned.apk`. A release built with the new key cannot update a debug-signed install; uninstall the debug build first.

Run the UI test only on an explicitly selected device (do not run connected tests against all attached devices):

```powershell
adb -s YOUR_SERIAL install -r app/build/outputs/apk/debug/Flip2LayoutSwitcher-<version>-debug.apk
adb -s YOUR_SERIAL install -r app/build/outputs/apk/androidTest/debug/Flip2LayoutSwitcher-<version>-debug-androidTest.apk
adb -s YOUR_SERIAL shell am instrument -w -e class app.flip2layout.switcher.ProfileUiTest app.flip2layout.switcher.test/androidx.test.runner.AndroidJUnitRunner
```

The UI test checks app search, profile selection, Save, refresh and Activity recreation; it restores the original profile and never launches a game or requests hardware switching. It is skipped if the test app (Eden Nightly) is not installed.

`am instrument` force-stops the app before and after the run, which turns the accessibility service off (see above). Re-enable it afterwards, and verify automatic switching with normal manual device tests rather than instrumentation.

Developer tools (Windows PowerShell; `Capture-Frame.ps1` needs PowerShell 7): `tools/Snapshot.ps1 -Serial ... -Label ...` captures read-only firmware/settings/input diagnostics with identity checks. `tools/Compare-Snapshots.ps1` compares them locally. Device evidence and third-party UI reference screenshots are excluded from source distribution.

## Source layout

- `MainActivity`: compact app list with Save bar, setup, settings, licenses and diagnostics (English-only Material 3 UI).
- `ProfileStore` / `ModeSession`: persistent per-app preferences and original Retroid mode restoration.
- `ControllerShell`: narrowly scoped Shizuku UserService / AIDL with UID and firmware guards.
- `ControllerAccess`: serialized changes, bounded reconnect confirmation and backend availability.
- `ForegroundService` / `SwitchPlanner`: foreground events, debounce, cancellation, transient-window filtering.
- `docs/FEASIBILITY.md`: verified findings, unknowns and real-device results.
- `docs/UI_RESEARCH.md`: primary UI references and design decisions.

MIT license. Third-party license notices are listed in `THIRD_PARTY_NOTICES.md`. This is an independent community project, not an official Retroid application.
