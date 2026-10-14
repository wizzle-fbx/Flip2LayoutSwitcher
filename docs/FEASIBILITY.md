# Feasibility and device findings — Retroid Pocket Flip 2

Last updated: 14 October 2026. Device: Retroid Pocket Flip 2, Android 13, firmware
`qti/kona/kona:13/TKQ1.240621.001/eng.RPFlip.20250501.115911:user/release-keys`.

No root, bootloader, firmware, driver, kernel or system-file change was made at any point. All device access used
a user-authorized ADB connection or Shizuku in ADB mode (UID 2000).

## Controller style mechanism (verified)

Retroid's own settings app (`com.rp.settings`, `ControllerStylePreference`) changes the controller style by writing
three `Settings.System` values in this order:

| Mode | `temp_abxy_layout_mode` | `flip_button_layout` | `no_create_gamepad_button_layout` |
| --- | ---: | ---: | ---: |
| Retro | 1 | 0 | 0 |
| Xbox | 0 | 1 | 0 |

- The firmware observes these settings and reconnects the built-in controller. It is not necessary to write any property:
  `persist.sys.gamepad.type` follows on its own (Retro `0`, Xbox `1`).
- The controller reappears with vendor `0x2022` and product `0x3001` (Retro) or `0x3002` (Xbox), readable through the
  public `InputManager` API.
- Writing the settings from a plain ADB shell switched the mode in both directions, and so did the app's Shizuku user
  service (UID 2000) for manual switching. These are vendor keys, not public `Settings.System` fields; Android does not
  let a normal app with `WRITE_SETTINGS` write non-public keys, so the app uses the narrow Shizuku service instead.
  Shizuku in root mode is rejected by the app.

The app only writes the three keys above, always with `no_create_gamepad_button_layout=0`, and reports success only when
settings readback, the firmware-derived property and the InputManager product ID all agree.

## Automatic switching (verified on device)

App-recorded events with the 0.2.0-beta build (accessibility detection + Shizuku shell):

| Time | Event | Result |
| --- | --- | --- |
| 21:21:08 | Eden (profile: Retro) came to the foreground; previous mode Xbox | Retro confirmed, controller reconnected |
| 21:28:38 | Android Settings (no profile) came to the foreground | Xbox restored and confirmed |

