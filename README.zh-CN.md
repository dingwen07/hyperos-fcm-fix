# HyperOS FCM Fix

[English](README.md) | **简体中文** | [繁體中文](README.zh-TW.md)

HyperOS FCM Fix 用于解决小米 HyperOS 冻结 Google Play 服务或限制应用后台运行所导致的 Firebase Cloud Messaging (FCM) 通知延迟问题。它使用 Shizuku 提供的 ADB shell 身份，无需 Root。

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="通过 Obtainium 获取" height="80">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22net.extrawdw.apps.miuisucks.powerkeeper%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fdingwen07%2Fhyperos-fcm-fix%22%2C%22author%22%3A%22dingwen07%22%2C%22name%22%3A%22HyperOS%20FCM%20Fix%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Atrue%7D%22%7D)

## 功能简介

- 保护 Google Play 服务 (`com.google.android.gms`)，使其免受已知会中断 FCM 连接的 HyperOS 和 Greezer 限制。
- 查找已安装且接收 FCM 消息的第三方应用，让用户选择需要保护的应用。
- 分别提供 Aurogon FCM 保护、自动解除停止、HyperOS 自启动和 AOSP 电池优化控制。
- 在 Shizuku 可用时监控相关 HyperOS 设置，并修复 PowerKeeper 所做的更改。
- 在设备重启、应用更新或 Shizuku 重启后通过恢复任务重新应用保护。

## 使用方法

1. 从 [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) 安装 Shizuku，然后通过无线调试、ADB 或 Sui 启动。Play 商店上的 Shizuku 版本在 Android 16 QPR2 上可能有兼容性问题。
2. 安装并打开 HyperOS FCM Fix，然后授予 Shizuku 权限。
3. 确认 Google Play 服务显示 **保护已启用**。GMS 会受到自动保护，无需在应用列表中选择。
4. 仅启用需要及时接收通知的应用。查看每个应用的 Aurogon、自动解除停止、自启动和电池设置；默认值适合作为起点。
5. 更改多项设置后或进行故障排查时，使用 **立即应用**。设备重启后，请重新启动 Shizuku 并返回本应用，确认保护已恢复。

> [!TIP]
> 建议先将 AOSP 电池优化设为 **优化**。仅当某个应用的通知仍然延迟时才使用 **无限制**，因为允许更多后台活动可能会增加耗电量。

## 技术概览

本应用使用 Shizuku 提供的 ADB shell 身份执行以下操作：

- 在不删除现有条目的情况下，确保 `com.google.android.gms` 保留在 `Settings.System.MILLET_NO_RESTRICT_APP` 中；
- 停用 Greezer 中易失的显式 GMS 限制器，并将 GMS 恢复到常规的运行时允许列表；
- 当 PowerKeeper 重新生成设置时，及时恢复隐藏的无限制条目；
- 无需请求 `QUERY_ALL_PACKAGES` 权限即可发现已安装且能够接收 FCM 的第三方应用；
- 维护由用户选择的 Aurogon FCM 允许列表和 HyperOS 自启动策略；
- 为所选 Android 用户按应用设置 AOSP 电池优化策略，包括无限制、优化、受限制或不更改；
- 仅为选择了定期重新应用设置的应用定期恢复 AppOps 和电池策略；
- 定期清除所选 FCM 应用的 `FLAG_STOPPED`，且不启动这些应用；
- 保护应用自身的后备监控程序免受 HyperOS 后台限制。

## FCM 保护

这个持久的应用包级修复会读取当前的 `MILLET_NO_RESTRICT_APP` 值，仅在缺少 `com.google.android.gms` 时追加该应用包，写回保留了原有内容的列表，并验证结果。设备处于活动状态时，长期运行的 Shizuku UserService 每两秒检查一次此设置和由本应用管理的 Aurogon 规则，在已知的延迟冻结路径通常开始运行前修复 PowerKeeper 的覆盖操作。Java 定时器无法唤醒已挂起的设备；不过，PowerKeeper 通常会在用户与其界面交互时重写此设置。

服务启动时还会运行以下纵深防御命令：

```sh
dumpsys greezer IM GMS disable
dumpsys greezer LM add com.google.android.gms
```

第一条命令会清除运行时的 `mGmsLimitEnabled` 标志。第二条命令会将 GMS 恢复到 Aurogon 的常规运行时允许列表。两条命令都不能替代 `MILLET_NO_RESTRICT_APP` 修复；后者还涵盖独立的 `PowerStrategyMode` 冻结路径。

WorkManager 提供恢复和引导后备机制。固定的 15 分钟恢复任务会在设备重启、应用更新或 Shizuku 重启后重新创建 Shizuku 监控，并为已启用且开启了“自动解除停止”的 FCM 应用清除 `FLAG_STOPPED`。另一个可配置任务会刷新 FCM 保护、已选择应用的自启动和 AOSP 电池优化策略，以及本应用自身的 HyperOS 后台权限。停用该任务不会停用专用的 FCM 恢复、“自动解除停止”或正在运行的 Shizuku 监控。WorkManager 的执行时间并不是应对 PowerKeeper 重写设置的主要机制。

应用列表仅包含为 `com.google.android.c2dm.intent.RECEIVE` 公开了接收器的非系统应用包。系统应用和更新后的系统应用会被排除；以前保存过的应用包即使已卸载，仍会显示在列表中。

