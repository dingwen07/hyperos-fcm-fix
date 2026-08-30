# Xiaomi `MILLET_NO_RESTRICT_APP` rewrite investigation

Date: 2026-08-31 (Asia/Singapore)

This report focuses on who owns Xiaomi's
`Settings.System.MILLET_NO_RESTRICT_APP`, when it is regenerated, and why the
app uses a prompt Shizuku watchdog for Google Play services. It supplements
the broader [GMS/FCM and Greezer investigation](xiaomi-hyperos-gms-fcm-greezer-investigation.md).

The primary firmware examined here used PowerKeeper 4.2.00 (`versionCode
40200`). Class and method names come from decompiled vendor code and may change
in other HyperOS releases. Runtime observations were taken from sanitized app
diagnostics and ADB tests; raw device identifiers and unrelated package names
are intentionally not published.

## Executive conclusion

`MILLET_NO_RESTRICT_APP` is not an independently owned whitelist. PowerKeeper
treats it as a generated projection of owner-user rows in its private
`UserConfigure` database whose literal `bgControl` value is `noRestrict`.

This distinction matters for Google Play services:

- Greezer consults the setting before several freeze decisions.
- PowerKeeper does not normally create a literal `noRestrict` row for
  `com.google.android.gms`, even when older compiled policy logic otherwise
  treats GMS as unrestricted.
- A Shizuku/shell addition of GMS changes only the downstream projection, not
  PowerKeeper's private source row.
- Whenever PowerKeeper rebuilds the projection, that shell-only GMS entry is
  omitted again.

The observed rewrites were bursty and activity-dependent, not periodic. The
watchdog interval is therefore chosen for the maximum safe repair latency after
a rare rewrite, not from the average time between rewrites.

## Ownership and data flow

```text
Xiaomi per-app battery UI and PowerKeeper policy logic
    |
    | writes private UserConfigure rows
    v
PowerKeeper userTable (owner rows with bgControl = noRestrict)
    |
    | ActiveStateController.dealNoRestrictApp()
    | queries the complete matching set and replaces the setting
    v
Settings.System.MILLET_NO_RESTRICT_APP for user 0
    |
    | parsed as package names
    v
Greezer mNoRestrictAppSet
    +--> Aurogon quick-freeze filter
    +--> PowerStrategyMode / PolicyMaker filter
```

`ActiveStateController` observes item changes under PowerKeeper's private
`UserConfigure` provider. Its regeneration path queries all owner-user rows
whose literal policy is `noRestrict`, joins their package names, and replaces
the complete Settings value. It does not merge unknown entries already present
in the Settings value.

The setting is stored for Android user 0, but Greezer's parsed set contains
package names rather than user IDs. On the tested build, an owner entry can
therefore protect the same package when Greezer evaluates its UID in another
Android user. Separate writes for XSpace or a work profile are not a substitute
for maintaining the owner value.

## Confirmed and code-backed rewrite triggers

There is no single Xiaomi timer that rewrites the list. Several policy paths
can mutate a `UserConfigure` row, and the row observer then rebuilds the shared
projection.

| Trigger | Evidence | Effect |
|---|---|---|
| User selects a per-app battery policy | Confirmed live | The row changes and the complete list is regenerated immediately. |
| PowerKeeper boot initialization and package inventory reconciliation | Code-backed and consistent with reboot observations | Rows may be created, normalized, or removed, followed by projection regeneration. |
| Package add/remove and user-ready reconciliation | Code-backed | The affected private policy state is reconciled and can cause a full rewrite. |
| First policy query for an unvisited app | Code-backed | A `miuiAuto` row can be promoted to `noRestrict` when Android already reports that the package ignores battery optimization; that row change rewrites the list. |
| No-launcher-app initialization | Code-backed | PowerKeeper records such packages separately and can normalize their rows back to `miuiAuto`; they are also hidden from the normal policy selector. |

The first-query path explains an otherwise surprising observation: opening or
using an app can appear to rewrite the list. The foreground callback itself was
not the final Settings writer in this PowerKeeper build. App activity can cause
an upstream policy query, row materialization, or reconciliation; the private
row change then causes `dealNoRestrictApp()` to replace the list.

Similarly, a reboot does not merely restore a serialized copy of the prior
Settings string. PowerKeeper reconstructs policy state from its own database
and current package inventory, so the resulting projection can omit an app
until later activity causes its row to be revisited.

## Direct overwrite experiment

The downstream ownership model was confirmed live:

1. Shell appended `com.google.android.gms` while preserving the existing list.
2. The user changed another app's Xiaomi background policy.
3. PowerKeeper immediately regenerated the complete setting from its private
   rows.
4. The other app reflected its new policy, but the shell-only GMS entry was
   gone.
5. Restoring GMS prevented later freeze decisions, but did not thaw GMS if it
   had already been frozen during the missing-entry window.

This is why a boot-only or WorkManager-only write is insufficient. Repair must
normally happen before Greezer acts, not merely after the next unlock or
periodic maintenance run.

## Runtime cadence from retained diagnostics

The retained diagnostic datasets did not show a stable Xiaomi rewrite period.
Some removal/repair events occurred in bursts only 16 to 108 seconds apart,
while other spans remained quiet for many hours. The evidence supports a set
of event-driven writers, not a repeating system timer.

