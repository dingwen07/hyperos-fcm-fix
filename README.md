# HyperOS FCM Fix

> [!NOTE]
> ## 繁體中文說明
>
> **HyperOS FCM Fix** 是給小米 HyperOS 裝置使用的 Android 工具，透過 [Shizuku](https://shizuku.rikka.app/) 取得 ADB shell 權限，修復 HyperOS／PowerKeeper 對 Google Play 服務（`com.google.android.gms`）的背景限制，降低 FCM 推播因 GMS 被凍結而中斷的情況。
>
> ### 先看這裡：實際使用方式與省電取捨
>
> 本工具需要搭配 Shizuku 才能執行，首次開啟 App 時請先啟動 Shizuku，並在 Shizuku 裡授權本工具。Shizuku 的啟動教學請參考其官方文件；本專案不重複說明無線偵錯或 ADB 的設定步驟。
>
> 這個問題涉及兩層不同的背景限制，請不要把它們當成同一個開關：
>
> 1. **本工具的應用列表勾選**：只列出已安裝、宣告 FCM 接收器的非系統 App。勾選後，本工具才會把該 App 納入 Aurogon FCM 保護與你啟用的 HyperOS 自動啟動／Android 電量政策；首次勾選會預設開啟 Aurogon 與自動啟動管理，之前設定過的 App 則沿用原本的細項設定。
> 2. **Android 系統的「電量無限制」**：這是 AOSP 的 device-idle／電池最佳化白名單，和 HyperOS 的 Aurogon、MILLET 及自動啟動規則分開。為了省電，建議先只在本工具清單勾選真正需要即時 FCM 的 App，Android 電量管理先維持「不管理」或「最佳化」。
>
> 如果某個 App 仍然收不到通知，再到 Android 系統設定把該 App 設為「電量無限制」。這種做法可以讓你不必在本工具的應用列表中勾選該 App 來管理它的電量政策；但是，**GMS 的整體 FCM 修復仍然需要本工具至少成功執行一次**，所以不能省略 Shizuku 授權與 GMS protection。
>
> 建議流程如下：
>
> 1. 啟動 Shizuku，開啟本工具並授權。
> 2. 先確認畫面上的 GMS protection 成功；`com.google.android.gms` 由本工具自動處理，不需要在應用列表中手動勾選。
> 3. 在應用列表勾選需要即時 FCM 的 App；進入細項後，Aurogon 通常保持開啟，自動啟動可依需求保留，Android 電量管理先不要強制改成「無限制」。
> 4. 若通知仍延遲或完全收不到，只針對該 App 開啟 Android「電量無限制」，然後重新讓本工具透過 Shizuku 執行一次；也可以使用 **Apply now**。
>
> 根據作者說明與目前的修復流程，以下兩個時機應視為必須重新執行本工具：
>
> - **手機重新開機後**：先重新啟動 Shizuku，再開啟本工具或等待它的 recovery 工作成功。系統會嘗試在開機時排程恢復，但 Shizuku 尚未啟動時無法完成特權修復。
> - **在 Android／HyperOS 設定中變更任何 App 的電量或背景策略後**：PowerKeeper 可能重建其內部清單，連帶移除 GMS 的例外狀態；請重新開啟本工具並讓 Shizuku 執行修復。即使是執行中的監控，也不應視為永久保證。
>
> 若 Shizuku 是透過無線偵錯啟動，離開配對網路或重開機後可能會讓 Shizuku 暫停；不同的啟動方式不一定有相同限制。已寫入的系統狀態不會因 Shizuku 暫停就立刻消失，所以實測可能仍能維持一段時間的 FCM，但監控本身已停止，之後仍可能被 PowerKeeper 覆蓋。重新啟動 Shizuku 並執行本工具，才是可重現的恢復方式。
>
> ### 它會做什麼
>
> 1. 維持 `Settings.System.MILLET_NO_RESTRICT_APP` 中的 GMS 項目，保留原有清單內容，不會覆寫其他項目。
> 2. 停用 Greezer 的暫時性 GMS 限制並恢復其一般允許清單；服務運作期間每 2 秒檢查一次，若 PowerKeeper 改回設定便重新修復。
> 3. 顯示已安裝且可接收 FCM 廣播的非系統 App；你可個別選擇是否管理其 Aurogon、HyperOS 自動啟動與 Android 電池最佳化設定。
> 4. 在開機、App 更新或 Shizuku 重啟後，以 WorkManager 嘗試恢復修復服務；可選的週期性套用只處理你已啟用的 App。
>
> ### 本次 HyperOS 3.0.7 與 3.0.318 調查結果
>
> 我們將同一機型的兩個中國版 OTA 進行解包與反編譯：
>
> - `OS3.0.7.0.WPBCNXM`
> - `OS3.0.318.0.WPBCNXM`
>
> 結果顯示，兩版 `PowerKeeper.apk` 的 `GmsCoreUtils`／`GmsObserver` GMS 判斷邏輯基本相同；主要差異不在 PowerKeeper APK，而在 `system_ext/framework/miui-services.jar` 裡的 Greezer。
>
> 3.0.7 主要透過 `mGmsMultiUid` 處理特定的多使用者 GMS UID。3.0.318 則新增更積極的 GMS 背景限制流程：
>
> 1. `GreezeManagerService` 在螢幕關閉、GMS UID 進入背景時，使用 `isGmsApp(uid)` 觸發 GMS limiter。
> 2. limiter 會移除 `com.google.android.gms` 的 Aurogon 一般允許清單，並對執行中的 GMS UID 呼叫 `triggerQuickFreeze()`。
> 3. 另一條新版路徑會在螢幕關閉後約 5 秒，直接對 `com.google.android.gms` 呼叫 `triggerQuickFreeze(uid, 5000)`。
> 4. `AurogonImmobulusMode.triggerQuickFreeze()` 會先檢查 `MILLET_NO_RESTRICT_APP`；若清單包含 `com.google.android.gms` 就跳過凍結，否則進入 `freezeActionForImmobulus()`。
>
> 因此 3.0.318 的典型 FCM 失效流程是：鎖屏 → GMS 進入背景 → Greezer 移除 GMS allowlist／啟動 quick-freeze → GMS 的 FCM 長連線中斷 → 解鎖後才重新連線。這也解釋了為什麼 3.0.7 正常，而 3.0.318 可能完全收不到鎖屏期間的推播。
>
> `MILLET_NO_RESTRICT_APP` 並不是新版才存在；兩版 Greezer 都會讀取它。新版 `PolicyMaker` 的 freeze filter 由 `149600` 變成 `150112`，新增了 `TOP_APP` filter bit，但 `NO_RESTRICT` bit 仍然存在。修復的關鍵仍是讓 `com.google.android.gms` 成為 Greezer 可識別的 no-restrictions 套件。
>
> 啟動時的下列指令是防禦層，不是完整替代方案：
>
> ```sh
> dumpsys greezer IM GMS disable
> dumpsys greezer LM add com.google.android.gms
> ```
>
> 前者只關閉目前 `system_server` 生命週期中的 GMS limiter，後者只恢復暫時性的 Aurogon allowlist；兩者在 `system_server` 重啟後可能失效，也可能被新版 Greezer／PowerKeeper 重新覆蓋。因此本工具仍需維護 `MILLET_NO_RESTRICT_APP`，並在設定被 PowerKeeper 重建後重新加入 GMS。
>
> 在沒有 root 或 system-server Hook 的裝置上，無法可靠地一次修改 PowerKeeper 的私有 `user_configure.db` 權威資料，也不能保證只執行一次 shell 指令便永久有效；這是本工具需要 Shizuku 監控的原因。以上結論是根據上述兩個 OTA 的靜態反編譯結果，實際行為仍可能因 HyperOS 分支、Greezer 設定與裝置使用者狀態而不同。
>
> ### 使用前須知
>
> - 僅支援小米 HyperOS 的擁有者使用者（user 0）；APK 會要求小米系統提供的 `com.miui.system` shared library，非小米裝置無法安裝。
> - 需要 Shizuku 11 以上，並由無線偵錯／ADB 或 Sui 啟動；首次使用時需在 Shizuku 授權本 App。
> - 本工具不需要 root，也不修改 PowerKeeper APK；但會經 Shizuku 調整系統設定、AppOps 與 device-idle allowlist。請先了解各 App 的背景執行與耗電影響，再啟用個別政策。
> - 不使用網路權限、廣告或分析 SDK。App 只會在本機保存你選取的 App 政策、最近執行結果與可在 App 內清除的診斷記錄。
>
> ### 快速開始
>
> 1. 安裝並啟動 Shizuku，確認其服務正在執行。
> 2. 安裝並開啟本 App，依畫面授與 Shizuku 權限。
> 3. 確認 GMS protection 顯示正常；如要管理其他 App，再從清單啟用並設定各自政策。
> 4. 需要立即套用所有已啟用政策時，使用 **Apply now**；需要排查時，從主畫面底部開啟 **Diagnostics**。
>
> 詳細的技術機制、安全模型與建置方式，請繼續閱讀下方英文文件；小米系統行為的調查記錄在 [docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md](docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md)。

An Android app for Xiaomi HyperOS devices that uses Shizuku's ADB-shell identity to:

- keep `com.google.android.gms` in `Settings.System.MILLET_NO_RESTRICT_APP` without removing existing entries;
- disable Greezer's volatile explicit GMS limiter and restore GMS to its ordinary runtime allowlist;
- promptly restore the hidden no-restrictions entry when PowerKeeper regenerates the setting;
- discover installed third-party FCM receiver apps without requesting `QUERY_ALL_PACKAGES`;
- maintain an app-selected Aurogon FCM allowlist and HyperOS Autostart policy;
- apply per-app AOSP Battery optimization as Unrestricted, Optimized, Restricted, or Don’t change for selected Android users;
- periodically restore AppOps and battery policies only for apps that opt into periodic enforcement;
- periodically clear `FLAG_STOPPED` for selected FCM apps without launching them;
- protect its own fallback watchdog from HyperOS background limits.

## FCM protection

The durable package-specific fix reads the current `MILLET_NO_RESTRICT_APP` value, appends `com.google.android.gms` only when absent, writes the preserved list, and verifies the result. A daemon Shizuku UserService checks that setting and the app-managed Aurogon rules every two seconds while the device is active, repairing PowerKeeper overwrites before the identified delayed-freeze paths normally run. Java timers do not wake a suspended device; PowerKeeper rewrites normally occur while the user is already interacting with its UI.

At service startup the app also runs these defense-in-depth commands:

```sh
dumpsys greezer IM GMS disable
dumpsys greezer LM add com.google.android.gms
```

The first command clears the runtime `mGmsLimitEnabled` flag. The second restores GMS to Aurogon's ordinary runtime allowlist. Neither replaces the `MILLET_NO_RESTRICT_APP` repair, which also covers the separate `PowerStrategyMode` freeze paths.

WorkManager remains a recovery/bootstrap fallback. A fixed 15-minute recovery job recreates the daemon Shizuku monitor after boot, app updates, or Shizuku restarts, and clears `FLAG_STOPPED` for enabled FCM apps whose Auto unstop switch is on. A separate configurable job refreshes FCM protection, the opted-in apps' Autostart and AOSP Battery optimization policies, and this app's own HyperOS background permissions; it can be disabled without disabling the dedicated FCM recovery, Auto unstop, or a running Shizuku monitor. WorkManager's timing is not used as the primary response to a PowerKeeper setting rewrite.

The app list is scoped to non-system packages exposing a receiver for `com.google.android.c2dm.intent.RECEIVE`; system and updated-system apps are excluded, while previously saved packages remain visible if they are no longer installed. Enabling an app activates its saved detail configuration. On a first enable, Aurogon, Auto unstop, and management of both Xiaomi Autostart AppOps are enabled; later enable/disable cycles remember the Aurogon, Auto unstop, Autostart, battery, and periodic-enforcement selections. Disabling an app removes it from this app's Aurogon and Auto unstop package sets. If AOSP Battery optimization management was enabled, disabling also applies Optimized once; otherwise it leaves battery state unchanged. Autostart is never changed by app-off. All later startup, periodic, and manual full passes exclude that app until it is enabled again. Aurogon and Auto unstop are independent detail switches: disabling either does not disable the app or stop its other configured policies, and Auto unstop is not gated by Periodic enforcement. Packages in `HYPEROS_AUTO_UNRESTRICTED_PACKAGES` (currently `com.tencent.mm` and `org.telegram.messenger`) default to app enabled, Aurogon and Auto unstop on, Autostart management on and Enabled, battery management on and Optimized, and periodic enforcement on. Other packages default to app disabled with Aurogon, Auto unstop, Autostart, and battery management off; their retained selector defaults are Disabled and Optimized. That package set is used only to seed defaults. Individual controls use targeted Shizuku operations, so changing one setting does not run the full enforcement pass. The explicit **Apply now** action processes every enabled app and runs Auto unstop for selected packages; startup uses the same enabled-app guard plus the per-app periodic-enforcement filter when the last pass is stale, while the fixed 15-minute FCM recovery job independently handles Auto unstop.

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
