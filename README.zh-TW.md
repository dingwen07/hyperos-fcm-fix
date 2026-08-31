# HyperOS FCM Fix

[English](README.md) | [简体中文](README.zh-CN.md) | **繁體中文**

HyperOS FCM Fix 用於防止小米 HyperOS 凍結 Google Play 服務，或過度限制需要及時接收 Firebase Cloud Messaging (FCM) 通知的應用程式。它使用 Shizuku 提供的 ADB shell 身分，無需 Root。

[<img src="https://raw.githubusercontent.com/machiav3lli/oandbackupx/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="透過 GitHub 取得" height="80">](https://github.com/dingwen07/hyperos-fcm-fix/releases)

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="透過 Obtainium 取得" height="80">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22net.extrawdw.apps.miuisucks.powerkeeper%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fdingwen07%2Fhyperos-fcm-fix%22%2C%22author%22%3A%22dingwen07%22%2C%22name%22%3A%22HyperOS%20FCM%20Fix%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%7D%22%7D)

## 主要功能

- 將 Google Play 服務 (`com.google.android.gms`) 保留在 HyperOS 的隱藏無限制清單 (`MILLET_NO_RESTRICT_APP`) 中，並在 PowerKeeper 重寫該清單後修復此項目。
- 停用已知的 Greezer GMS 限制器，並將 GMS 恢復至正常的執行階段允許清單。
- 提供可選的 FCM 連線保護，用於 CN Google Play 服務可能不會積極重試連線的情況。
- 無需要求 `QUERY_ALL_PACKAGES` 權限即可找出已安裝的第三方 FCM 應用程式。
- 為每個已啟用的應用程式分別提供 Aurogon FCM 保護、自動解除停止、HyperOS 自動啟動和 AOSP 電池最佳化控制。
- 在裝置重新啟動、應用程式更新或 Shizuku 重新啟動後恢復保護，並保留用於疑難排解的診斷記錄。

## 要求與設定

本應用程式僅在小米 HyperOS 裝置的擁有者使用者中執行。應用程式資訊清單依賴小米的 `com.miui.system` 共用程式庫，因此不受支援的裝置無法安裝。本應用程式不會修改 PowerKeeper、清除應用程式資料或使用 Root。

1. 從 [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) 安裝 Shizuku，然後透過無線偵錯、ADB 或 Sui 啟動。Play 商店版 Shizuku 在 Android 16 QPR2 上可能有相容性問題。
2. 安裝並開啟 HyperOS FCM Fix，然後授予 Shizuku 權限。
3. 確認 Google Play 服務顯示 **保護作用中**。GMS 會受到自動保護，無需在應用程式清單中另行啟用。
4. 僅啟用需要及時接收通知的應用程式，然後檢查其 Aurogon、自動解除停止、自動啟動和電池設定。
5. 變更多項設定後或進行疑難排解時，使用 **立即套用**。裝置重新啟動後，請重新啟動 Shizuku 並返回本應用程式，確認保護已恢復。

> [!NOTE]
> 如要停止使用 HyperOS FCM Fix，請先在應用程式及其自動啟動管理開關仍處於啟用狀態時，將每個受管理應用程式的 **HyperOS 自動啟動**設為希望保留的狀態。然後停用所有受管理的應用程式，重新啟動裝置後再解除安裝。停用應用程式會移除其 Aurogon 和自動解除停止規則，並將受管理的電池最佳化恢復為 **最佳化**；其餘暫時性系統變更會在重新啟動後復原。HyperOS 自動啟動是例外，會保留最後套用的狀態。

> [!TIP]
> 建議先將 AOSP 電池最佳化設為 **最佳化**。僅當某個應用程式的通知仍然延遲時才使用 **無限制**，因為允許更多背景活動可能會增加耗電量。

## 保護行為

### Google Play 服務

核心修復會保留 `Settings.System.MILLET_NO_RESTRICT_APP` 中的所有現有項目，僅在缺少 `com.google.android.gms` 時將其附加至清單。Shizuku UserService 預設每 30 秒檢查一次，也可選擇 60 秒或 120 秒。