That cadence also means a short quiet capture cannot prove that the entry is
durable. Conversely, rare rewrites do not justify a slow repair if the first
freeze decision after a rewrite can occur quickly.

## Why the watchdog uses 2.5 seconds

The shortest relevant delayed-freeze path found in the examined Greezer code
was five seconds. A ten-second poll has a worst-case detection delay of nearly
ten seconds and can lose that race. Once frozen, adding GMS back to the list
does not itself thaw the existing UID.

The app consequently uses a 2.5-second `scheduleWithFixedDelay` watchdog. The
delay begins after the previous check finishes, so this is not a hard real-time
2.5-second clock. In the healthy state the hot path runs only one idempotent
`settings get` for `MILLET_NO_RESTRICT_APP`; it writes and verifies only when
GMS is absent.

The scheduled executor holds no wake lock. It consumes CPU while the device and
daemon are runnable, but it does not wake a suspended device merely to meet the
2.5-second interval.

### Screen-on CPU sample

The guard process was sampled on the same eight-core Xiaomi device before and
after restarting Shizuku so the newly installed MILLET-only implementation was
actually loaded. The display reported `Awake` at both endpoints of each sample.

CPU time is the delta of Linux `/proc/<pid>/stat` fields 14 through 17 (the
guard's user/system time plus reaped child-command user/system time), using the
device's 100 Hz clock tick. Including child time is important because each
`settings` invocation is a short-lived shell child.

| Loop loaded by guard | Wall time | CPU time | One-core utilization | Eight-core aggregate |
|---|---:|---:|---:|---:|
| Previous MILLET + Aurogon loop | 60.03 s | 1.79 s | 2.98% | 0.37% |
| MILLET-only two-second candidate | 60.04 s | 0.98 s | 1.63% | 0.20% |

The focused loop used about 45% less CPU time in this paired short sample. It
still wakes the runnable guard thread and launches one shell command per check,
so the result is not zero-cost or a general battery benchmark. It shows that
removing the unrelated Aurogon read materially reduced the screen-on CPU cost.
It predates the adjustment to 2.5 seconds and is not a measurement of the final
interval.

The first attempted post-install measurement was initially mistaken for the
new implementation: its Shizuku UserService PID had actually survived for
about 6.2 days and still held code loaded from the previous APK. Restarting
Shizuku produced a new guard PID and the lower result above. The app now bumps
its Shizuku UserService version for implementation changes, not only AIDL
changes, so future full installs replace stale daemon code automatically.

A second MILLET-only sample was excluded because the device entered `Dozing`
before its endpoint and therefore was not comparable to the screen-on runs.

Android WorkManager is not an equivalent prompt monitor. Its
[`PeriodicWorkRequest`](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest)
has a 15-minute minimum interval and is intentionally inexact. The existing
recovery worker remains useful for eventual repair and for restarting
protection after process/lifecycle events, but it cannot protect the
five-second removal window.

## Why this is not an Aurogon polling requirement

`Settings.Global.aurogon_enable` has a different writer and lifecycle. On the
examined framework, `CloudDataUpdate` reapplies it after Greeze/system-server
initialization or a relevant Xiaomi cloud-data change. The retained logs
contained no poll-detected Aurogon overwrite despite many direct app-side
reconciliations.

The implementation therefore keeps Aurogon out of the 2.5-second hot loop. It
reconciles Aurogon immediately during protection/configuration operations and
uses the existing 15-minute recovery worker as a safety net. Only the volatile
GMS membership in `MILLET_NO_RESTRICT_APP` remains in `fcmPolling`.

## Why a Settings observer is not the current replacement

A Settings `ContentObserver` would be attractive if it were hosted by a normal,
durable Android application process. The long-lived component here is instead
a Shizuku UserService running as shell. The
[Shizuku UserService documentation](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md#user-service)
warns that this is not a normal Android application process and that
application-context APIs such as `Context.getContentResolver()` generally do
not work there.

An observer in the ordinary app process would disappear when Xiaomi kills that
process, while direct internal-provider access from the UserService would add
fragile, ROM-specific plumbing. Until a reliable event source covers all of
the rewrite paths, the small idempotent shell read is the more dependable
no-root guard.

## Reproduction and verification

Read the owner projection without modifying it:

```sh
adb shell settings --user 0 get system MILLET_NO_RESTRICT_APP
```

To reproduce the ownership behavior safely on a test package:

1. Record the current owner value.
2. Change that test package between Xiaomi's smart/recommended and No
   restrictions policies.
3. Read the setting again and compare the complete package set.
4. Repeat after reboot and after first opening the test app.

Do not infer durability from one unchanged sample. Correlate Settings changes
with PowerKeeper diagnostics and Greezer freeze history, and preserve every
existing user-selected entry in any repair implementation.

## Limits

- The detailed writer analysis applies to the examined PowerKeeper 4.2.00 and
  corresponding HyperOS framework. Xiaomi can change private schema, trigger
  paths, or Greezer policy in later builds.
- Decompiled source can contain renamed locals or reconstructed control-flow
  artifacts. Conclusions above rely on simple data-flow paths and live tests
  wherever possible.
- The observed 16-to-108-second bursts are examples from retained datasets,
  not a claimed lower or upper bound for future rewrites.
