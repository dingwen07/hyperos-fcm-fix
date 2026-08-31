# HyperOS FCM Fix

[English](README.md) | [简体中文](README.zh-CN.md) | **繁體中文**

HyperOS FCM Fix 用於解決小米 HyperOS 凍結 Google Play 服務或限制應用程式背景執行所導致的 Firebase Cloud Messaging (FCM) 通知延遲問題。它使用 Shizuku 提供的 ADB shell 身分，無需 Root。

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="透過 Obtainium 取得" height="80">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22net.extrawdw.apps.miuisucks.powerkeeper%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fdingwen07%2Fhyperos-fcm-fix%22%2C%22author%22%3A%22dingwen07%22%2C%22name%22%3A%22HyperOS%20FCM%20Fix%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%7D%22%7D)

## 功能簡介

- 保護 Google Play 服務 (`com.google.android.gms`)，使其免受已知會中斷 FCM 連線的 HyperOS 和 Greezer 限制。
- 找出已安裝且接收 FCM 訊息的第三方應用程式，讓使用者選擇需要保護的應用程式。
- 分別提供 Aurogon FCM 保護、自動解除停止、HyperOS 自動啟動和 AOSP 電池最佳化控制。
- 在 Shizuku 可用時監控相關 HyperOS 設定，並修復 PowerKeeper 所做的變更。
- 在裝置重新啟動、應用程式更新或 Shizuku 重新啟動後透過恢復工作重新套用保護。

## 使用方式

1. 從 [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) 安裝 Shizuku，然後透過無線偵錯、ADB 或 Sui 啟動。注意：Play 商店中的 Shizuku 版本在 Android 16 QPR2 上可能有相容性問題。
2. 安裝並開啟 HyperOS FCM Fix，然後授予 Shizuku 權限。
3. 確認 Google Play 服務顯示 **保護作用中**。GMS 會受到自動保護，無需在應用程式清單中選取。
4. 僅啟用需要及時接收通知的應用程式。檢查每個應用程式的 Aurogon、自動解除停止、自動啟動和電池設定；預設值適合作為起點。
5. 變更多項設定後或進行疑難排解時，使用 **立即套用**。裝置重新啟動後，請重新啟動 Shizuku 並返回本應用程式，確認保護已恢復。

> [!TIP]
> 建議先將 AOSP 電池最佳化設為 **最佳化**。僅當某個應用程式的通知仍然延遲時才使用 **無限制**，因為允許更多背景活動可能會增加耗電量。

## 技術概覽

本應用程式使用 Shizuku 提供的 ADB shell 身分執行以下操作：

- 在不刪除現有項目的情況下，確保 `com.google.android.gms` 保留在 `Settings.System.MILLET_NO_RESTRICT_APP` 中；
- 停用 Greezer 中易失的明確 GMS 限制器，並將 GMS 恢復至一般的執行階段允許清單；
- 當 PowerKeeper 重新產生設定時，及時恢復隱藏的無限制項目；
- 無需要求 `QUERY_ALL_PACKAGES` 權限即可找出已安裝且能夠接收 FCM 的第三方應用程式；
- 維護由使用者選取的 Aurogon FCM 允許清單和 HyperOS 自動啟動政策；
- 為所選 Android 使用者依應用程式設定 AOSP 電池最佳化政策，包括無限制、最佳化、受限制或不變更；
- 僅為選取了週期性套用的應用程式定期恢復 AppOps 和電池政策；
- 定期清除所選 FCM 應用程式的 `FLAG_STOPPED`，且不啟動這些應用程式；
- 保護應用程式本身的備援監控程式免受 HyperOS 背景限制。

## FCM 保護

