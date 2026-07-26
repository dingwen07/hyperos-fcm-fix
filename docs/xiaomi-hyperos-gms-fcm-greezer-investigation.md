# Xiaomi China HyperOS: GMS/FCM screen-off disconnection investigation

Date: 2026-07 (exact test day omitted; Asia/Singapore)

> Sanitization note: device codenames, model and firmware identifiers, per-device UIDs, the unrelated test-app identity, local network endpoints, exact timestamps, test-specific cumulative counters, and binary fingerprints have been removed or replaced. Public package names, Android's fixed shell UID, commands, API levels, software versions, and technical behavior are retained because they are necessary to reproduce and evaluate the findings.

## Executive conclusion

On the two failing China HyperOS builds tested here, the long FCM interruption was caused by Xiaomi freezing the `com.google.android.gms` UID in Greezer. It was not necessary to blame the LAN, fake-IP routing, Android Doze, or the PowerKeeper `gms_wall` firewall to explain the observed disconnect.

There are at least two distinct Greezer freeze paths affecting GMS on these builds:

1. A newer, explicit GMS limiter in `GreezeManagerService`. When GMS becomes active while the screen is off, it waits 10 seconds, removes `com.google.android.gms` from Aurogon's ordinary allowlist, and requests an immediate quick freeze.
2. The separate `PowerStrategyMode` path. Foreground-to-background changes schedule freezes with reason `tobg`; system-triggered thaws can be followed by freezes with reason `from system`.

Xiaomi's per-package **No restrictions** policy feeds a different set, `Settings.System.MILLET_NO_RESTRICT_APP`. Both the Aurogon quick-freeze implementation and `PowerStrategyMode` consult this set. Adding `com.google.android.gms` itself to it stopped the tested device from freezing GMS after lock and kept its FCM TCP connection established.

Setting **Google Play Store** (`com.android.vending`) to No restrictions does **not** add `com.google.android.gms` to that set. It does disable a separate PowerKeeper GMS network-control feature, because that feature intentionally uses the Play Store's policy as its user-facing master switch. That explains why Play Store No restrictions can disable Xiaomi's GMS firewall yet still fail to prevent Greezer from freezing Play services.

The tested no-root solution is therefore:

- Use Shizuku/shell to ensure `com.google.android.gms` is present in `MILLET_NO_RESTRICT_APP`, preserving every existing package.
- Re-check and restore it whenever PowerKeeper regenerates the setting from its private policy database.
- Optionally also run `dumpsys greezer IM GMS disable` after each `system_server` start as defense in depth on builds that contain the explicit GMS limiter.

This does not require UI automation, root, replacing PowerKeeper, or globally disabling Greezer.

## Evidence standard

Claims in this report are based on one or both of:

- Live ADB testing on the three connected devices.
- Decompiled code pulled from those devices' `miui-services.jar`, PowerKeeper APK, and Google Play services APK.

Where code implies something that was not isolated in a device test, it is labeled as code-backed rather than independently tested. Decompiled code can contain naming or control-flow artifacts, so the strongest conclusions use simple methods and are corroborated by live behavior.

## Devices compared

| Label | Device/model | HyperOS build | Android | PowerKeeper | Google Play services | Result before intervention |
|---|---|---|---:|---:|---:|---|
| Device A | Omitted | China build A (omitted) | 16 / API 36 | 4.2.00 | 26.26.34 | Primary GMS froze; FCM reconnected on unlock |
| Device B | Omitted | China build B (omitted) | 16 / API 36 | 4.2.00 | 26.26.34 | Primary GMS did not freeze; FCM survived lock |
| Device C | Omitted | China build C (omitted) | 16 / API 36 | 4.2.00 | 26.26.34 | Primary GMS froze until the hidden No restrictions entry was added |

All three reported region `CN`.

The primary GMS UIDs were:

- Device A: `<owner-gms-uid-a>`
- Device B: `<owner-gms-uid-b>`
- Device C: `<owner-gms-uid-c>`

Devices A and C also had user-999/XSpace GMS UIDs (`<xspace-gms-uid-a>` and `<xspace-gms-uid-c>`). Primary-user FCM was the main subject; clone-user counters were kept separate.

## Keep the mechanisms separate

HyperOS contains several overlapping generations of background control. Similar names such as "allowlist", "whitelist", "freeze", and "GMS control" do not mean the same thing.

```text
Xiaomi App details UI
    |
    | bgControl = "noRestrict" for that exact package
    v
PowerKeeper private UserConfigure database
    |
    | ActiveStateController.dealNoRestrictApp()
    v
Settings.System.MILLET_NO_RESTRICT_APP
    |
    +--> Aurogon quick-freeze filter
    |
    +--> PowerStrategyMode / PolicyMaker / AurogonFilterManager

Separately:

PowerKeeper GmsObserver
    +--> gms_wall firewall chain
    +--> per-UID DNS blocker
    +--> wakelock/alarm/backup handling
    ^
    | user-facing switch is Play Store's bgControl

Separately:

AOSP DeviceIdle / Doze / app standby

Separately:

GMS process importance (BFGS), trust-agent binding,
notification-listener binding, default payment role
```

The final fix works through `MILLET_NO_RESTRICT_APP`. The other mechanisms are useful diagnostic context but are not substitutes for that package-specific Greezer exemption.

## Path 1: Xiaomi App details “No restrictions”

### Code path

PowerKeeper defines:

```java
UserConfigure.BG_CONTROL_NO_RESTRICT = "noRestrict";
```

