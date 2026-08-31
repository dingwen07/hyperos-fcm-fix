package net.extrawdw.apps.miuisucks.powerkeeper

/** Builds process-free Binder shell commands for app-policy enforcement. */
object EnforcementCommandPlan {
    private val selfProtectionMiuiOps = listOf(
        MIUI_BOOT_COMPLETED_OP,
        MIUI_AUTOSTART_OP,
        MIUI_BACKGROUND_START_ACTIVITY_OP,
        MIUI_SERVICE_FOREGROUND_OP,
        MIUI_AUTOSTART_SWITCH_OP,
    )

    fun policyCommands(
        policy: AppPolicy,
        userId: Int,
        includeAutostartSwitchOp: Boolean,
    ): List<SystemServiceCommand> {
        requireValidPackageName(policy.packageName)
        require(userId >= 0) { "Invalid Android user ID" }
        val commands = mutableListOf<SystemServiceCommand>()
        if (policy.autostartManaged) {
            val mode = if (policy.autostartEnabled) "allow" else "ignore"
            commands += appOp(userId, policy.packageName, MIUI_AUTOSTART_OP.toString(), mode)
            if (includeAutostartSwitchOp) {
                commands += appOp(userId, policy.packageName, MIUI_AUTOSTART_SWITCH_OP.toString(), mode)
            }
        }
        if (policy.dozeManaged) {
            val mode = if (policy.dozePolicy == AppDozePolicy.RESTRICTED) "ignore" else "allow"
            commands += appOp(userId, policy.packageName, RUN_IN_BACKGROUND, mode)
            commands += appOp(userId, policy.packageName, RUN_ANY_IN_BACKGROUND, mode)
        }
        return commands
    }

    fun dozeWhitelistCommand(policy: AppPolicy): SystemServiceCommand? {
        requireValidPackageName(policy.packageName)
        if (!policy.dozeManaged) return null
        val operation = if (policy.dozePolicy == AppDozePolicy.UNRESTRICTED) "+" else "-"
        return SystemServiceCommands.deviceIdle("whitelist", "$operation${policy.packageName}")
    }

    fun selfProtectionCommands(
        applicationId: String,
        includeAutostartSwitchOp: Boolean,
    ): List<SystemServiceCommand> {
        requireValidPackageName(applicationId)
        return buildList {
            add(SystemServiceCommands.deviceIdle("whitelist", "+$applicationId"))
            add(appOp(0, applicationId, RUN_IN_BACKGROUND, "allow"))
            add(appOp(0, applicationId, RUN_ANY_IN_BACKGROUND, "allow"))
            selfProtectionMiuiOps
                .filter { includeAutostartSwitchOp || it != MIUI_AUTOSTART_SWITCH_OP }
                .forEach { operation -> add(appOp(0, applicationId, operation.toString(), "allow")) }
            add(SystemServiceCommands.activity("set-inactive", "--user", "0", applicationId, "false"))
            add(SystemServiceCommands.activity("set-standby-bucket", "--user", "0", applicationId, "active"))
        }
    }

    fun writeAppOpsSettingsCommand(): SystemServiceCommand =
        SystemServiceCommands.appOps("write-settings")

    fun requireValidPackageName(packageName: String) {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
    }

    private fun appOp(userId: Int, packageName: String, operation: String, mode: String) =
        SystemServiceCommands.appOps(
            "set",
            "--user",
            userId.toString(),
            packageName,
            operation,
            mode,
        )

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private const val MIUI_BOOT_COMPLETED_OP = 10007
    private const val MIUI_AUTOSTART_OP = 10008
    private const val MIUI_BACKGROUND_START_ACTIVITY_OP = 10021
    private const val MIUI_SERVICE_FOREGROUND_OP = 10023
    const val MIUI_AUTOSTART_SWITCH_OP = 10053
    const val RUN_IN_BACKGROUND = "RUN_IN_BACKGROUND"
    const val RUN_ANY_IN_BACKGROUND = "RUN_ANY_IN_BACKGROUND"
}