這個持久的應用程式套件層級修復會讀取目前的 `MILLET_NO_RESTRICT_APP` 值，僅在缺少 `com.google.android.gms` 時附加該應用程式套件，寫回保留了原有內容的清單，並驗證結果。裝置處於使用中狀態時，長期執行的 Shizuku UserService 預設每 2.5 秒檢查一次此設定；應用程式也提供 5 秒、10 秒和 30 秒間隔。輪詢和修復迴圈始終完全在 UserService 內執行，並在已知的延遲凍結路徑通常開始執行前修復 PowerKeeper 的覆寫操作。Aurogon 規則會在保護或設定操作期間立即協調，並由現有的 15 分鐘恢復工作兜底。Java 計時器無法喚醒已暫停的裝置；不過，PowerKeeper 通常會在使用者與其介面互動時重寫此設定。

服務啟動時還會執行以下縱深防禦命令：

```sh
dumpsys greezer IM GMS disable
dumpsys greezer LM add com.google.android.gms
```

第一條命令會清除執行階段的 `mGmsLimitEnabled` 旗標。第二條命令會將 GMS 恢復至 Aurogon 的一般執行階段允許清單。兩條命令都不能取代 `MILLET_NO_RESTRICT_APP` 修復；後者還涵蓋獨立的 `PowerStrategyMode` 凍結路徑。

WorkManager 提供恢復和啟動備援機制。固定的 15 分鐘恢復工作會在裝置重新啟動、應用程式更新或 Shizuku 重新啟動後重新建立 Shizuku 監控，並為已啟用且開啟了「自動解除停止」的 FCM 應用程式清除 `FLAG_STOPPED`。另一個可設定的工作會重新整理 FCM 保護、已選取應用程式的自動啟動和 AOSP 電池最佳化政策，以及本應用程式本身的 HyperOS 背景權限。停用該工作不會停用專用的 FCM 恢復、「自動解除停止」或正在執行的 Shizuku 監控。WorkManager 的執行時間並不是應對 PowerKeeper 重寫設定的主要機制。

應用程式清單僅包含為 `com.google.android.c2dm.intent.RECEIVE` 公開了接收器的非系統應用程式套件。系統應用程式和更新後的系統應用程式會被排除；以前儲存過的應用程式套件即使已解除安裝，仍會顯示在清單中。

啟用應用程式會啟用已儲存的設定。首次啟用應用程式時，會開啟 Aurogon、「自動解除停止」以及兩個小米自動啟動 AppOp 的管理。此後的啟用和停用操作會保留 Aurogon、「自動解除停止」、自動啟動、電池和週期性套用的選擇。停用應用程式會將其從本應用程式的 Aurogon 和「自動解除停止」應用程式套件集合中移除。如果先前開啟了 AOSP 電池最佳化管理，停用應用程式時還會套用一次「最佳化」政策；否則，其電池狀態保持不變。停用應用程式絕不會變更自動啟動設定。之後的啟動、週期性和手動完整執行都會排除該應用程式，直到它再次啟用。Aurogon 和「自動解除停止」是兩個獨立設定：停用其中任一項都不會停用應用程式或停止其他已設定的政策，而且「自動解除停止」不受「週期性套用」控制。

對於 `HYPEROS_AUTO_UNRESTRICTED_PACKAGES` 中的應用程式套件（目前為 `com.tencent.mm` 和 `org.telegram.messenger`），預設會啟用應用程式、Aurogon 和「自動解除停止」，開啟自動啟動管理並設為「啟用」，開啟電池管理並設為「最佳化」，同時開啟週期性套用。其他應用程式套件預設不啟用應用程式，並關閉 Aurogon、「自動解除停止」、自動啟動管理和電池管理；其保留的選取器預設值分別為「停用」和「最佳化」。此應用程式套件集合僅用於提供初始預設值。

各項控制項使用定向的 Shizuku 操作，因此變更一項設定不會執行完整的套用程序。明確點選 **立即套用** 會處理每個已啟用的應用程式，並對所選應用程式套件執行「自動解除停止」。當上次執行結果過期時，應用程式啟動程序會使用相同的已啟用應用程式檢查和依應用程式設定的週期性套用篩選器；固定的 15 分鐘 FCM 恢復工作則獨立處理「自動解除停止」。

