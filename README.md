# Xiaomi PowerKeeper FCM Fix

An Android app for Xiaomi MIUI and HyperOS devices that uses Shizuku's ADB-shell identity to:

- keep `com.google.android.gms` in `Settings.System.MILLET_NO_RESTRICT_APP` without removing existing entries;
- disable Greezer's volatile explicit GMS limiter and restore GMS to its ordinary runtime allowlist;
- promptly restore the hidden no-restrictions entry when PowerKeeper regenerates the setting;
- discover Android users and persist each user ID, name, and WeChat-policy selection;
- periodically keep WeChat (`com.tencent.mm`) at Optimized or Restricted for the selected users instead of Unrestricted;
- protect its own fallback watchdog from HyperOS background limits.

## FCM protection

The durable package-specific fix reads the current `MILLET_NO_RESTRICT_APP` value, appends `com.google.android.gms` only when absent, writes the preserved list, and verifies the result. A daemon Shizuku UserService checks that setting every two seconds while the device is active, repairing PowerKeeper overwrites before the identified delayed-freeze paths normally run. Java timers do not wake a suspended device; PowerKeeper rewrites normally occur while the user is already interacting with its UI.

At service startup the app also runs these defense-in-depth commands:

```sh
dumpsys greezer IM GMS disable
dumpsys greezer LM add com.google.android.gms
```

The first command clears the runtime `mGmsLimitEnabled` flag. The second restores GMS to Aurogon's ordinary runtime allowlist. Neither replaces the `MILLET_NO_RESTRICT_APP` repair, which also covers the separate `PowerStrategyMode` freeze paths.

WorkManager remains a recovery/bootstrap fallback. A fixed 15-minute recovery job recreates the daemon Shizuku monitor after boot, app updates, or Shizuku restarts; a separate configurable job periodically reapplies the selected WeChat policy. WorkManager's timing is not used as the primary response to a PowerKeeper setting rewrite.

## Xiaomi-only installation

The manifest requires the APK-backed `com.miui.system` shared library published by Xiaomi firmware. Android PackageManager rejects installation when this library is absent. A second runtime guard verifies the `Xiaomi` manufacturer, the shared library, and the `com.miui.system` system package before scheduling or invoking Shizuku.

Xiaomi autostart gates are enabled for the app. A small manifest receiver listens only for `BOOT_COMPLETED` and the app-specific `MY_PACKAGE_REPLACED` broadcast, then restores the periodic schedule and starts an immediate recovery attempt.

## Safety model

The app does not use root, hidden APIs, UID-wide AppOps, uninstall, data clearing, UI automation, or destructive filesystem operations. It does not alter the PowerKeeper package. Its long-lived Shizuku UserService exposes only the fixed enforcement and FCM-monitor operations required by the app.

Android's device-idle allowlist is global per application ID. Removing WeChat from that allowlist is therefore not user-scoped; WeChat AppOps changes target only the Android users enabled in the app. Owner (`0`) and XSpace (`999`) default to enabled, while other discovered users default to disabled.

## Build

Open the project in Android Studio and run the `app` configuration. The target device needs Shizuku 11 or newer, started through wireless debugging/ADB or Sui, and the user must authorize HyperOS PowerKeeper FCM Fix in Shizuku.
