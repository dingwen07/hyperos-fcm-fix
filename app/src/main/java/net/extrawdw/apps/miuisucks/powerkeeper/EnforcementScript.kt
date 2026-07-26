package net.extrawdw.apps.miuisucks.powerkeeper

object EnforcementScript {
    val DEFAULT_TARGET_USERS = AndroidUserSelections.DEFAULT_ENABLED_USER_IDS.sorted()

    private val selfProtectionMiuiOps = listOf(
        MIUI_BOOT_COMPLETED_OP,
        MIUI_AUTOSTART_OP,
        MIUI_BACKGROUND_START_ACTIVITY_OP,
        MIUI_SERVICE_FOREGROUND_OP,
        MIUI_AUTOSTART_SWITCH_OP,
    )

    fun build(
        policies: Collection<AppPolicy>,
        applicationId: String,
        targetUsers: List<Int> = DEFAULT_TARGET_USERS,
        includeSelfProtection: Boolean = true,
    ): String {
        require(PACKAGE_NAME.matches(applicationId)) { "Invalid application ID" }
        require(policies.all { PACKAGE_NAME.matches(it.packageName) }) { "Invalid package name" }
        require(targetUsers.all { it >= 0 }) { "Invalid Android user ID" }
        val normalizedPolicies = policies.associateBy(AppPolicy::packageName).values.sortedBy(AppPolicy::packageName)
        val normalizedTargetUsers = targetUsers.distinct().sorted()

        return buildString {
            appendLine("attempts=0")
            appendLine("succeeded=0")
            appendLine("failed=0")
            appendLine("run_quiet() {")
            appendLine("  attempts=\$((attempts + 1))")
            appendLine("  command_output=\$(\"\$@\" 2>&1)")
            appendLine("  command_status=\$?")
            appendLine("  if [ \"\$command_status\" -eq 0 ]; then")
            appendLine("    succeeded=\$((succeeded + 1))")
            appendLine("  else")
            appendLine("    failed=\$((failed + 1))")
            appendLine("    printf 'FAILED [%s]: %s\\n' \"\$*\" \"\$command_output\"")
            appendLine("  fi")
            appendLine("}")
            appendLine("is_installed() {")
            appendLine("  pm list packages --user \"\$1\" \"\$2\" 2>/dev/null | grep -Fxq \"package:\$2\"")
            appendLine("}")
            appendLine("printf 'Per-app battery policy enforcement\\n'")
            appendLine("printf 'shell_uid=%s configured_apps=${normalizedPolicies.size}\\n' \"\$(id -u)\"")
            appendAppCommands(normalizedPolicies, normalizedTargetUsers)
            if (includeSelfProtection) appendSelfProtectionCommands(applicationId)
            appendLine("run_quiet cmd appops write-settings")
            appendLine("printf 'summary: %s/%s commands succeeded; %s failed\\n' \"\$succeeded\" \"\$attempts\" \"\$failed\"")
            appendLine("if [ \"\$failed\" -eq 0 ]; then exit 0; else exit 2; fi")
        }
    }

    private fun StringBuilder.appendAppCommands(policies: Collection<AppPolicy>, targetUsers: List<Int>) {
        val actionable = policies.filter { it.autostartManaged || it.dozePolicy != AppDozePolicy.OFF }
        if (actionable.isEmpty()) {
            appendLine("printf 'No per-app Autostart or AOSP battery policies selected\\n'")
            return
        }
        if (targetUsers.isEmpty()) {
            appendLine("printf 'No Android users selected; no per-app state changed\\n'")
            return
        }

        actionable.forEachIndexed { index, policy ->
            val variable = "app_found_$index"
            appendLine("$variable=0")
            appendLine("for target_user in ${targetUsers.joinToString(" ")}; do")
            appendLine("  if is_installed \"\$target_user\" '${policy.packageName}'; then")
            appendLine("    $variable=1")
            if (policy.autostartManaged) {
                val mode = if (policy.autostartEnabled) "allow" else "ignore"
                appendLine("    run_quiet cmd appops set --user \"\$target_user\" '${policy.packageName}' '$MIUI_AUTOSTART_OP' '$mode'")
                appendLine("    run_quiet cmd appops set --user \"\$target_user\" '${policy.packageName}' '$MIUI_AUTOSTART_SWITCH_OP' '$mode'")
            }
            if (policy.dozePolicy != AppDozePolicy.OFF) {
                val mode = if (policy.dozePolicy == AppDozePolicy.RESTRICTED) "ignore" else "allow"
                appendLine("    run_quiet cmd appops set --user \"\$target_user\" '${policy.packageName}' RUN_IN_BACKGROUND '$mode'")
                appendLine("    run_quiet cmd appops set --user \"\$target_user\" '${policy.packageName}' RUN_ANY_IN_BACKGROUND '$mode'")
            }
            appendLine("    printf '${policy.packageName} user %s: autostart=${if (policy.autostartManaged) policy.autostartEnabled else "unmanaged"} doze=${policy.dozePolicy.persistedValue}\\n' \"\$target_user\"")
            appendLine("  else")
            appendLine("    printf '${policy.packageName} user %s: skipped (not installed)\\n' \"\$target_user\"")
            appendLine("  fi")
            appendLine("done")
            if (policy.dozePolicy != AppDozePolicy.OFF) {
                val operation = if (policy.dozePolicy == AppDozePolicy.UNRESTRICTED) "+" else "-"
                appendLine("if [ \"\$$variable\" -eq 1 ]; then")
                appendLine("  run_quiet cmd deviceidle whitelist '$operation${policy.packageName}'")
                appendLine("fi")
            }
        }
    }

    private fun StringBuilder.appendSelfProtectionCommands(applicationId: String) {
        appendLine("printf 'Self-protection: user 0\\n'")
        appendLine("run_quiet cmd deviceidle whitelist '+$applicationId'")
        appendLine("run_quiet cmd appops set --user 0 '$applicationId' RUN_IN_BACKGROUND allow")
        appendLine("run_quiet cmd appops set --user 0 '$applicationId' RUN_ANY_IN_BACKGROUND allow")
        selfProtectionMiuiOps.forEach { op ->
            appendLine("run_quiet cmd appops set --user 0 '$applicationId' '$op' allow")
        }
        appendLine("run_quiet am set-inactive --user 0 '$applicationId' false")
        appendLine("run_quiet am set-standby-bucket --user 0 '$applicationId' active")
    }

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private const val MIUI_BOOT_COMPLETED_OP = 10007
    private const val MIUI_AUTOSTART_OP = 10008
    private const val MIUI_BACKGROUND_START_ACTIVITY_OP = 10021
    private const val MIUI_SERVICE_FOREGROUND_OP = 10023
    private const val MIUI_AUTOSTART_SWITCH_OP = 10053
}