A second title was then tested: 9 Kings (`com.playdigious.ninekings`, profile Retro) switched to Retro when launched from
the home screen; the settings were last written by `com.android.shell` (the app's Shizuku service). That run exposed a
logging bug: when a later window event cancelled the switching job, `withContext(NonCancellable)` rethrew the
cancellation on return, so the result line and the cooldown timestamp were skipped. Bookkeeping now runs inside the
non-cancellable block.

A later 9 Kings run after *Clear all* did not switch and left no log line: an overlay or dialog window (not an
activity) during the 700 ms debounce cancelled the pending switch, and the game sent no further event. Transient
windows (overlays, dialogs, shade, keyboard, Game Assistant) now leave a pending switch in place; only screen off and
keyguard cancel it, and cancellations are logged with their reason. An attempt cancelled after `take()` also clears
the planner so the same app can switch on its next event. With Flip2 Layout Switcher in the vendor whitelist, the user
confirmed on device that *Clear all* keeps the service and 9 Kings switches correctly.

The persisted session at that point was `normal=XBOX, override=RETRO` → after leaving, `normal=XBOX`, matching the
remembered-preference design. ADB readback afterwards showed `temp_abxy_layout_mode=0`, `flip_button_layout=1`.

## Force-stop disables the accessibility service (verified)

- On this firmware, swiping an app away in Recents (or *Clear all*) in the stock launcher (`com.android.launcher3`)
  **force-stops** it. Exit info recorded `reason=USER REQUESTED, subreason=FORCE STOP` from the launcher's PID.
  The launcher's `RecentsView.removeTaskInternal` calls `ActivityManager.forceStopPackage` for every dismissed task
  except a few OTA packages and packages listed in the vendor `Settings.System` key `app_whiteList` (empty on this
  device). Launching a game does not kill other apps by itself.
- Android 13 removes a force-stopped package's accessibility service from `enabled_accessibility_services`. The user has
  to enable it again in Accessibility settings. The app never re-enables it through ADB.
- Instrumentation (`am instrument`) force-stops the target app as well, with the same result. Prefer manual device
  tests for automation; keep instrumented tests to UI-only checks.
- A package update (`adb install -r`) is *not* a force-stop: the service stayed enabled, rebound within about one second
  and the Shizuku shell reconnected.

Mitigations in 0.2.0-beta:
- **Recommended fix (vendor UI):** Retroid Settings → Advanced → Application → *Whitelist application* → menu →
  *Add whitelist* → Flip2 Layout Switcher. This screen (`AppWhiteListActivity`) writes `app_whiteList`, the exact list the
  launcher checks before force-stopping. The user adds it; the app never writes this key.
- Alternative: drag the Flip2 Layout Switcher card **down** in Recents to lock it (`com.sprd.ext.lockicon.TaskLockStatus`).
  Locked cards are skipped by *Clear all* and cannot be swiped away. The lock is stored per task affinity in the
  launcher's own preferences.
- App-side workarounds were tried and dropped: `excludeFromRecents` hid the card only in the background (the live
  tile could still be cleared) and made returning from Play/Settings during setup awkward; `onUserLeaveHint` is not
  called for the Recents gesture and `onStop` does not run before *Clear all*. The vendor whitelist is the fix.
- Package updates (`adb install -r`) were checked repeatedly: the service stays enabled each time.
- There is no automation switch any more: the setup banner and Settings status show whether automation can actually run
  (Shizuku ready, detector bound, whitelist), so it can never look active while the service is disconnected.
- The setup screen explains that force-stopping turns the accessibility permission off.
- The diagnostic event log survives process restarts.

## Fresh-install onboarding test (0.3.0-beta)

Flip2 Layout Switcher and Shizuku were uninstalled and Flip2 Layout Switcher was removed from the vendor whitelist, then the app
was installed from the Files app and set up only through its own Setup screen:

- The setup banner appeared on first launch; *Get Shizuku* opened Shizuku's Google Play install sheet directly.
- Installed/running/permission states of Shizuku were detected on return; *Allow access* opened Shizuku's dialog.
- A fresh Shizuku has its notifications off; Shizuku's pairing screen asks for them because the pairing code is typed
  into a notification. The instructions now say so. (In this run Shizuku was started with its documented "start by
  connecting to a computer" command over the existing ADB connection instead of typing the pairing code.)
- The accessibility list showed a misleading "On" summary while the service was off; the hint now says which entry and
  switch to use. No restricted-settings dialog appeared for this Files-app install on this firmware.
- *Open Handheld Settings* (exported `MainSettingsActivity`) led to the whitelist; the app can read `app_whiteList`
  and marked the step done.
- After saving 9 Kings as Retro: launch → Retro confirmed, Home → Xbox restored.
- Returning from Play needed two Back presses while the app was hidden from Recents; `excludeFromRecents` was removed
  (the whitelist now prevents force-stop) and the whitelist step was moved first.
- Handheld Settings → Power → *Clean process when standby* (`auto_clean_processes`, off by default) force-stops all
  apps except `auto_clean_ignored_packages` when the screen turns off; the Setup screen warns when it would stop the app.

## Pre-release review fixes (0.3.0-beta)

- Saving profiles (Save or leaving the app) now re-evaluates the app in front instead of dropping its pending switch.
  Verified on device: an unsaved Retro choice for 9 Kings was saved on leaving and applied when the game opened.
- A switch whose settings were written but whose reconnect was not confirmed in time keeps the override, so a late
  reconnect is not recorded as the user's normal mode. The session is rolled back only when nothing was written.
- The Shizuku user service is rebound after its process dies, and a bind that never connects is retried.
- A half-written layout pair (controller enabled, keys disagree) is repaired by the next full write instead of
  blocking switching.
- Cancelled switches no longer show the error banner; a failed session write returns an error instead of crashing.
- Quick successive Retro/Xbox taps update the draft atomically.

## Final audit (correctness, security, UI/docs)

- Control no longer stays "not ready" forever after one failed settings read: the bound shell is probed again on
  resume, when Shizuku reconnects and from *Try again*.
- Returning to a game after its switch was cancelled (another package in front, screen off, keyguard) switches it
  again; the app in front is tracked even while control is unavailable and applied once control becomes ready.
- A shell that dies mid-write or cannot roll back counts as "written", so a late reconnect is not taken as the
  user's own mode change.
- Updater: download URL must be `https://github.com/...`, APK capped at 50 MB and API response at 512 KB, a failed
  install session is abandoned, and a found update survives process restarts.
- `versionCode` is derived from `appVersion`, so the updater's name comparison and Android's code check agree.
- Release builds do not write diagnostics to logcat; cloud backup and device-to-device transfer are excluded.
- Open-source licenses (MIT for Shizuku API, Apache-2.0 for AndroidX/Compose/Kotlin) are shown in the app.
- Gradle distribution checksum is pinned.
- UI: one status priority for banner and Settings, a dismissible switch-error banner, a single result message for
  *Check now*, Back returns to where Setup was opened, the button tester stops itself, screen-reader labels for
  Retro/Xbox buttons and switches, scrollable plain-text release notes.

## In-app updates

Checks GitHub Releases (`updateRepo` in `gradle.properties`), downloads the APK, verifies package name and a higher
`versionCode`, and installs through a `PackageInstaller` session (system confirmation). Version comparison and release
selection are unit-tested; the download/install path has not run yet because no repository or signed release exists.
Test it with the first two signed releases before announcing updates.

## Not yet verified

- Notification shade and keyboard during a profiled game (return to the home launcher is verified above).
- Rapid app switching, screen lock/unlock, and reboot (Shizuku must be started again).
- Other titles: Albion Online, Azahar, GameHub. Whether each game accepts the controller reconnect without restarting.
- A/B/X/Y keycodes inside a game after each automatic switch.
- Behavior on other firmware builds (the app refuses to switch on any unverified fingerprint).
- Independent setup by another user (a clean install was tested by the maintainer; see above).

## Sources

- [OdinTools ControllerStyle](https://github.com/langerhans/OdinTools/blob/main/app/src/main/java/de/langerhans/odintools/models/ControllerStyle.kt) — the same three keys on another Retroid device; OdinTools' private Binder path is not used here.
- [Android Settings.System](https://developer.android.com/reference/android/provider/Settings.System)
- [AccessibilityEvent](https://developer.android.com/reference/android/view/accessibility/AccessibilityEvent)
- [Package visibility](https://developer.android.com/training/package-visibility/declaring)
- [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/)

Raw device dumps and extracted vendor APKs stay local (`work/`, `docs/evidence/` are git-ignored); only the relevant
values are quoted here.