每次執行完整的 FCM 保護流程時，還會執行等同於 `dumpsys greezer IM GMS disable` 和 `dumpsys greezer LM add com.google.android.gms` 的操作。固定復原工作、裝置啟動或應用程式更新後的復原、**立即套用**以及可設定的定期策略執行都會觸發此流程。這些命令不屬於 30/60/120 秒輪詢。裝置啟動或應用程式更新後，復原工作和過期的策略執行可能同時排入佇列，因此這些命令可能在短時間內執行兩次。僅任一 Greezer 命令失敗時會回報為無法使用，但不會單獨觸發 WorkManager 重試。

UserService 不持有喚醒鎖定。裝置進入 Doze 且處理程序被暫停時，輪詢會暫停，並在處理程序再次取得執行機會後繼續，因此無法保證嚴格按照所選間隔執行。固定的 15 分鐘 WorkManager 復原工作會在 Shizuku 可用時重新建立監控，並重新套用 GMS 和 Aurogon 保護；它也會執行自動解除停止，並在啟用 FCM 連線保護時，最後要求 GMS 重新連線。WorkManager 受系統排程影響，在 Doze 期間可能延後執行。

**FCM 連線保護**是一項獨立的可選功能，用於 CN GMS 可能不會積極重試連線的情況。UserService 可執行時，它會檢查 GMS 是否在 5228–5230 連接埠上有已建立的 FCM 連線；找不到連線或無法檢查時，應用程式會傳送定向的 `com.google.android.intent.action.GCM_RECONNECT` 廣播。復原工作每次實際執行時也會傳送該廣播。關閉此選項不會停用 `MILLET_NO_RESTRICT_APP` 修復。

### 受管理的應用程式

應用程式清單包含宣告了 `com.google.android.c2dm.intent.RECEIVE` 接收器的非系統應用程式套件。每個已啟用的應用程式有四項獨立控制：

- Aurogon FCM 保護：允許 FCM Intent 通過小米的廣播控制並傳送至應用程式，包括傳送過程需要啟動應用程式的情況。
- 自動解除停止（Android 16+）：每 15 分鐘清除一次 `FLAG_STOPPED`，使應用程式仍有資格由 FCM 啟動；該操作本身不會啟動應用程式。
- HyperOS 自動啟動：在 FCM 傳送目標沒有執行中處理程序（例如處理程序已被終止）時允許啟動該處理程序。此時它會與 Aurogon 規則配合。本應用程式會管理小米的兩個自動啟動 AppOp。
- AOSP 電池最佳化：主要用於限制應用程式的背景活動，同時不影響 FCM。它會為所選 Android 使用者套用無限制、最佳化或受限制政策。

首次啟用應用程式時，Aurogon、自動解除停止和自動啟動管理會一併開啟。微信和 Telegram 預設啟用，並將電池最佳化管理設為 **最佳化**；其他找到的應用程式預設停用，且不管理電池最佳化。

停用應用程式時，本應用程式會將其從受管理的 Aurogon 和自動解除停止清單中移除，同時保留已儲存的選擇。如果先前啟用了電池最佳化管理，則會套用一次 **最佳化**；自動啟動狀態不會變更。

**立即套用**會處理所有已啟用的應用程式。另一個 WorkManager 工作會按照 **定期執行頻率**，自動為所有已啟用的應用程式重新套用受管理的自動啟動和電池政策。將其設為 **關閉** 只會停用這個定期執行工作，不會停止 GMS 保護、固定的 15 分鐘 GMS 與 Aurogon 復原工作或自動解除停止。

自動啟動和電池政策以本應用程式中選取的 Android 使用者為目標；擁有者使用者和手機分身使用者預設選取。Android 的無限制 device-idle 允許清單以應用程式套件為全域範圍，因此「無限制」和「最佳化」的變更無法依使用者隔離。

## 診斷

開啟主畫面底部的 **診斷**，可檢視、重新整理、複製或清除工作階段記錄。記錄涵蓋 Shizuku 連線、背景工作、特殊權限操作和 FCM 修復，檔案儲存在 `/storage/emulated/0/Android/data/net.extrawdw.apps.miuisucks.powerkeeper/files/logs/`。Logcat 項目使用 `PowerKeeperFix` 前置字串。

## 技術調查

- [小米 HyperOS GMS、FCM 與 Greezer 調查](docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md)
- [PowerKeeper 重寫 `MILLET_NO_RESTRICT_APP` 的調查](docs/xiaomi-millet-no-restrict-app-rewrite-investigation.md)

## 建置

在 Android Studio 中開啟專案並執行 `app` 設定。