自動啟動和 AOSP 電池最佳化各有一個獨立的管理開關。關閉管理開關會阻止對相應設定進行 AppOps 變更，同時保留選定值，並隱藏其 Material 3 選取器。開啟管理後，選取器會重新顯示並套用保留的值。電池選取器依序為「無限制」「最佳化」「受限制」。

批次套用設定時，只會各協調一次 GMS 和 Aurogon，為每個所選 Android 使用者各取得一次已安裝應用程式套件快照，然後每個 shell 指令碼最多處理 16 項應用程式政策。**立即套用**、週期性 WorkManager 和啟動時的過期套用都使用這條有界路徑。中間批次會略過管理器自我保護和 `appops write-settings`；這些操作在每次批次執行中只會執行一次。**立即套用** 按鈕會接收各批次中每個應用程式的完成標記，並準確顯示已完成數和總數。

## 診斷

應用程式內的診斷檢視器會將 WorkManager 執行記錄、Shizuku 連線事件、特殊權限命令和 FCM 修復寫入輪替的工作階段檔案。介面操作不會被記錄。開啟主畫面底部的 **診斷**，可以檢視和選取文字、重新整理使用中的工作階段或清除全部工作階段。Logcat 項目使用 `PowerKeeperFix` 前置字串。應用程式最多保留 20 個工作階段，檔案大小達到約 1 MB 時會輪替，並顯示大型工作階段中最新的 200 KB 內容。

## 僅限小米裝置安裝

資訊清單要求存在由小米韌體提供、以 APK 為載體的 `com.miui.system` 共用程式庫。缺少此程式庫時，Android 套件管理員會拒絕安裝。執行階段防護會在排程工作或叫用 Shizuku 前，驗證目前為 Android 擁有者使用者 0、製造商為 `Xiaomi`、共用程式庫存在，並且 `com.miui.system` 是系統應用程式套件。次要使用者和 XSpace 執行個體會保持不使用；擁有者使用者執行個體仍可管理所選 Android 使用者的個別應用程式政策。

本應用程式會為擁有者使用者 0 啟用兩個小米自動啟動 AppOp：強制套用操作 `10008` 和安全中心開關狀態操作 `10053`。自我保護還會允許小米的開機完成 (`10007`)、背景啟動 Activity (`10021`) 和前景服務 (`10023`) 限制項。資訊清單中的一個小型接收器只會接聽 `BOOT_COMPLETED` 和應用程式專屬的 `MY_PACKAGE_REPLACED` 廣播，隨後恢復週期性排程並立即嘗試恢復。

## 安全模型

本應用程式不使用 Root、隱藏 API、UID 層級 AppOps、解除安裝操作、資料清除、介面自動化或破壞性檔案系統操作，也不會修改 PowerKeeper 應用程式套件。長期執行的 Shizuku UserService 只會公開本應用程式所需的固定設定套用操作和 FCM 監控操作。

Android 的 device-idle 允許清單以應用程式 ID 為全域範圍。因此，「無限制」和「最佳化」的允許清單變更不區分使用者；依使用者設定的 AppOps 變更僅以本應用程式中啟用的 Android 使用者為目標。擁有者使用者 (`0`) 和 XSpace 使用者 (`999`) 預設啟用，其他找到的使用者預設停用。

## 技術調查

FCM 保護設計所依據的去識別化裝置與框架調查記錄位於 [docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md](docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md)。另有一份專題報告說明 [PowerKeeper 何時重寫 `MILLET_NO_RESTRICT_APP`，以及為什麼需要即時的監控迴圈](docs/xiaomi-millet-no-restrict-app-rewrite-investigation.md)。

## 建置

在 Android Studio 中開啟專案並執行 `app` 設定。在目標小米裝置上安裝最新的 [Shizuku GitHub 版本](https://github.com/RikkaApps/Shizuku/releases)，並透過無線偵錯、ADB 或 Sui 啟動。使用者必須在 Shizuku 中授權 HyperOS FCM Fix。
