# HyperOS 4 nighttime GMS freezing and a non-root workaround

Investigation date: 2026-10-05–06. Findings are specific to the installed China ROM below.

## Findings

On the tested HyperOS 4 build, disabling Xiaomi's older **nighttime battery saving** setting did not prevent a separate framework policy from freezing Google Play services (GMS) during nighttime deep Doze. GMS was already in `MILLET_NO_RESTRICT_APP`, its known Greezer GMS limiter was disabled, and Android's system Doze exemptions were present. Nevertheless, the OEM freezer recorded `nightDoze` freezes and the established FCM socket disappeared.

A non-root workaround was reproduced while keeping deep Doze enabled: add `com.google.android.gms` to the shared game predownload freeze-exemption setting, thaw owner GMS through an existing Xiaomi broadcast, then request GMS reconnection. Two short subsequent deep-idle entries retained unfrozen owner GMS and an established FCM socket. Removing the exemption allowed freezing and socket loss again.

This is an internal OEM workaround, not a documented Xiaomi API. Actual push delivery, overnight battery use, reboot/OTA behavior, and recovery of already-frozen clone GMS were not validated. No same-device older-ROM comparison established exactly when Xiaomi introduced this policy.

## Tested environment and evidence scope

| Component | Version |
| --- | --- |
| Device | Xiaomi 18 Fold, model `2608BPX34C` |
| ROM | China `OS4.0.21.0.XPNCNXM` |
| Android | 17 / API 37 |
| PowerKeeper | `4.2.00` / `40200` |
| SecurityCenter | `13.6.3-260924.0.1` / `40001363` |
| Joyose | `2.5.20` / `520` |
| Google Play services | `26.36.35 (260400-991383798)` / `263635035` |
| HyperOS FCM Fix during device probe | `0.2.3` / `9` |

The investigation combined targeted runtime state and logs with selected classes from the actual installed framework and OEM APKs. Decompiled control flow was checked against instruction-level output when structured decompilation failed. Raw device dumps are intentionally excluded because they can contain unrelated private information.

Artifact identifiers for reproducibility:

| Installed artifact | SHA-256 |
| --- | --- |
| `miui-services.jar` | `a0b2fe285ab3f0af7a0c408762aab0c22a5142e05d3ff868a742f68076c5e7c3` |
| `PowerKeeper.apk` | `d12c1ac9fff20698baa677c19e0922ab0efcaa6f5cf2bf5a641cd797608f71cd` |
| `SecurityCenter.apk` | `bb47f3a983b5a8624d553fa19ae7904eacf6c3d1a60a9106f496251b4d976510` |
| `Joyose.apk` | `b866dde653a23d687c9b540796c59cef3025295f70de10215da7ea649f09359d` |
| `SettingsProvider.apk` | `104d5fb6b604cfd9844f0ae8d81d8eecaa6874094ad4d9fff5e6ac3f90a0528c` |

## Why the existing settings did not prevent the freeze

PowerKeeper's `PhoneSleepModeController` reported `isUserOff=true`, corroborating that the preexisting nighttime battery-saving option was off. Deep Doze and light Doze remained enabled; the relevant captures had the screen off, the phone unplugged, and deep state `IDLE`. Ordinary battery saver and DND were off.

The decisive policy lived in `miui-services.jar`:

1. `power.Utils.isNight()` checks the local hour: `hour <= 7 || hour >= 23`, meaning **23:00 through 07:59**.
2. `AurogonImmobulusMode.isNightDeepIdleStationary()` combines that predicate with `PowerManager.isDeviceIdleMode()` and screen-off state. It does not consult the older PowerKeeper sleep toggle. Despite its name, this method does not directly test motion or charging.
3. `isNoRestrictFreezeable()` allows recognized messaging packages to remain exempt, but makes other no-restrictions packages eligible under the nighttime predicate.
4. `AurogonFilterManager`'s no-restrictions filter, bit 64, therefore stops being an unconditional exemption at night.
5. `power.EventReceiver` schedules a daily 23:00 `NIGHT_FREEZE` alarm and also triggers the pass on nighttime deep-idle entry.
6. `power.ActionExecute.dealNightFreeze()` walks the no-restrictions packages, resolves owner UIDs, and requests freezes with reason `nightDoze`.