`ActiveStateController` watches PowerKeeper's `UserConfigure.CONTENT_URI`. When a row changes, it checks whether that exact package's `bgControl` equals `noRestrict`, updates an internal UID property, and calls `dealNoRestrictApp()`.

`dealNoRestrictApp()` queries all user-0 rows with `bgControl = noRestrict`, builds a package-name set, and writes the comma-separated result to:

```text
Settings.System.MILLET_NO_RESTRICT_APP
```

Relevant pulled source:

- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/provider/UserConfigure.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/provider/UserConfigureHelper.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/controller/ActiveStateController.java`, especially lines 536-551 and 579-619

### Greezer consumption

`AurogonImmobulusMode` registers a content observer for `MILLET_NO_RESTRICT_APP`, parses it by comma, trims whitespace, and stores package names in `mNoRestrictAppSet`.

It checks this set in several freeze paths. In particular, `lambda$triggerQuickFreeze$0()` returns before freezing when the target package is in `mNoRestrictAppSet`.

`AurogonFilterManager` also defines `MSG_FILTER_NO_RESTRICT_CASE = 64`. `PolicyMaker.isAllowFreeze()` calls:

```java
AurogonFilterManager.getInstance().filter(uid, pkgName, 150112)
```

`150112` includes bit `64`, so membership in `mNoRestrictAppSet` makes `PolicyMaker` return `CANNOT_FREEZE`. This protects the `PowerStrategyMode` paths, including `tobg` and `from system`.

Relevant pulled source:

- `work/greezer/<device-a>/AurogonImmobulusMode.java`, especially lines 277, 1391-1405, 1990-1992, and 2059-2077
- `work/decompiled/miui-services/sources/com/miui/server/greeze/AurogonFilterManager.java`, especially lines 28 and 84-87
- `work/decompiled/miui-services/sources/com/miui/server/greeze/power/PolicyMaker.java`, especially lines 183-200

### Package specificity

The setting is package-specific. Setting Play Store to No restrictions produced:

```text
com.android.vending
```

on Device A. It did not produce `com.google.android.gms`.

Before the fix, Device C contained:

```text
com.android.vending, com.example.testapp
```

After the shell addition it contained:

```text
com.android.vending, com.example.testapp, com.google.android.gms
```

This exact-package distinction is the central reason Play Store No restrictions did not keep FCM alive.

### What the per-app selector actually stores

The SecurityCenter UI presents four choices, but they are not standard Android
AppOps. `PowerDetailActivity` sends a package, user ID, and one string through
the private `IPowerKeeper` Binder interface:

| UI choice | Binder value | PowerKeeper `userTable.bgControl` |
|---|---|---|
| Battery saver / smart (recommended) | `miui_auto` | `miuiAuto` |
| No restrictions | `no_restrict` | `noRestrict` |
| Restrict background apps | `restrict_bg` | `restrictBg` |
| Restrict background activity | `no_bg` | `noBg` |

`restrictBg` also stores a delay, defaulting to 10 minutes. The other three
profiles use `bgDelayMin = -2` in the tested build.

Relevant source:

- `work/decompiled/securitycenter/sources/com/miui/powercenter/legacypowerrank/PowerDetailActivity.java`, especially `h1()` and `onPreferenceClick()`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/provider/PowerSaveConfigureManager.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/provider/UserConfigure.java`

The supplied `Xiaomi_AppOps_and_PowerKeeper_Report.md` was directionally
correct on this point: this selector is a PowerKeeper policy, not the standard
`RUN_IN_BACKGROUND`/`RUN_ANY_IN_BACKGROUND` AppOps and not Xiaomi auto-start
AppOp 10008.

### One database choice fans out to many controllers

The one `bgControl` value is compiled into a `PowerKeeperAppConfigure`
scenario. A row-specific content change rebuilds that package's compiled
object and calls `setAppConfigureUidPolicy()`. That method distributes derived
policies to all of these subsystems:

1. app-activity/data checker
2. background-location rule checker
3. legacy frozen-app rule checker
4. kill-process controller and its rule checker
5. sensor controller and its rule checker
6. active-state controller
7. background-idle rule checker
8. DeviceIdle rule checker
9. app-idle/app-standby rule checker
10. app-cluster controller

The implementation makes 12 setter calls because kill and sensor each have a
controller-level setting as well as a rule-checker setting. The setting is
therefore a profile that affects several policies, not an alias for one AppOp
or one Android battery-optimization flag.

The profile also has two important direct side effects:

- A literal database value of `noRestrict` makes `ActiveStateController`
  record the UID as user-unrestricted and regenerate
  `Settings.System.MILLET_NO_RESTRICT_APP` from all user-0 `noRestrict` rows.
- Scenario 8 gives the DeviceIdle rule checker policy 0, which causes
  `DeviceIdleController` to add the app to Android's user DeviceIdle
  whitelist.

The derived numeric policies are controller-specific. The same number does
not always mean the same final kernel/framework action, so the scenario number
must not be treated as a universal restriction level.

Relevant source:

- `PowerKeeperConfigureManager.setAppConfigureUidPolicy()`, lines 847-863 in the pulled source
- `PowerKeeperAppConfigure.fillScenarioContent()` and its `to*Params()` methods
- `ActiveStateController`, `DeviceIdleController`, `BgIdleController`,
  `FrozenAppController`, `SensorController`, and `AppStandbyController`

### Controlled Test app smart-to-No-restrictions test

Device C provided a clean live before/after test using only the user's UI
action.

