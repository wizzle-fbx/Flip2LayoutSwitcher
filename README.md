# Flip2 Layout Switcher

Automatic Retro / Xbox controller layout per game on the **Retroid Pocket Flip 2**.

Pick Retro or Xbox for a game once. When you open it (from any launcher), the layout switches by itself; when you leave, your normal setting comes back. No root.

Flip 2 only, tested on the firmware with build number ending in `20250501`. On other firmware the app does not change anything.

## Install

1. Download the APK from [Releases](https://github.com/wizzle-fbx/Flip2LayoutSwitcher/releases) and open it.
2. Open the app and follow the **Setup** screen:
   - add the app to Retroid's whitelist,
   - install [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api), start it and allow access,
   - turn on the accessibility service.

After a reboot, open Shizuku and tap **Start** again. New versions are offered inside the app.

## Privacy

The app only changes Retroid's own Retro/Xbox setting. The accessibility service only sees which app is open, not what is on screen or which keys you press. No ads, tracking or accounts; the only network access is the update check against GitHub (can be turned off in Settings).

---

MIT license. Not an official Retroid app. Developer notes: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
