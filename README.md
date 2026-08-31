# HyperOS FCM Fix

**English** | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

HyperOS FCM Fix helps restore timely Firebase Cloud Messaging (FCM) notifications on Xiaomi HyperOS devices that freeze Google Play services or restrict apps in the background. It uses Shizuku's ADB shell identity and does not require root.

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22net.extrawdw.apps.miuisucks.powerkeeper%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fdingwen07%2Fhyperos-fcm-fix%22%2C%22author%22%3A%22dingwen07%22%2C%22name%22%3A%22HyperOS%20FCM%20Fix%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%7D%22%7D)

## What it does

- Protects Google Play services (`com.google.android.gms`) from the HyperOS and Greezer restrictions known to break FCM connections.
- Finds installed third-party apps that receive FCM messages and lets you choose which ones to protect.
- Provides independent controls for Aurogon FCM protection, Auto unstop, HyperOS Autostart, and AOSP battery optimization.
- Monitors the relevant HyperOS settings while Shizuku is available and repairs changes made by PowerKeeper.
- Uses recovery jobs after reboots, app updates, or Shizuku restarts.

## How to use

1. Install Shizuku from [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases), then start it through wireless debugging, ADB, or Sui. Note: the Shizuku version on Play store may have compatibility issues on Android 16 QPR2.
2. Install and open HyperOS FCM Fix, then grant its Shizuku permission.
3. Confirm that Google Play services shows **Protection active**. GMS is protected automatically and does not need to be selected from the app list.
4. Enable only the apps for which timely notifications matter. Review each app's Aurogon, Auto unstop, Autostart, and battery settings; the defaults are a good starting point.
5. Use **Apply now** after changing several settings or when troubleshooting. After a reboot, restart Shizuku and return to the app to confirm that protection has resumed.

> [!TIP]
> Start with AOSP battery optimization set to **Optimized**. Use **Unrestricted** only for an app whose notifications remain delayed, because allowing more background activity can increase battery use.

## Technical overview

Using Shizuku's ADB shell identity, the app can:

- keep `com.google.android.gms` in `Settings.System.MILLET_NO_RESTRICT_APP` without removing existing entries;
- disable Greezer's volatile, explicit GMS limiter and restore GMS to its ordinary runtime allowlist;
- promptly restore the hidden no-restrictions entry when PowerKeeper regenerates the setting;
- discover installed third-party FCM receiver apps without requesting `QUERY_ALL_PACKAGES`;
- maintain an app-selected Aurogon FCM allowlist and HyperOS Autostart policy;
- apply per-app AOSP battery-optimization policies—Unrestricted, Optimized, Restricted, or Don't change—for selected Android users;
- periodically restore managed AppOps and battery policies for every enabled app;
- periodically clear `FLAG_STOPPED` for selected FCM apps without launching them; and
- protect its own fallback watchdog from HyperOS background restrictions.

## FCM protection

The durable, package-specific fix reads the current `MILLET_NO_RESTRICT_APP` value and appends `com.google.android.gms` only when it is absent, preserving the other entries. A long-running Shizuku UserService performs this check every 30 seconds by default while it is runnable; the app also offers 60- and 120-second intervals. At the end of each poll, the default-on **FCM Connection Protection** checks both IPv4 and IPv6 socket tables for an established Google Play services connection to remote port 5228–5230. A match skips the ordinary targeted `GCM_RECONNECT`; no match refreshes the cached GMS UID, records the UID in diagnostics, and sends the request. An unavailable probe fails open by sending, and one request is mandatory every 10 runnable minutes even while sockets match. Users can turn off these reconnect requests without disabling `MILLET_NO_RESTRICT_APP` repair. Healthy matched polls remain silent. The loop remains entirely inside the UserService and holds no wake lock, so Java timers cannot wake a suspended device and the selected interval is not a wall-clock guarantee during Doze. Aurogon rules are reconciled immediately during protection/configuration operations, with the existing 15-minute recovery work as a safety net.

No-match diagnostics also include the currently configured polling interval.

At startup, the service also runs these defense-in-depth commands:

```sh
dumpsys greezer IM GMS disable
dumpsys greezer LM add com.google.android.gms
```

The first command clears the runtime `mGmsLimitEnabled` flag. The second restores GMS to Aurogon's ordinary runtime allowlist. Neither command replaces the `MILLET_NO_RESTRICT_APP` repair, which also covers the separate `PowerStrategyMode` freeze paths.

WorkManager provides a recovery and bootstrap fallback. A fixed 15-minute recovery job recreates the Shizuku monitor after a reboot, app update, or Shizuku restart, and clears `FLAG_STOPPED` for enabled FCM apps whose Auto unstop switch is on. A separate, configurable job refreshes FCM protection, every enabled app's managed Autostart and AOSP battery-optimization policies, and this app's own HyperOS background permissions. It can be disabled without disabling the dedicated FCM recovery, Auto unstop, or a running Shizuku monitor. WorkManager timing is not the primary response to a PowerKeeper setting rewrite.