With Test app on smart/recommended:

```text
userTable: com.example.testapp | miuiAuto | -2
compiled scenario: 2
MILLET_NO_RESTRICT_APP membership: absent
DeviceIdle user whitelist membership: absent
RUN_ANY_IN_BACKGROUND: allow
```

After the user selected No restrictions:

```text
userTable: com.example.testapp | noRestrict | -2
compiled scenario: 8
MILLET_NO_RESTRICT_APP membership: present
DeviceIdle: user,com.example.testapp,<test-app-uid>
RUN_ANY_IN_BACKGROUND: allow
```

Thus this one UI operation demonstrably changed the private PowerKeeper row,
the compiled controller profile, Xiaomi's MILLET/Greezer package set, and the
Android DeviceIdle user whitelist. It did not change the standard background
AppOp, which was `allow` both before and after.

### Why the selector is hidden for GMS

On this build, the hiding path is a general no-launcher-app filter rather than
a GMS-only conditional in the power-detail screen:

1. `PowerKeeperConfigureManager.initUserConfigure()` tests every controlled
   package with `pkgHasIcon()`.
2. `Utils.pkgHasIcon()` is exactly
   `PackageManager.getLaunchIntentForPackage(pkg) != null`.
3. Packages without a launch intent are written to the colon-separated
   `user_de_configured_apps` setting.
4. SecurityCenter's `PowerDetailActivity` checks that list before asking
   PowerKeeper for the current per-app value. If the package is present, the
   query task returns `null` and the whole single-choice policy category is
   hidden.

The device-side check matched the code:

```text
com.google.android.gms: No activity found
com.example.testapp: com.example.testapp/.MainActivity
```

GMS nevertheless has a normal `userTable` row. On Device C its current value
was directly visible in PowerKeeper's own dump:

```text
com.google.android.gms | miuiAuto | -2
```

`PowerSaveConfigureManager.getPowerSaveAppConfigure()` contains a migration
convenience: on the first UI query, a `miuiAuto` app already ignored by AOSP
battery optimization is automatically changed to `noRestrict`. That does not
help GMS here because the SecurityCenter task returns early for a package in
`user_de_configured_apps` and never performs the query. The live GMS row
remaining `miuiAuto` despite its AOSP system/excidle exemption confirms that
this auto-promotion had not happened.

### What `miuiAuto` means specifically for GMS

PowerKeeper's scenario compiler has an explicit GMS-core exception. For a
normal third-party app, `miuiAuto` on the tested balanced configuration became
scenario 2. For `GmsCoreUtils.isGmsCoreApp(packageName)`, the same `miuiAuto`
row becomes scenario 0.

The live values on Device C were:

```text
Test app smart: userTable=miuiAuto, scenario=2
GMS smart:      userTable=miuiAuto, scenario=0
Test app unrestricted: userTable=noRestrict, scenario=8
```

`PowerKeeperAppConfigure.isNoRestrict()` returns true for both scenario 0 and
scenario 8. This is why older PowerKeeper code can describe GMS as effectively
unrestricted even though its database row is still `miuiAuto`.

That legacy exception must not be confused with the newer Greezer exemption:
`ActiveStateController.dealNoRestrictApp()` does not use the compiled scenario
or `isNoRestrict()`. It queries only rows whose literal `bgControl` equals
`noRestrict`. Consequently GMS scenario 0 does not put
`com.google.android.gms` into `MILLET_NO_RESTRICT_APP`. That missing package
membership is what allowed the newer Greezer paths to freeze it.

### Can shell fully set GMS to No restrictions?

No: shell cannot set the authoritative PowerKeeper database row on this ROM.
This was verified in two independent ways on Device C:

```text
content query content://com.miui.powerkeeper.configure/userTable ...
SecurityException: requires miui.permission.powerkeeper.HIDDEN_MODE_PROVIDER
```

and:

```text
am startservice -n com.miui.powerkeeper/.PowerKeeperBackgroundService
Error: Requires permission com.miui.powerkeeper.permission.BIND_SERVICE
```

Both permissions are declared `signatureOrSystem` (`signature|privileged` in
the installed-package dump). A Shizuku UserService runs as shell UID 2000 and
does not acquire them. PowerKeeper's configuration Binder is component-bound,
not published as a globally callable service in `service list`.

Shell can reproduce the downstream parts that matter for GMS/FCM:

- write and monitor `Settings.System.MILLET_NO_RESTRICT_APP`;
- manage the Android DeviceIdle whitelist (although GMS was already present as
  both `system` and `system-excidle` on Device C);
- manage ordinary AppOps/app-standby state if required, though neither was the
  missing GMS exemption in this case.

After shell restored the GMS MILLET entry, PowerKeeper still reported:

```text
userTable=miuiAuto
compiled scenario=0
```

That proves the shell operation is a downstream Greezer exemption, not a full
emulation of the UI's `noRestrict` profile. For the tested FCM failure it is
nevertheless the decisive part and was sufficient to stop screen-off GMS
freezes. It remains non-persistent because any later PowerKeeper row change can
regenerate the shared setting from the private table and remove the shell-only
entry.

### Cross-user scope: owner, XSpace, and managed work profiles

`MILLET_NO_RESTRICT_APP` has asymmetric storage and enforcement:

- `Settings.System` is a per-user settings namespace.
- PowerKeeper deliberately generates this key from only `userTable` rows with
  `userId = 0` and `bgControl = noRestrict`.
- Greezer reads the key through its system-server context into one
  `Set<String>`. The set contains package names only—there is no user ID or UID
  in an entry.
