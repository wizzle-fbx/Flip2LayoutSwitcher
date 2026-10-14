# UI research and product decisions — 14 October 2026

Reviewed primary sources and actual screenshots, rather than copying a visual style wholesale.

| Reference | Observed pattern | Decision for this app |
| --- | --- | --- |
| [OdinTools app overrides](https://github.com/langerhans/OdinTools/blob/main/docs/app_overrides.png) and [profile editor](https://github.com/langerhans/OdinTools/blob/main/docs/override_config.png) | Icon/name list, separate editor containing controller/performance/fan choices and Save | Retain readable app rows; put the only profile choice directly in the row, saved with one Save bar. No multi-setting editor. |
| [Key Mapper accessibility setup dialog](https://github.com/keymapperorg/KeyMapper/blob/develop/base/src/main/java/io/github/sds100/keymapper/base/onboarding/SetupAccessibilityServiceDialog.kt) | Permission explanation before system settings; separate help for restricted settings | Explain app detection once, provide an explicit system settings button and contextual restricted-settings help. Do not grant accessibility through shell. No source code copied. |
| [Android single-choice segmented buttons](https://developer.android.com/develop/ui/compose/components/segmented-button) | Adjacent mutually exclusive choices | Native Material 3 Retro/Xbox segmented control per app. |
| [Android touch target guidance](https://developer.android.com/guide/topics/ui/accessibility/apps) | At least 48 dp interactive targets | Minimum 48 dp selections and icon buttons, D-pad-focusable native controls. |

## Product flow

Revised after device feedback (14 October 2026): the app is set up once and then forgotten.

- Home: one compact row (search, refresh, settings) and a two-column icon/name list with Retro/Xbox per app. No title bar, automation switch or filter chips; the list shows installed games/apps plus anything that already has a profile.
- Choices are drafted and marked with a dot; a bottom bar counts unsaved changes and offers **Save** with a "Saved" confirmation. Leaving the app also saves, and the draft survives Activity recreation (a controller reconnect recreates the Activity).
- Tapping the selected mode again removes the profile; unassigned apps keep the normal Retroid mode. There is no per-row overflow menu or launch-from-app path.
- Automation has no on/off switch: it runs whenever Shizuku control and app detection both work. Until then a single setup banner is shown.
- Setup screen: Retroid whitelist (vendor UI), Shizuku, accessibility, in that order, with completed steps detected.
- Settings: setup status, the update check (on by default, *Check now*), About, open-source licenses and technical diagnostics. No manual mode, resume-after-restart or connection controls.
- English-only UI (Retroid's own menus are not translated either); installed app names remain their own labels. Setup order: whitelist, Shizuku, accessibility.

Compatibility with other firmware still needs independent user tests.