启用应用会激活已保存的配置。首次启用应用时，会开启 Aurogon、“自动解除停止”以及两个小米自启动 AppOp 的管理。此后的启用和停用操作会保留 Aurogon、“自动解除停止”、自启动、电池和定期重新应用设置的选择。停用应用会将其从本应用的 Aurogon 和“自动解除停止”应用包集合中移除。如果此前开启了 AOSP 电池优化管理，停用应用时还会应用一次“优化”策略；否则，其电池状态保持不变。停用应用绝不会更改自启动设置。之后的启动、定期和手动完整执行都会排除该应用，直到它再次启用。Aurogon 和“自动解除停止”是两个独立设置：停用其中任意一项都不会停用应用或停止其他已配置策略，而且“自动解除停止”不受“定期重新应用设置”控制。

对于 `HYPEROS_AUTO_UNRESTRICTED_PACKAGES` 中的应用包（目前为 `com.tencent.mm` 和 `org.telegram.messenger`），默认会启用应用、Aurogon 和“自动解除停止”，开启自启动管理并设为“启用”，开启电池管理并设为“优化”，同时开启定期重新应用设置。其他应用包默认不启用应用，并关闭 Aurogon、“自动解除停止”、自启动管理和电池管理；其保留的选择器默认值分别为“停用”和“优化”。此应用包集合仅用于提供初始默认值。

各项控件使用定向的 Shizuku 操作，因此更改一项设置不会运行完整的应用流程。明确点击 **立即应用** 会处理每个已启用应用，并对所选应用包执行“自动解除停止”。当上次执行结果过期时，应用启动过程会使用相同的已启用应用检查和按应用设置的定期应用筛选器；固定的 15 分钟 FCM 恢复任务则独立处理“自动解除停止”。

自启动和 AOSP 电池优化各有一个独立的管理开关。关闭管理开关会阻止对相应设置进行 AppOps 更改，同时保留选定值，并隐藏其 Material 3 选择器。开启管理后，选择器会重新显示并应用保留的值。电池选择器依次为“无限制”“优化”“受限制”。

批量应用设置时，只会各协调一次 GMS 和 Aurogon，为每个所选 Android 用户各获取一次已安装应用包快照，然后每个 shell 脚本最多处理 16 项应用策略。**立即应用**、定期 WorkManager 和启动时的过期应用都使用这条有界路径。中间批次会省略管理器自我保护和 `appops write-settings`；这些操作在每次批量执行中只运行一次。**立即应用** 按钮会接收各批次中每个应用的完成标记，并准确显示已完成数和总数。

## 诊断

应用内的诊断查看器会将 WorkManager 执行记录、Shizuku 连接事件、特权命令和 FCM 修复写入轮换的会话文件。界面操作不会被记录。打开主屏幕底部的 **诊断信息**，可以查看和选择文本、刷新活动会话或清除全部会话。Logcat 条目使用 `PowerKeeperFix` 前缀。应用最多保留 20 个会话，文件大小达到约 1 MB 时会轮换，并显示大型会话中最新的 200 KB 内容。

## 仅限小米设备安装

清单要求存在由小米固件提供、以 APK 为载体的 `com.miui.system` 共享库。缺少此库时，Android 软件包管理器会拒绝安装。运行时保护会在调度任务或调用 Shizuku 前，验证当前为 Android 机主用户 0、制造商为 `Xiaomi`、共享库存在，并且 `com.miui.system` 是系统应用包。次要用户和手机分身实例会保持不活动；机主用户实例仍可管理所选 Android 用户的按应用策略。

本应用会为机主用户 0 启用两个小米自启动 AppOp：执行操作 `10008` 和安全中心开关状态操作 `10053`。自我保护还会允许小米的开机完成 (`10007`)、后台启动 Activity (`10021`) 和前台服务 (`10023`) 限制项。清单中的一个小型接收器只监听 `BOOT_COMPLETED` 和应用专属的 `MY_PACKAGE_REPLACED` 广播，随后恢复定期计划并立即尝试恢复。

## 安全模型

本应用不使用 Root、隐藏 API、UID 级 AppOps、卸载操作、数据清除、界面自动化或破坏性文件系统操作，也不会修改 PowerKeeper 应用包。长期运行的 Shizuku UserService 只公开本应用所需的固定设置应用操作和 FCM 监控操作。

Android 的 device-idle 允许列表以应用 ID 为全局范围。因此，“无限制”和“优化”的允许列表更改不区分用户；按用户设置的 AppOps 更改仅以本应用中启用的 Android 用户为目标。机主用户 (`0`) 和手机分身用户 (`999`) 默认启用，其他发现的用户默认停用。

## 技术调查

FCM 保护设计所依据的脱敏设备与框架调查记录位于 [docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md](docs/xiaomi-hyperos-gms-fcm-greezer-investigation.md)。

## 构建

在 Android Studio 中打开项目并运行 `app` 配置。在目标小米设备上安装最新的 [Shizuku GitHub 版本](https://github.com/RikkaApps/Shizuku/releases)，并通过无线调试、ADB 或 Sui 启动。用户必须在 Shizuku 中授权 HyperOS FCM Fix。