- Greezer resolves each candidate UID to an `AurogonAppInfo` and tests
  `mNoRestrictAppSet.contains(app.mPackageName)`. Therefore the owner-loaded
  package entry applies to that package in every Android user that Greezer
  processes.

The observer is registered for `UserHandle.USER_ALL` (`-1`), but its callback
uses ordinary `Settings.System.getString()` rather than
`getStringForUser(changedUserId)`. In system_server this reloads the owner-user
value. A write made only to user 999 or a managed-profile user may wake the
observer, but it does not become a separate per-profile Greezer allowlist.

Device C confirmed the storage split:

```text
user 0 MILLET targets:   com.example.testapp, com.google.android.gms
user 999 MILLET targets: none

owner GMS UID:  <owner-gms-uid-c>
XSpace GMS UID: <xspace-gms-uid-c>
```

Device A has both a managed work profile (numeric user ID omitted) and XSpace (user 999). It
showed the same split:

```text
user 0 MILLET targets:   com.android.vending, com.google.android.gms
work-profile MILLET targets: none
user 999 MILLET targets: none

owner GMS UID:        <owner-gms-uid-a>
work-profile GMS UID: <work-profile-gms-uid-a>
XSpace GMS UID:       <xspace-gms-uid-a>
```

The newer explicit GMS limiter reinforces the cross-user conclusion. It
enumerates every `UserInfo`, constructs each user's GMS UID with
`UserHandle.getUid(userId, appId)`, and sends every running UID through
`triggerQuickFreeze()`. That method obtains the package name and returns before
freezing if the package is in the one `mNoRestrictAppSet`. Thus an owner entry
for `com.google.android.gms` protects the owner, XSpace, and a running work
profile from this quick-freeze path.

This also means the MILLET mechanism cannot express “unrestricted only in the
work profile” or “only in XSpace.” Its granularity is package name across
users.

The broader PowerKeeper profile is more nuanced:

- Ordinary managed profiles have their own `userTable` rows and their compiled
  controller policies are built with that profile's UID.
- The MILLET projection still ignores those profile rows because
  `getNoRestrictApps()` hard-codes `userId = 0`.
- XSpace is special. PowerKeeper clones the main-space compiled
  `PowerKeeperAppConfigure` into the corresponding XSpace UID at initialization
  and after owner-row changes. SecurityCenter also maps an XSpace policy edit
  back to the main-space user before calling PowerKeeper.
- DeviceIdle ultimately works by app ID rather than full UID. PowerKeeper keeps
  per-user intermediate state but ORs it across users before modifying the
  underlying DeviceIdle whitelist, so that side effect can also become
  cross-user for packages sharing the same app ID.

For a Shizuku repair, the correct target on this ROM is therefore explicitly:

```sh
settings --user 0 put system MILLET_NO_RESTRICT_APP ...
```

Writing separate copies for user 999 or a work-profile user is unnecessary for
Greezer and is not a substitute for the owner value. The repair must still
preserve all existing owner entries. A policy change from any user can notify
PowerKeeper, which regenerates the owner projection and can remove shell-only
entries.

Finally, this is a normal-mode/package-filter exemption, not a universal
promise that no Xiaomi power mode can ever act on the UID. Several Immobulus
bulk paths intentionally ignore `mNoRestrictAppSet` while Extreme mode is
active. The tested GMS quick-freeze path and `PowerStrategyMode` filter do honor
the package entry.

### Confirmed overwrite behavior

PowerKeeper treats `MILLET_NO_RESTRICT_APP` as a generated projection of its private `UserConfigure` table, not as an independently owned user list.

This was confirmed live after the initial fix:

1. `com.google.android.gms` was appended through shell.
2. The user toggled Test app's Xiaomi background setting.
3. PowerKeeper immediately regenerated the setting as:

   ```text
   com.android.vending, com.example.testapp
   ```

4. The shell-added GMS entry had disappeared.
5. The GMS entry was then restored.

Therefore a durable Shizuku implementation must re-check the setting after any PowerKeeper UI-policy change. Boot-time-only restoration is insufficient.

## Path 2: the newer explicit GMS limiter in Greezer

### Failing builds

Device A and Device C have byte-identical decompiled `GreezeManagerService.java` files:

```text
<sha256-omitted>
```

Their decompiled `AurogonImmobulusMode.java` files are also identical:

```text
<sha256-omitted>
```

Their `GreezeManagerService` contains an explicit `mGmsLimitEnabled`, initialized to `true`.

When a UID becomes active, the UID observer does the following:

```java
if (isGmsApp(uid) && !mScreenOn && !mHandler.hasMessages(10)) {
    mHandler.sendEmptyMessageDelayed(10, 10000L);
}
```

Message 10 calls `triggerGMSLimitAction()`. On a China model, while `mGmsLimitEnabled` is true, that method:

1. Removes `com.google.android.gms` from Aurogon's ordinary `mAllowList`.
2. Builds a GMS UID for every Android user.
3. Calls `triggerQuickFreeze(gmsUid, 0)` for each running GMS UID.

Relevant pulled source:

- `work/greezer/<device-a>/GreezeManagerService.java`, lines 416-424, 631, and 4258-4283
- Device C has identical code at the corresponding locations.

### Runtime dump command

`AurogonImmobulusMode.dump()` contains hidden commands:

```text
dumpsys greezer IM GMS disable
dumpsys greezer IM GMS enable
dumpsys greezer IM GMS limit
```

