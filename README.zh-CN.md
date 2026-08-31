# HyperOS FCM Fix

[English](README.md) | **简体中文** | [繁體中文](README.zh-TW.md)

HyperOS FCM Fix 用于防止小米 HyperOS 冻结 Google Play 服务，或过度限制需要及时接收 Firebase Cloud Messaging (FCM) 通知的应用。它使用 Shizuku 提供的 ADB shell 身份，无需 Root。

[<img src="https://raw.githubusercontent.com/machiav3lli/oandbackupx/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="通过 GitHub 获取" height="80">](https://github.com/dingwen07/hyperos-fcm-fix/releases)

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="通过 Obtainium 获取" height="80">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22net.extrawdw.apps.miuisucks.powerkeeper%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fdingwen07%2Fhyperos-fcm-fix%22%2C%22author%22%3A%22dingwen07%22%2C%22name%22%3A%22HyperOS%20FCM%20Fix%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%7D%22%7D)

## 主要功能

- 将 Google Play 服务 (`com.google.android.gms`) 保留在 HyperOS 的隐藏无限制列表 (`MILLET_NO_RESTRICT_APP`) 中，并在 PowerKeeper 重写该列表后修复此条目。
- 停用已知的 Greezer GMS 限制器，并将 GMS 恢复到正常的运行时允许列表。
- 提供可选的 FCM 连接保护，用于 CN Google Play 服务可能不积极重试连接的情况。
- 无需请求 `QUERY_ALL_PACKAGES` 权限即可查找已安装的第三方 FCM 应用。
- 为每个已启用应用分别提供 Aurogon FCM 保护、自动解除停止、HyperOS 自启动和 AOSP 电池优化控制。
- 在设备重启、应用更新或 Shizuku 重启后恢复保护，并保留用于故障排查的诊断记录。

## 要求与设置

本应用仅在小米 HyperOS 设备的机主用户中运行。应用清单依赖小米的 `com.miui.system` 共享库，因此不受支持的设备无法安装。本应用不会修改 PowerKeeper、清除应用数据或使用 Root。

1. 从 [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) 安装 Shizuku，然后通过无线调试、ADB 或 Sui 启动。Play 商店版 Shizuku 在 Android 16 QPR2 上可能存在兼容性问题。
2. 安装并打开 HyperOS FCM Fix，然后授予 Shizuku 权限。
3. 确认 Google Play 服务显示 **保护已启用**。GMS 会受到自动保护，无需在应用列表中另行启用。
4. 仅启用需要及时接收通知的应用，然后检查其 Aurogon、自动解除停止、自启动和电池设置。
5. 更改多项设置后或进行故障排查时，使用 **立即应用**。设备重启后，请重新启动 Shizuku 并返回本应用，确认保护已恢复。

> [!NOTE]
> 如要停止使用 HyperOS FCM Fix，请先在应用及其自启动管理开关仍处于启用状态时，将每个受管理应用的 **HyperOS 自启动**设为希望保留的状态。然后停用所有受管理应用，重启设备后再卸载。停用应用会移除其 Aurogon 和自动解除停止规则，并将受管理的电池优化恢复为 **优化**；其余临时系统更改会在重启后复原。HyperOS 自启动是例外，会保留最后应用的状态。

> [!TIP]
> 建议先将 AOSP 电池优化设为 **优化**。仅当某个应用的通知仍然延迟时才使用 **无限制**，因为允许更多后台活动可能会增加耗电量。

## 保护行为

### Google Play 服务

核心修复会保留 `Settings.System.MILLET_NO_RESTRICT_APP` 中的所有现有条目，仅在缺少 `com.google.android.gms` 时将其追加到列表。Shizuku UserService 默认每 30 秒检查一次，也可选择 60 秒或 120 秒。