The app list is limited to non-system packages that expose a receiver for `com.google.android.c2dm.intent.RECEIVE`. System and updated-system apps are excluded, while previously saved packages remain visible if they are no longer installed.

Enabling an app activates its saved configuration. The first time an app is enabled, Aurogon, Auto unstop, and management of both Xiaomi Autostart AppOps are enabled. Later enable and disable cycles preserve the Aurogon, Auto unstop, Autostart, and battery selections. Disabling an app removes it from this app's Aurogon and Auto unstop package sets. If AOSP battery-optimization management was enabled, disabling the app also applies Optimized once; otherwise, it leaves the battery state unchanged. Autostart is never changed when the app is disabled. All subsequent startup, periodic, and manual full passes exclude the app until it is enabled again. Aurogon and Auto unstop are independent settings: disabling either one does not disable the app or stop its other configured policies. Auto unstop runs in the independent fixed 15-minute FCM recovery job.

Packages in `HYPEROS_AUTO_UNRESTRICTED_PACKAGES` (currently `com.tencent.mm` and `org.telegram.messenger`) default to the app being enabled, with Aurogon and Auto unstop on, Autostart management on and set to Enabled, and battery management on and set to Optimized. Other packages default to the app being disabled, with Aurogon, Auto unstop, Autostart management, and battery management off; their retained selector defaults are Disabled and Optimized. This package set is used only to seed defaults.

Individual controls use targeted Shizuku operations, so changing one setting does not run the full enforcement pass. The explicit **Apply now** action processes every enabled app and runs Auto unstop for selected packages. Periodic WorkManager and stale-on-start enforcement process every enabled app while respecting each Autostart and battery management switch. The global periodical-enforcement frequency, including Off, controls these AppOps and battery passes, while the fixed 15-minute FCM recovery job handles Auto unstop independently.

Autostart and AOSP battery optimization each have an explicit management switch. Turning a management switch off prevents AppOps changes for that setting while retaining its selected value, and hides its Material 3 selector. Turning management on reveals the selector and applies the retained value. The battery selector is ordered Unrestricted, Optimized, Restricted.

Bulk enforcement reconciles GMS and Aurogon once, takes one snapshot of installed packages for each selected Android user, and then processes at most 16 app policies per Binder command batch. **Apply now**, periodic WorkManager, and stale-on-start enforcement all use this bounded path. Intermediate chunks omit manager self-protection and `appops write-settings`; those operations run only once per bulk pass. The **Apply now** button receives per-app completion markers from each chunk and displays exact completed and total counts.

## Diagnostics

The in-app diagnostics viewer combines WorkManager runs, Shizuku connection events, privileged commands, and FCM repairs into rotating session files. UI interactions are not logged. Open **Diagnostics** at the bottom of the main screen to inspect selectable text, refresh an active session, or clear all sessions. Logcat entries use the `PowerKeeperFix` prefix. The app retains at most 20 sessions, rolls files at approximately 1 MB, and displays the latest 200 KB of a large session.

## Xiaomi-only installation

The manifest requires the APK-backed `com.miui.system` shared library provided by Xiaomi firmware. Android's Package Manager rejects installation when this library is absent. A runtime guard verifies Android owner user 0, the `Xiaomi` manufacturer, the shared library, and the `com.miui.system` system package before scheduling work or invoking Shizuku. Secondary-user and XSpace instances remain inactive; the owner-user instance can still manage per-app policies for the selected Android users.

Both Xiaomi Autostart AppOps are enabled for the app on owner user 0: enforcement operation `10008` and Security Center switch-state operation `10053`. Self-protection also permits Xiaomi's boot-completed (`10007`), background-activity-start (`10021`), and foreground-service (`10023`) gates. A small manifest receiver listens only for the `BOOT_COMPLETED` and app-specific `MY_PACKAGE_REPLACED` broadcasts, then restores the periodic schedule and starts an immediate recovery attempt.

## Safety model

The app does not use root, UID-wide AppOps, uninstall operations, data clearing, UI automation, or destructive filesystem operations. It does not modify the PowerKeeper package. Its long-running Shizuku UserService exposes only the fixed enforcement and FCM-monitoring operations required by the app. Inside that shell-identity service, Android system commands are dispatched through each system service's Binder shell or dump entry point instead of spawning `/system/bin` child processes.

Android's device-idle allowlist is global per application ID. Unrestricted and Optimized allowlist changes are therefore not user-scoped; per-user AppOps changes target only the Android users enabled in the app. Owner (`0`) and XSpace (`999`) users are enabled by default, while other discovered users are disabled by default.

## Technical investigation

The sanitized device and framework investigation behind the FCM protection design is documented in [docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md](docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md). A focused report explains [when PowerKeeper rewrites `MILLET_NO_RESTRICT_APP` and why it needs a prompt watchdog](docs/xiaomi-millet-no-restrict-app-rewrite-investigation.md).

## Build

Open the project in Android Studio and run the `app` configuration. Install the latest [Shizuku GitHub release](https://github.com/RikkaApps/Shizuku/releases) on the target Xiaomi device and start it through wireless debugging, ADB, or Sui. The user must authorize HyperOS FCM Fix in Shizuku.
