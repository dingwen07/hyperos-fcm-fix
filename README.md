# HyperOS FCM Fix

**English** | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md)

HyperOS FCM Fix prevents Xiaomi HyperOS from freezing Google Play services or over-restricting apps that need timely Firebase Cloud Messaging (FCM) notifications. It uses Shizuku's ADB shell identity and does not require root.

[<img src="https://raw.githubusercontent.com/machiav3lli/oandbackupx/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="80">](https://github.com/dingwen07/hyperos-fcm-fix/releases)

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="80">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22net.extrawdw.apps.miuisucks.powerkeeper%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fdingwen07%2Fhyperos-fcm-fix%22%2C%22author%22%3A%22dingwen07%22%2C%22name%22%3A%22HyperOS%20FCM%20Fix%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%7D%22%7D)

## Features

- Keeps Google Play services (`com.google.android.gms`) in HyperOS's hidden no-restrictions list (`MILLET_NO_RESTRICT_APP`) and repairs the entry when PowerKeeper rewrites the list.
- Disables the known Greezer GMS limiter and restores GMS to its normal runtime allowlist.
- Offers optional FCM Connection Protection for CN Google Play services builds that may not reconnect promptly.
- Finds installed third-party FCM apps without requesting `QUERY_ALL_PACKAGES`.
- Provides separate Aurogon FCM protection, Auto unstop, HyperOS Autostart, and AOSP battery-optimization controls for each enabled app.
- Restores protection after reboots, app updates, or Shizuku restarts, and keeps diagnostic records for troubleshooting.

## Requirements and setup

The app runs only in the owner profile on a Xiaomi device with HyperOS. Its manifest requires Xiaomi's `com.miui.system` shared library, so unsupported devices cannot install it. It does not modify PowerKeeper, clear app data, or use root.

1. Install Shizuku from [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases), then start it through wireless debugging, ADB, or Sui. The Play Store build of Shizuku may have compatibility issues on Android 16 QPR2.
2. Install and open HyperOS FCM Fix, then grant its Shizuku permission.
3. Confirm that Google Play services shows **Protection active**. GMS is protected automatically and does not appear as an app you need to enable.
4. Enable only the apps for which timely notifications matter, then review their Aurogon, Auto unstop, Autostart, and battery settings.
5. Use **Apply now** after changing several settings or while troubleshooting. After a reboot, restart Shizuku and return to the app to confirm that protection has resumed.

> [!NOTE]
> To stop using HyperOS FCM Fix, first set each managed app's **HyperOS Autostart** to the state you want while the app and its Autostart management switch are still enabled. Then disable every managed app and reboot before uninstalling. Disabling an app removes its Aurogon and Auto unstop rules and returns managed battery optimization to **Optimized**; the remaining temporary system changes reset after reboot. HyperOS Autostart is the exception and keeps the last applied state.

> [!TIP]
> Start with AOSP battery optimization set to **Optimized**. Use **Unrestricted** only for an app whose notifications remain delayed, because allowing more background activity can increase battery use.

## Protection behavior

### Google Play services

The core fix preserves all existing entries in `Settings.System.MILLET_NO_RESTRICT_APP` and appends `com.google.android.gms` only when it is missing. A Shizuku UserService checks the setting every 30 seconds by default; 60- and 120-second intervals are also available.

Every full FCM protection pass also issues the equivalent of `dumpsys greezer IM GMS disable` and `dumpsys greezer LM add com.google.android.gms`. A pass occurs during the fixed recovery job, boot or app-update recovery, **Apply now**, and configurable periodic policy enforcement. These commands do not run in the 30/60/120-second polling loop. Boot or app update can queue both recovery and stale policy enforcement, so the commands may run twice close together. A failure of either Greezer command is reported as unavailable but does not by itself make WorkManager retry.

The UserService does not hold a wake lock. If Android suspends its process during Doze, polling pauses and resumes when the process can run again, so the selected interval is not exact. A fixed 15-minute WorkManager recovery job re-establishes the monitor and reapplies GMS and Aurogon protection when Shizuku is available; it also runs Auto unstop and, when FCM Connection Protection is enabled, finishes by requesting a GMS reconnect. WorkManager is subject to system scheduling and may run later during Doze.

**FCM Connection Protection** is a separate, optional workaround for CN GMS builds that may not reconnect promptly. While the UserService is runnable, it checks whether GMS has an established FCM socket on ports 5228–5230 and sends a targeted `com.google.android.intent.action.GCM_RECONNECT` broadcast when no connection is found or the check is unavailable. The recovery job also sends the broadcast whenever it actually runs. Turning this option off does not disable the `MILLET_NO_RESTRICT_APP` repair.

### Managed apps

The app list contains non-system packages that declare a receiver for `com.google.android.c2dm.intent.RECEIVE`. Each enabled app has four independent controls:

- Aurogon FCM protection: permits the FCM Intent through Xiaomi's broadcast control so it can be delivered to the app, including when delivery requires starting the app.
- Auto unstop (Android 16+): clears `FLAG_STOPPED` every 15 minutes so the app remains eligible to be started by FCM; it does not launch the app itself.
- HyperOS Autostart: permits the process start when FCM delivery targets an app with no running process, such as after the process has been killed. In that case it works together with the Aurogon rule. It manages both Xiaomi Autostart AppOps.
- AOSP battery optimization: mainly lets users suppress an app's background activity while keeping FCM unaffected. It applies Unrestricted, Optimized, or Restricted to the selected Android profiles.

On first enable, Aurogon, Auto unstop, and Autostart management are turned on. WeChat and Telegram are enabled by default and start with battery optimization managed as **Optimized**; other discovered apps start disabled with battery management off.

Disabling an app removes it from the managed Aurogon and Auto unstop sets while retaining its saved choices. If battery management was enabled, the app is changed to **Optimized** once. Its Autostart state is left unchanged.

**Apply now** processes every enabled app. A separate WorkManager job uses the **Periodical enforcement frequency** setting to reapply managed Autostart and battery policies automatically for every enabled app. Setting it to **Off** disables this periodic enforcement job, but does not stop GMS protection, the fixed 15-minute GMS and Aurogon recovery job, or Auto unstop.

Autostart and battery policies target the Android profiles selected in the app; the owner and XSpace profiles are selected by default. Android's unrestricted device-idle allowlist is package-wide, so Unrestricted and Optimized changes are not isolated per profile.

## Diagnostics

Open **Diagnostics** at the bottom of the main screen to inspect, refresh, copy, or clear session logs covering Shizuku connections, background jobs, privileged operations, and FCM repairs. The files are stored under `/storage/emulated/0/Android/data/net.extrawdw.apps.miuisucks.powerkeeper/files/logs/`. Logcat entries use the `PowerKeeperFix` prefix.

## Technical investigation

- [Xiaomi HyperOS GMS, FCM, and Greezer investigation](docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md)
- [PowerKeeper `MILLET_NO_RESTRICT_APP` rewrite investigation](docs/xiaomi-millet-no-restrict-app-rewrite-investigation.md)

## Build

Open the project in Android Studio and run the `app` configuration.