每次执行完整的 FCM 保护流程时，还会执行等效于 `dumpsys greezer IM GMS disable` 和 `dumpsys greezer LM add com.google.android.gms` 的操作。固定恢复任务、设备启动或应用更新后的恢复、**立即应用**以及可配置的定期策略执行都会触发该流程。这些命令不属于 30/60/120 秒轮询。设备启动或应用更新后，恢复任务和过期的策略执行可能同时入队，因此这些命令可能在短时间内执行两次。仅任一 Greezer 命令失败时会报告为不可用，但不会单独触发 WorkManager 重试。

UserService 不持有唤醒锁。设备进入 Doze 且进程被挂起时，轮询会暂停，并在进程再次获得运行机会后继续，因此无法保证严格按照所选间隔执行。固定的 15 分钟 WorkManager 恢复任务会在 Shizuku 可用时重新建立监控，并重新应用 GMS 和 Aurogon 保护；它也会执行自动解除停止，并在启用 FCM 连接保护时，最后请求 GMS 重新连接。WorkManager 受系统调度影响，在 Doze 期间可能延后运行。

**FCM 连接保护**是一项独立的可选功能，用于 CN GMS 可能不积极重试连接的情况。UserService 可运行时，它会检查 GMS 是否在 5228–5230 端口上存在已建立的 FCM 连接；未找到连接或无法检查时，应用会发送定向的 `com.google.android.intent.action.GCM_RECONNECT` 广播。恢复任务每次实际运行时也会发送该广播。关闭此选项不会停用 `MILLET_NO_RESTRICT_APP` 修复。

### 受管理的应用

应用列表包含声明了 `com.google.android.c2dm.intent.RECEIVE` 接收器的非系统应用包。每个已启用应用有四项独立控制：

- Aurogon FCM 保护：允许 FCM Intent 通过小米的广播控制并投递到应用，包括投递过程需要启动应用的情况。
- 自动解除停止（Android 16+）：每 15 分钟清除一次 `FLAG_STOPPED`，使应用仍有资格由 FCM 启动；该操作本身不会启动应用。
- HyperOS 自启动：在 FCM 投递目标没有运行中进程（例如进程已被杀死）时允许启动该进程。此时它会与 Aurogon 规则配合。本应用会管理小米的两个自启动 AppOp。
- AOSP 电池优化：主要用于限制应用的后台活动，同时不影响 FCM。它会为所选 Android 用户应用无限制、优化或受限制策略。

首次启用应用时，Aurogon、自动解除停止和自启动管理会一并开启。微信和 Telegram 默认启用，并将电池优化管理设为 **优化**；其他发现的应用默认停用，且不管理电池优化。

停用应用时，本应用会将其从受管理的 Aurogon 和自动解除停止列表中移除，同时保留已保存的选择。如果此前启用了电池优化管理，则会应用一次 **优化**；自启动状态不会改变。

**立即应用**会处理所有已启用应用。另一个 WorkManager 任务会按照 **定期执行频率**，自动为所有已启用应用重新应用受管理的自启动和电池策略。将其设为 **关闭** 只会停用这个定期执行任务，不会停止 GMS 保护、固定的 15 分钟 GMS 与 Aurogon 恢复任务或自动解除停止。

自启动和电池策略以本应用中选定的 Android 用户为目标；机主用户和手机分身用户默认选中。Android 的无限制 device-idle 允许列表以应用包为全局范围，因此“无限制”和“优化”的更改无法按用户隔离。

## 诊断

打开主屏幕底部的 **诊断信息**，可查看、刷新、复制或清除会话日志。日志涵盖 Shizuku 连接、后台任务、特权操作和 FCM 修复，文件保存在 `/storage/emulated/0/Android/data/net.extrawdw.apps.miuisucks.powerkeeper/files/logs/`。Logcat 条目使用 `PowerKeeperFix` 前缀。

## 技术调查

- [小米 HyperOS GMS、FCM 与 Greezer 调查](docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md)
- [PowerKeeper 重写 `MILLET_NO_RESTRICT_APP` 的调查](docs/xiaomi-millet-no-restrict-app-rewrite-investigation.md)

## 构建

在 Android Studio 中打开项目并运行 `app` 配置。