This explains why repairing `MILLET_NO_RESTRICT_APP` more frequently cannot by itself solve this particular restriction. The existing `IM GMS disable` and `LM add` commands address other paths. Ordinary Android Doze exemptions also do not control an independent OEM freezer. See [Android's documented Doze behavior](https://developer.android.com/training/monitoring-device-state/doze-standby) and [FCM message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority) for the platform delivery expectations.

The direct `cmd greezer whitelist` mutation was unavailable to ADB/Shizuku shell on this ROM: the installed permission check explicitly rejects UID 2000. No root operation or deep-Doze disabling was used.

## The game setting and recovery broadcasts

### Format and exemption

`Settings.System["com.miui.game.allowlist"]` stores package names separated by **underscores**:

```text
com.example.game_com.google.android.gms_com.example.othergame
```

The framework uses Java `String.split("_")` and exact package matching, without trimming each token. Spaces, JSON, commas, and substrings do not express membership. A package name containing an underscore cannot be represented intact by this parser. Trailing empty tokens are discarded.

`GreezeManagerService.freezeUids()` performs `preCheckInterest()` before freezing. That check rejects a freeze when `AurogonImmobulusMode.isGameAllowListApp(uid)` matches the UID's package. This exemption has **no nighttime predicate**: while enabled, it can protect GMS from this lower-level freeze path during the day too. It does not disable deep Doze or stop all higher-level attempts to freeze GMS.

Preserve existing packages when appending GMS. The following single-package write was appropriate only because the tested phone's key was originally absent:

```sh
adb shell settings --user 0 put system com.miui.game.allowlist com.google.android.gms
```

Membership prevents subsequent freezes; it does **not** thaw an already-frozen process.

### Thaw and reconnect

The installed system receiver accepts the exported action below without a receiver permission. It reads `pkgName` and integer `option`:

```sh
adb shell am broadcast --user 0 \
  -a com.xiaomi.mipush.REFRESH_WHITE_LIST_ACTION -p android \
  --es pkgName com.google.android.gms --ei option -1

adb shell am broadcast --user 0 \
  -a com.google.android.intent.action.GCM_RECONNECT -p com.google.android.gms
```

`updateMipushProtectAppList()` resolves the package and thaws its UID **before** interpreting the option. `-1` changes no MiPush protection membership. This uses Xiaomi's thaw helper; it does not route FCM through MiPush. Membership-changing options were not tested.

Broadcast completion only confirms dispatch. It does not prove a new FCM connection or notification delivery.

## Controlled device result

The user locked the screen. A bounded probe used `force-inactive` and `force-idle deep` to compare a baseline with two subsequent idle entries. It never disabled deep Doze. The table reports owner GMS; socket counts came from UID-filtered `/proc/net/tcp` and `tcp6` entries for established connections to ports 5228–5230.

| Stage | Deep state / enabled | Owner GMS frozen | Established FCM sockets |
| --- | --- | --- | --- |
| Before controlled idle entry | `QUICK_DOZE_DELAY` / `1` | No | 1 |
| Baseline deep idle, no game exemption | `IDLE` / `1` | Yes, all five observed processes | 0 |
| Exemption written, before thaw | `IDLE` / `1` | Yes | 0 |
| After targeted thaw | `IDLE` / `1` | No | 0 |
| Five seconds after reconnect request | `IDLE` / `1` | No | 0 |
| First subsequent idle entry and 20 seconds later | `IDLE` / `1` | No | 1 |
| Second subsequent idle entry and 20 seconds later | `IDLE` / `1` | No | 1 |

The socket appeared about 12 seconds after the reconnect request. Logs recorded baseline `nightDoze`, a `mipush` thaw, and repeated game-predownload exemptions afterward. Following cleanup, a successful OEM freeze and zero established FCM sockets were recorded again.

Forced-idle state was released, `deep=1` and `force=false` were verified, and the originally absent game key was restored. Cleanup wrote an empty value before deleting the key so the runtime cache could consume the removal. Sequential process/socket snapshots crossed the final freeze transition; a process snapshot taken just before freezing must not be treated as a simultaneous steady-state result.

These observations establish a short freeze/socket comparison. They do not establish overnight reliability or actual test-push delivery.

## Who rewrites the list, and whether it can be made permanent

Exact-key references were scanned in the installed framework, PowerKeeper, SecurityCenter, Joyose, GameCenter, GameCenter SDK, SecurityAdd, SecurityCore, and SettingsProvider. The identified production writers were game **predownload** implementations in SecurityCenter and Joyose; the framework also has a debug whole-string write path. This scan does not exclude every other package, native code, dynamic code, or future cloud update.

| Writer | When it changes membership | Scheduling evidence |
| --- | --- | --- |
| SecurityCenter `nn.d` / `PreDownloadJobService` | Append before a game task; remove after it; clear the **entire** setting on `stopDownload`, timeout, or service error | Job 4096 uses task-derived minimum latency; requires unmetered network, device idle, battery/storage not low; no fixed periodic rewrite |
| Joyose `utils.r` / `PreDownloadManager` | Append before binding a game download service; attempt removal on unbind/binding death; clear the **entire** setting when the queue empties or a session times out | Conditional job 17493102 uses 12 hours, requires charging, idle and unmetered network; a download session has a two-hour timeout |

Both components perform ordinary whole-string read/modify/write operations. They can erase a manually added GMS entry when clearing their shared list. Joyose's 12-hour job and two-hour timeout are **not guaranteed allowlist rewrite intervals**. On this phone, user predownload switches were disabled and neither downloader job was scheduled during the read-only follow-up. No automatic rewrite cadence was measured; the lifecycle behavior above is installed-code evidence.

Joyose's per-package removal is ineffective on this installed build: its parsed-list helper returns an empty list, so removal returns without writing. This was checked against instruction-level output. Whole-list clears remain effective. A debug gate can also substitute four hours for the periodic job; neither interval measures actual setting writes.

The system **selected-game/Game Turbo UI** uses a separate database at `content://com.miui.securitycenter.gamebooster/gamebooster`. Adding the Google app there left the freeze key absent. Predownload switches/providers and the game download Binder interface control supported download tasks; they do not provide permanent arbitrary GMS exemption. No inspected legitimate UI, provider, or Binder API supplied such enrollment.

### Stored value versus loaded framework cache

`AurogonImmobulusMode.init()` registers a settings observer but does not initially read this key. `updateGameAllowList()` is called from the observer. Consequently, a persisted value alone is insufficient evidence that a newly restarted framework loaded it.

The installed SettingsProvider can suppress notification for an unchanged ordinary PUT when setting metadata is also unchanged. A same-value write is therefore not a reliable reload signal. To reload an already-present GMS entry at a new UserService's first enable, the app toggles **one trailing underscore**. This changes the stored string while preserving effective package membership under the inspected Java split behavior. It does not remove GMS temporarily. Normal healthy polls do not touch the value.

Another cache detail affects rollback: a null read returns early without clearing cached membership. **Deleting the key alone can leave protection active.** Write the remaining package list, or a non-null empty string when none remain. The app keeps that empty value on cleanup rather than racing an observer with deletion.

## Users, clone profiles, and GMS UIDs

Android separates app data and processes by user/profile; see [AOSP multi-user documentation](https://source.android.com/docs/devices/admin/multi-user). The connected phone had distinct owner and user-999 GMS UIDs and processes. Their FCM transport state must be checked separately. An owner socket does not establish clone connectivity.

The game exemption compares package names obtained from target UIDs, so the single owner-context setting can match GMS in both profiles when Greezer recognizes them. Framework `updateTargetList()` specifically retains GMS outside its normal owner/clone checks. This is code-supported prevention coverage, not a live clone-recovery result.

The MiPush helper is narrower: it calls owner-context `PackageManager.getPackageUid(packageName, 0)`. Here `0` is **flags**, not a user ID argument. The receiver accepts no target UID/user extra and is registered normally, rather than as a cross-user receiver. Changing `am broadcast --user 0` to `--user 999` does not make it thaw clone GMS.

The app's socket probe, thaw, and reconnect target **owner GMS**. The shared exemption may prevent future clone freezes, but immediate recovery of already-frozen clone GMS remains unverified. A profile-aware reconnect must target the appropriate Android user and still requires that profile's frozen process to become runnable.

## Retry behavior and battery implications

During the test, Xiaomi continued attempting a rejected freeze roughly every five seconds. `ActionExecute.onFreezeFailed()` leads to a 5,000 ms delayed retry through a Handler. The inspected retry path uses no wake lock or independent alarm for each retry. The daily 23:00 alarm is a separate trigger.

Android Handler delays use uptime, which excludes deep CPU sleep; see [Handler scheduling](https://developer.android.com/reference/android/os/Handler#sendMessageDelayed(android.os.Message,%20long)) and [SystemClock.uptimeMillis](https://developer.android.com/reference/android/os/SystemClock#uptimeMillis()). These retries therefore do not by themselves imply a hardware wakeup every five seconds. A device reporting Doze `IDLE` can still have a runnable CPU, particularly during an ADB probe.

Retries do consume work while the CPU is runnable. Keeping GMS unfrozen and connected may also increase its background activity. Neither cost was measured here, so there is no defensible battery percentage estimate or guarantee of negligible drain.

## App implementation

**Nighttime FCM protection (HyperOS 4)** is experimental and **off by default**. It uses Shizuku's shell identity and preserves deep Doze.

- Check in the existing FCM/MILLET UserService loop and fixed 15-minute WorkManager recovery pass, plus immediate configuration/startup and full protection passes. Use the existing selected polling interval and add no timer, alarm, or wake lock. Android can defer runnable polling and WorkManager during sleep.
- Healthy checks only read membership. When absent, append GMS while retaining other entries, verify the result, then send owner thaw and reconnect broadcasts. Retry failed recovery on later checks without repeatedly rewriting healthy membership.
- At the first enable of a new UserService, signal the framework observer even when the stored entry is already present. Existing FCM reconnect requests thaw owner GMS first while this feature is enabled.
- Record whether GMS existed before the first activation **before writing the setting**. Disabling removes only app-added GMS, preserves preexisting GMS and other packages, and writes an empty value when necessary. If Shizuku is unavailable, cleanup remains pending until it returns. Disable the option and complete cleanup before uninstalling or clearing app data.
- The option's initial/repair broadcasts operate independently of **FCM Connection Protection**, which controls the ongoing socket watchdog and periodic reconnect request. Leaving nighttime protection off performs no game-setting or MiPush operations, except pending cleanup from a previous activation.

The controller serializes its own operations, rereads before writing, and verifies afterward. Settings shell commands offer no compare-and-swap transaction, so an external OEM writer can still race a write. Polling also leaves a repair window after an OEM clear and adds no guarantee while the process cannot run.

The device probe validated the underlying workaround, not this newly compiled app implementation. A clean build and full Package Manager install are required when validating the changed Shizuku Binder interface; reboot/OTA, clone recovery, actual notifications, and overnight battery tests remain separate follow-up validation.