The first two set `mGmsLimitEnabled` false or true. `GreezeManagerService.dump()` uses Android's normal `DumpUtils.checkDumpPermission`, so shell can use these dump arguments.

The tested command was:

```sh
dumpsys greezer IM GMS disable
```

It succeeded on Devices 1 and 3, and `dumpsys greezer` then reported:

```text
IM mGmsLimitEnabled : false
```

This flag is initialized to `true` in the service constructor and the only other assignments found are the dump commands. It is therefore runtime state and resets when `system_server` restarts or the phone reboots.

### Why the cloud allowlist did not save the failing builds

All three devices' secure setting `immobulus_mode_switch_restrict` began with `enable_24_allowlist` and contained `com.google.android.gms`.

`AurogonImmobulusMode.updateCloudAllowList()` parses that setting into `mCloudAllowList`, merges it into `mAllowList`, and resolves package UIDs into `mAllowUidList`.

On the failing builds, `triggerGMSLimitAction()` explicitly removes GMS from this in-memory Aurogon allowlist before requesting the quick freeze. The underlying secure cloud string can still visibly contain GMS while the effective in-memory allowlist no longer does.

This cloud allowlist is distinct from `mNoRestrictAppSet`. The new GMS limiter removes GMS from the former, but `triggerQuickFreeze()` still checks the latter first. That is why adding GMS to `MILLET_NO_RESTRICT_APP` is stronger than merely restoring the ordinary Aurogon allowlist.

## Path 3: PowerStrategyMode (`tobg` and `from system`)

This is a distinct Greezer path, not PowerKeeper's firewall and not the explicit GMS limiter.

`GreezeManagerService.onForegroundActivitiesChanged(..., false)` schedules:

```java
dealRetryUid(uid, "tobg", PolicyMaker.BINDER_DELAYED_TIME)
```

after a short post. `BINDER_DELAYED_TIME` is 5000 ms. `ActionExecute.delayFreeze()` later calls `PolicyMaker.isAllowFreeze(uid)` and, when allowed, freezes the UID through `GREEZER_MODULE_POWER`.

`ActionExecute.dealThawOther()` schedules the same machinery with reason `from system` when module 1000 wakes an app.

Relevant source:

- `work/greezer/<device-a>/GreezeManagerService.java`, around lines 510-538
- `work/decompiled/miui-services/sources/com/miui/server/greeze/power/ActionExecute.java`, especially lines 181-190, 234-267, and 340-435
- `work/decompiled/miui-services/sources/com/miui/server/greeze/power/PolicyMaker.java`

### Device C proof

Disabling only the explicit GMS limiter was not sufficient on Device C. Even with:

```text
IM mGmsLimitEnabled : false
```

and after adding GMS to Aurogon's ordinary `mAllowList` with `LM add`, the next manual unlock/lock produced:

```text
<timestamp> - THAW uid = <owner-gms-uid-c> ... reason : screen on caller : 1
<timestamp> - FZ uid = <owner-gms-uid-c> ... reason : tobg caller : 1
```

GMS froze nine seconds after the screen-on thaw. Later cycles also showed:

```text
... FZ uid = <owner-gms-uid-c> ... reason : tobg caller : 1
... THAW uid = <owner-gms-uid-c> ... reason : Excute Service caller : 1000
... FZ uid = <owner-gms-uid-c> ... reason : from system caller : 1
```

This proves that:

- `IM GMS disable` controls one GMS-specific path, not every Greezer path.
- `LM add com.google.android.gms` modifies Aurogon's ordinary `mAllowList`, but it does not protect against `PowerStrategyMode`'s own policy path.
- The No restrictions set is the shared filter that covers both.

## Path 4: PowerKeeper's separate GMS network controller

PowerKeeper has a separate `GmsObserver`. This is real code, but it should not be confused with Greezer process freezing.

### What it controls

On China builds its default feature state is enabled:

```java
defaultState = !Build.IS_INTERNATIONAL_BUILD;
```

It initializes an MCD-controlled chain for the GMS UID:

```java
initGmsChain("gms_wall", gmsUid, "REJECT")
```

When PowerKeeper decides Google is unreachable, `onGoogleReachabilityChanged(false)` requests a blocked state. `updateGmsState(true)` can then:

- Enable `gms_wall` through MCD.
- Set the GMS UID's DNS rule to `deny` through `dnsproxyd`.
- Block configured GMS-related wakelocks.
- Restrict alarms and notify registered callbacks.
- Adjust Google backup behavior.

When reachability returns, it reverses those controls.

Relevant source:

- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/utils/GmsObserver.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/utils/NetdExecutor.java`
- `work/powerkeeper/<device-a>/jadx/sources/com/miui/powerkeeper/utils/OctVmNativeProxy.java`

### Why Play Store No restrictions affects this path

`GmsObserver.isGmsControlEnabled()` does not read the GMS package's policy. It reads `com.android.vending`:

```java
UserConfigureHelper.getUserConfigureHelperByPkg(context, "com.android.vending")
```

and returns false when Play Store's `bgControl` is `noRestrict`.

The content observer then unblocks GMS if it was blocked, and future requests to enter the blocked state are ignored while user control is disabled.

Therefore:

- **Play Store No restrictions is the user-visible off switch for this dedicated GMS network-control path.**
- **It is not a Greezer exemption for `com.google.android.gms`.**

The user tested Play Store No restrictions on Device A. FCM diagnostics still showed a fresh 00:00/00:01 connection after unlock. The live package list contained only `com.android.vending`, while Greezer was still able to freeze the GMS UID. This behavior is consistent with both code paths and disproves the earlier idea that Play Store No restrictions alone disables all Xiaomi GMS limiting.

### Relationship to `ERROR_IO_FIN`

The dedicated firewall could disrupt GMS networking when enabled, but the observed `ERROR_IO_FIN` does not establish that it caused this case.

On Device C, the Greezer history recorded the `tobg` freeze before the FCM socket disappeared. A frozen process can leave its existing TCP socket visible for a short time; the connection can then be closed while the client remains unable to run. Thus the timeline is fully explained by process freezing, without requiring a firewall event.

The correct conclusion is limited:

- Xiaomi has a separate GMS firewall/DNS controller in code.
- Play Store No restrictions disables its user-controlled blocking behavior.
- The tested long screen-off FCM failure persisted because Greezer still froze GMS.
- No claim is made that `gms_wall` can never affect other failures.

### MCD and SELinux findings

`NetdExecutor` sends MCD Binder transaction 8 to service `miui.whetstone.mcd` with arguments such as:

```text
sudebug set_chain_state gms_wall disable
```

The device contains `/system_ext/bin/mcd`, owned by `root:shell` and labeled `u:object_r:mcd_exec:s0`.

Pulled SELinux policy contains:

- `allow shell mcd_exec ... execute`
- `allow shell mcd process transition`
- `typetransition shell mcd_exec process mcd`
- `allow mcd self capability net_admin`

This makes an MCD-based chain-state fallback code- and policy-supported without root. It was not needed for the successful Greezer fix, and it is not equivalent to disabling the separate per-UID `dnsproxyd` rule. PowerKeeper could also re-enable the chain after a later reachability event unless its user control is disabled. It should therefore not be the primary solution.

## Path 5: AOSP Doze and app standby

AOSP DeviceIdle state is separate from Xiaomi Greezer.

On Device C, after the successful fix, `dumpsys deviceidle whitelist` already showed GMS as both `system` and `system-excidle`. It had also been present in Android's whitelist before the Xiaomi freeze was solved. Nevertheless, Greezer froze UID <owner-gms-uid-c>.

This proves that Android's Doze whitelist does not prevent Xiaomi's cgroup freezer from freezing an app. Shell can manipulate ordinary Doze and app-standby state, but doing so is not a substitute for the Xiaomi No restrictions set.

Likewise, testing `am unfreeze --sticky <gms-pid>` on already-frozen Device C returned success from ActivityManager, but the Xiaomi Greezer freeze remained effective and no FCM socket returned. AOSP's sticky-unfreeze facility does not override Xiaomi's independent Greezer ownership reliably.

## Ordinary Aurogon allowlists and local PowerKeeper config

### `mAllowList` versus `mNoRestrictAppSet`

These are different collections:

- `mAllowList` is assembled from local and cloud Aurogon allowlists. `LM add` changes this runtime collection.
- `mNoRestrictAppSet` comes from `MILLET_NO_RESTRICT_APP` and is checked by both Aurogon quick freeze and `PowerStrategyMode`'s filter.

`dumpsys greezer LM add com.google.android.gms` successfully modified the former but did not stop Device C's `tobg` freeze. It must not be presented as the complete fix.

### PowerKeeper `local.config`

All three decoded PowerKeeper configs contained the same GMS entry:

```json
{
  "app_name": "com.google.android.gms",
  "added": true,
  "group_id": 5,
  "action_list": [
    {"action_key": "set_data_connection", "action_value": true},
    {"action_key": "set_location", "action_value": false},
    {"action_key": "location_delay_hot", "action_value": "-2"},
    {"action_key": "kill_delay_hot", "action_value": "-2"}
  ]
}
```

All three also had a `launch_restrict` default string containing GMS. These common entries do not explain why only two devices froze primary GMS. They are useful negative evidence against treating every legacy PowerKeeper configuration entry as causal.

## Why Device B worked without a GMS No restrictions entry

Device B's current `MILLET_NO_RESTRICT_APP` list did not contain GMS, yet its primary GMS freeze count was zero:

```text
pkg: com.google.android.gms uid: <owner-gms-uid-b> frozenTime: 0 count: 0
```

Its `GreezeManagerService` differs materially from the failing pair:

- It has no `mGmsLimitEnabled` field.
- Its UID-active observer compares only against `mGmsMultiUid`; it does not call `isGmsApp(uid)` for every primary GMS UID.
- Its `triggerGMSLimitAction(boolean)` operates only on `mGmsMultiUid`, changing wake-lock and network rules. It does not remove primary GMS from Aurogon's allowlist and does not call `triggerQuickFreeze()` for every user's GMS UID.
- Its cloud allowlist still contains GMS, and this older handler does not remove primary GMS from that list.

The working decompiled `GreezeManagerService.java` hash was:

```text
<sha256-omitted>
```

The working `AurogonImmobulusMode.java` hash was:

```text
<sha256-omitted>
```

This comparison is strong evidence that the decisive difference is in Xiaomi's framework Greezer implementation, not the GMS APK version, PowerKeeper version string, or LAN.

## PowerKeeper build comparison

The full APK hashes differed, but the failing devices' primary PowerKeeper DEX files were identical:

```text
Device A classes.dex: <sha256-omitted>
Device C classes.dex: <sha256-omitted>
Device B classes.dex: <sha256-omitted>
```

The decoded `local.config` hashes were also identical for the failing pair and different for Device B:

```text
Device A: <sha256-omitted>
Device C: <sha256-omitted>
Device B: <sha256-omitted>
```

However, the decompiled `GmsObserver.java` difference between working and failing PowerKeeper was only an obfuscated delimiter-symbol reference. The dedicated firewall logic was semantically the same. The clearest working/failing discriminator remained `miui-services.jar`'s Greezer code.

## Process importance, BFGS, trust agents, notification access, and payment role

These were investigated because they can make GMS appear important to Android ActivityManager. They did not provide a reliable Xiaomi Greezer exemption.

### BFGS is not the No restrictions list

`BFGS` is Android process importance associated with a bound foreground-service state. It can result when privileged framework components bind services inside GMS. It does not automatically add `com.google.android.gms` to `MILLET_NO_RESTRICT_APP`.

The tested evidence was:

- Device C was configured with Google Play services notification access and Extend Unlock enabled, but GMS still froze after lock.
- Making Google Pay the default payment app on Device A did not create a durable Greezer exemption.
- Device C's GMS UID appeared in Greezer's `mCore` set and still froze repeatedly.
- Device B's primary GMS was not relying on the same `mCore` observation and had freeze count zero.

Therefore BFGS, a bound system service, or `mCore` membership may affect some policy branches but is neither necessary nor sufficient for the observed exemption.

### `LockingTrustAgentService`

The pulled GMS 26.26.34 code shows:

- `LockingTrustAgentService` is a thin Chimera proxy.
- `LockingTrustAgentChimeraService` extends Android's `TrustAgentService` and registers itself on creation.
- `LockingIntentOperation` handles internal action `com.google.android.gms.personalsafety.ACTION_LOCK_DEVICE`; when the trust agent and feature gate are available, it calls `lockUser()` and can show a keyguard message.

This is Personal Safety/theft-lock functionality. Android's trust framework binds a configured trust agent; binding can raise GMS process importance. It is not code that grants a PowerKeeper or Greezer whitelist.

Relevant source:

- `work/gms_inspect/LockingTrustAgentService.java`
- `work/gms_inspect/LockingTrustAgentChimeraService.java`
- `work/gms_inspect/LockingIntentOperation.java`

### `PhoneHubNotificationListenerService`

The pulled code shows:

- `PhoneHubNotificationListenerService` is another Chimera proxy.
- The implementation extends Android's `NotificationListenerService`.
- `onListenerConnected()` reads active notifications.
- Notification callbacks select supported messaging/call notifications and forward changes to Phone Hub/proximity callbacks.

Granting notification access causes Android's notification-manager framework to bind the enabled listener, which can also raise GMS process importance. It is for cross-device Phone Hub notification mirroring, not an FCM keepalive or Greezer whitelist.

Relevant source:

- `work/gms_inspect/PhoneHubNotificationListenerService.java`
- `work/gms_inspect/PhoneHubNotificationListenerChimeraService.java`

## Network observations

The tested Xiaomi device used no VPN. The LAN could reach Google directly. A Pixel on the same LAN maintained FCM for a long time, so the LAN and fake-IP setup were valid controls.

The Xiaomi FCM connection used the fake-IP destination `<fake-ip>:5228`. The same destination was observed both before and after the Greezer fix. This argues against fake-IP routing being the differentiator.

## Controlled test results

### Device A: explicit GMS limiter disabled

Command:

```sh
dumpsys greezer IM GMS disable
```

Result:

- `mGmsLimitEnabled` became false.
- While the device was already dozing, the exact FCM socket remained:

  ```text
  <lan-ip>:<ephemeral-port> -> <fake-ip>:5228
  ```

  for approximately five minutes.
- The primary GMS freeze count did not change during that sample.

This showed that the explicit limiter was important on Device A. It did not prove the command covers every freeze path, which Device C later disproved.

### Device C: limiter disabled plus ordinary Aurogon allowlist

Applied:

```sh
dumpsys greezer IM GMS disable
dumpsys greezer LM add com.google.android.gms
```

After a manual unlock/lock, primary GMS froze with `reason : tobg`. The FCM socket appeared briefly after wake and then disappeared while GMS was frozen.

Conclusion: this combination does not cover `PowerStrategyMode`.

### Device C: hidden No restrictions entry

The existing `MILLET_NO_RESTRICT_APP` value was preserved and `com.google.android.gms` was appended.

After the user manually unlocked and locked the phone:

- Last thaw of primary GMS was at `<timestamp>`, reason `screen on`.
- No later primary-GMS freeze was recorded during the verification interval.
- The cumulative primary freeze count did not increase.
- At `<timestamp>`, the FCM socket was still the same established connection:

  ```text
  <lan-ip>:<ephemeral-port> -> <fake-ip>:5228  ESTAB
  ```

- The user independently confirmed that the problem was fixed.

The device still had `mGmsLimitEnabled = false` during this test, so the device test is a combined-state test. The code independently shows that `mNoRestrictAppSet` is checked before explicit quick freeze and by `PowerStrategyMode`; therefore the setting covers the paths identified here even when the runtime limiter later resets.

### Device C: overwrite test

After the fix, toggling Test app's Xiaomi background policy removed the shell-added GMS entry immediately. While the entry was absent, the phone was locked and Greezer recorded:

```text
<timestamp> - FZ uid = <owner-gms-uid-c> ... reason : screen off caller : 1
```

The GMS entry was restored afterward, but changing an allowlist does not thaw a UID that is already frozen. GMS remained frozen until a legitimate Bluetooth event produced:

```text
<timestamp> - THAW uid = <owner-gms-uid-c> ... reason : bluetooth caller : 1000
```

No new freeze followed during the final sample, and by `<timestamp>` FCM had reconnected on:

```text
<lan-ip>:<ephemeral-port> -> <fake-ip>:5228  ESTAB
```

The cumulative primary-GMS freeze count increased by one because of the freeze during the missing-entry window.

This is a useful removal/recovery experiment:

- With the GMS entry present, the initial post-lock verification had no new freeze and retained the socket.
- PowerKeeper's UI rewrite removed the entry.
- GMS then froze on screen-off.
- Restoring the entry prevented another freeze after the existing one was legitimately thawed, and FCM reconnected.

It confirms the need for continuous, prompt, idempotent repair rather than a one-time write. A repair operation should run immediately when the setting changes; merely restoring the entry later cannot undo a freeze that has already happened.

## Shell and Shizuku boundaries

### What shell can do

Tested successfully:

- Read and write `Settings.System.MILLET_NO_RESTRICT_APP` with `settings`.
- Invoke `dumpsys greezer IM GMS disable`.
- Invoke `dumpsys greezer LM add ...` (although it is not the complete fix).
- Read Greezer counters/history through `dumpsys`.
- Manage ordinary AOSP Doze/app-standby state.

### What shell could not do directly

`cmd greezer help` returned a `SecurityException` saying UID 2000 did not have permission to `greezer`. The formal Binder shell-command path is more restricted than the service's dump path.

PowerKeeper's manifest protects its exported configuration provider with:

```text
miui.permission.powerkeeper.HIDDEN_MODE_PROVIDER
protectionLevel="signatureOrSystem"
```

Its exported control services use `com.miui.powerkeeper.permission.BIND_SERVICE`, also `signatureOrSystem`. A normal Shizuku shell client cannot simply write a hidden `UserConfigure` row for GMS through these interfaces.

This is why the practical no-root route writes the downstream system setting and repairs it when PowerKeeper overwrites it.

## Recommended Shizuku implementation

### Required behavior

The Shizuku UserService should implement an idempotent `ensureGmsNoRestrict()` operation:

1. Read `settings get system MILLET_NO_RESTRICT_APP` as shell.
2. Parse the comma-separated entries and trim whitespace exactly as Greezer does.
3. If `com.google.android.gms` is absent, append it without removing or reordering user-selected packages unnecessarily.
4. Write the full preserved list back with `settings put system MILLET_NO_RESTRICT_APP ...`.
5. Re-read and verify.

Run that operation:

- When the app obtains/reobtains Shizuku access.
- After boot once Shizuku is available.
- Whenever the `MILLET_NO_RESTRICT_APP` settings URI changes.
- After the user changes any Xiaomi app background policy.

The observer must avoid a write loop: compare normalized membership first and write only when GMS is absent.

The repair should be prompt. If GMS freezes during the interval in which the entry is absent, restoring the list prevents later policy decisions but does not thaw the existing freeze. The tested shell/AOSP unfreeze route was ineffective against Greezer, so the app should report that condition and let the user wake/unlock the device rather than automating UI interaction.

### Optional defense in depth

On builds where `dumpsys greezer` exposes `mGmsLimitEnabled`, also run:

```sh
dumpsys greezer IM GMS disable
```

after `system_server` starts. This is a volatile runtime toggle. It is useful defense in depth and was directly tested, but it must not replace the `MILLET_NO_RESTRICT_APP` repair.

### Do not use as the sole fix

- Play Store No restrictions: disables the separate GMS network controller, not Greezer's package exemption.
- `dumpsys greezer LM add com.google.android.gms`: does not cover `PowerStrategyMode`.
- AOSP DeviceIdle whitelist or app-standby commands: do not override Xiaomi's freezer.
- `am unfreeze --sticky`: did not override an existing Greezer freeze.
- BFGS/notification access/Extend Unlock/default payment role: did not reliably exempt GMS.
- Globally disabling Immobulus/Greezer: unnecessarily broad and not required by the tested targeted fix.

## Current tested state at report time

Device C was left with:

```text
MILLET_NO_RESTRICT_APP = com.android.vending, com.example.testapp, com.google.android.gms
IM mGmsLimitEnabled = false
```

Its final observed primary-GMS cumulative freeze count included the deliberately exposed `screen off` freeze during the overwrite test; the exact value is omitted. After the subsequent Bluetooth thaw, FCM re-established its port-5228 connection and no additional freeze was observed in the final sample.

Device A was left with `IM mGmsLimitEnabled = false`; its Xiaomi No restrictions list contained only `com.android.vending`.

Device B was not modified for this fix.

These runtime flags and settings can change after reboot, `system_server` restart, PowerKeeper updates/cloud refreshes, or user policy changes. The report records observed state, not a permanent ROM modification.

## Final causal statement

For the tested failing devices, the direct cause of long screen-off FCM loss was Xiaomi Greezer freezing `com.google.android.gms`. The decisive package-level exemption is Xiaomi's `MILLET_NO_RESTRICT_APP`, not Play Store's policy, Android Doze, BFGS, a trust agent, a notification listener, or the ordinary Aurogon cloud allowlist.

PowerKeeper's `gms_wall`/DNS controller is a separate real mechanism. Play Store No restrictions disables that controller, but it does not stop the newer Greezer paths. Keeping `com.google.android.gms` in the hidden No restrictions set—and repairing the entry whenever PowerKeeper regenerates the set—is the narrow, tested, no-root solution.
