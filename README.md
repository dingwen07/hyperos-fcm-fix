# HyperOS FCM Fix

An Android app for Xiaomi HyperOS devices that uses Shizuku's ADB-shell identity to:

- keep `com.google.android.gms` in `Settings.System.MILLET_NO_RESTRICT_APP` without removing existing entries;
- disable Greezer's volatile explicit GMS limiter and restore GMS to its ordinary runtime allowlist;
- promptly restore the hidden no-restrictions entry when PowerKeeper regenerates the setting;
- discover installed third-party FCM receiver apps without requesting `QUERY_ALL_PACKAGES`;
- maintain an app-selected Aurogon FCM allowlist and HyperOS Autostart policy;
- apply per-app AOSP Battery optimization as Unrestricted, Optimized, Restricted, or Don’t change for selected Android users;
- periodically restore AppOps and battery policies only for apps that opt into periodic enforcement;
- protect its own fallback watchdog from HyperOS background limits.

## FCM protection

The durable package-specific fix reads the current `MILLET_NO_RESTRICT_APP` value, appends `com.google.android.gms` only when absent, writes the preserved list, and verifies the result. A daemon Shizuku UserService checks that setting and the app-managed Aurogon rules every two seconds while the device is active, repairing PowerKeeper overwrites before the identified delayed-freeze paths normally run. Java timers do not wake a suspended device; PowerKeeper rewrites normally occur while the user is already interacting with its UI.

At service startup the app also runs these defense-in-depth commands:

```sh
dumpsys greezer IM GMS disable
dumpsys greezer LM add com.google.android.gms
```

The first command clears the runtime `mGmsLimitEnabled` flag. The second restores GMS to Aurogon's ordinary runtime allowlist. Neither replaces the `MILLET_NO_RESTRICT_APP` repair, which also covers the separate `PowerStrategyMode` freeze paths.

WorkManager remains a recovery/bootstrap fallback. A fixed 15-minute recovery job recreates the daemon Shizuku monitor after boot, app updates, or Shizuku restarts. A separate configurable job refreshes FCM protection, the opted-in apps' Autostart and AOSP Battery optimization policies, and this app's own HyperOS background permissions; it can be disabled without disabling the dedicated FCM recovery job or a running Shizuku monitor. WorkManager's timing is not used as the primary response to a PowerKeeper setting rewrite.

The app list is scoped to non-system packages exposing a receiver for `com.google.android.c2dm.intent.RECEIVE`; system and updated-system apps are excluded, while previously saved packages remain visible if they are no longer installed. Enabling an app activates its saved detail configuration. On a first enable, Aurogon and management of both Xiaomi Autostart AppOps are enabled; later enable/disable cycles remember the Aurogon, Autostart, battery, and periodic-enforcement selections. Disabling an app removes it from this app's Aurogon rule. If AOSP Battery optimization management was enabled, disabling also applies Optimized once; otherwise it leaves battery state unchanged. Autostart is never changed by app-off. All later startup, periodic, and manual full passes exclude that app until it is enabled again. Aurogon is an independent detail switch: disabling it does not disable the app or stop its other configured policies. Packages in `HYPEROS_AUTO_UNRESTRICTED_PACKAGES` (currently `com.tencent.mm` and `org.telegram.messenger`) default to app enabled, Aurogon on, Autostart management on and Enabled, battery management on and Optimized, and periodic enforcement on. Other packages default to app disabled with both Autostart and battery management off; their retained selector defaults are Disabled and Optimized. That package set is used only to seed defaults. Individual controls use targeted Shizuku operations, so changing one setting does not run the full enforcement pass. The explicit **Apply now** action processes every enabled app; startup uses the same enabled-app guard plus the per-app periodic-enforcement filter when the last pass is stale.

Autostart and AOSP Battery optimization each have an explicit management switch. A switch being off suppresses that setting's AppOps changes while retaining its selected value, and hides its Material 3 selector. Turning management on reveals the selector and applies the retained value. The battery selector is ordered Unrestricted, Optimized, Restricted.

Bulk enforcement reconciles GMS and Aurogon once, snapshots installed packages once for each selected Android user, and then processes at most 16 app policies per shell script. Apply now, periodic WorkManager, and stale-on-start enforcement all use this bounded path. Intermediate chunks omit manager self-protection and `appops write-settings`; those operations run only once per bulk pass. The Apply now button receives per-app completion markers from within each chunk and displays exact completed/total progress.

## Diagnostics

The in-app diagnostics viewer combines WorkManager runs, Shizuku connection events, privileged commands, and FCM repairs into rotating session files. UI interactions are not logged. Open **Diagnostics** at the bottom of the main screen to inspect selectable text, refresh an active session, or clear all sessions. Logcat entries use the `PowerKeeperFix` prefix. The app retains at most 20 sessions, rolls files at approximately 1 MB, and displays the latest 200 KB of a large session.

## Xiaomi-only installation

The manifest requires the APK-backed `com.miui.system` shared library published by Xiaomi firmware. Android PackageManager rejects installation when this library is absent. A runtime guard verifies Android owner user 0, the `Xiaomi` manufacturer, the shared library, and the `com.miui.system` system package before scheduling or invoking Shizuku. Secondary-user and XSpace instances remain inactive; the owner-user instance can still manage the selected Android users' per-app policies.

Both Xiaomi autostart AppOps are enabled for the app on owner user 0: enforcement operation `10008` and Security Center switch-state operation `10053`. Self-protection also permits Xiaomi's boot-completed (`10007`), background-activity-start (`10021`), and foreground-service (`10023`) gates. A small manifest receiver listens only for `BOOT_COMPLETED` and the app-specific `MY_PACKAGE_REPLACED` broadcast, then restores the periodic schedule and starts an immediate recovery attempt.

## Safety model

The app does not use root, hidden APIs, UID-wide AppOps, uninstall, data clearing, UI automation, or destructive filesystem operations. It does not alter the PowerKeeper package. Its long-lived Shizuku UserService exposes only the fixed enforcement and FCM-monitor operations required by the app.

Android's device-idle allowlist is global per application ID. Unrestricted/Optimized allowlist changes are therefore not user-scoped; per-user AppOps changes target only the Android users enabled in the app. Owner (`0`) and XSpace (`999`) default to enabled, while other discovered users default to disabled.

## Technical investigation

The sanitized device and framework investigation behind the FCM protection design is in [docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md](docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md).

## Build

Open the project in Android Studio and run the `app` configuration. The target device needs Shizuku 11 or newer, started through wireless debugging/ADB or Sui, and the user must authorize HyperOS FCM Fix in Shizuku.
