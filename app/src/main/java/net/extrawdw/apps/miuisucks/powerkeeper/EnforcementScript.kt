package net.extrawdw.apps.miuisucks.powerkeeper

object EnforcementScript {
    val DEFAULT_TARGET_USERS = AndroidUserSelections.DEFAULT_ENABLED_USER_IDS.sorted()
    const val PROGRESS_PREFIX = "__MIUIPOWERKEEPER_PROGRESS__:"

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
        includeWriteSettings: Boolean = true,
        installedPackagesByUser: Map<Int, Set<String>>? = null,
        progressStartIndex: Int? = null,
    ): String {
        require(PACKAGE_NAME.matches(applicationId)) { "Invalid application ID" }
        require(policies.all { PACKAGE_NAME.matches(it.packageName) }) { "Invalid package name" }
        require(targetUsers.all { it >= 0 }) { "Invalid Android user ID" }
        val normalizedPolicies = policies.associateBy(AppPolicy::packageName).values
            .sortedBy(AppPolicy::packageName)
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
            appendLine("printf 'Per-app battery policy enforcement\\n'")
            appendLine("printf 'shell_uid=%s configured_apps=${normalizedPolicies.size}\\n' \"\$(id -u)\"")
            appendAppCommands(
                normalizedPolicies,
                normalizedTargetUsers,
                installedPackagesByUser,
                progressStartIndex,
            )
            if (includeSelfProtection) appendSelfProtectionCommands(applicationId)
            if (includeWriteSettings) appendLine("run_quiet cmd appops write-settings")
            appendLine("printf 'summary: %s/%s commands succeeded; %s failed\\n' \"\$succeeded\" \"\$attempts\" \"\$failed\"")
            appendLine("if [ \"\$failed\" -eq 0 ]; then exit 0; else exit 2; fi")
        }
    }

    private fun StringBuilder.appendAppCommands(
        policies: Collection<AppPolicy>,
        targetUsers: List<Int>,
        installedPackagesByUser: Map<Int, Set<String>>?,
        progressStartIndex: Int?,
    ) {
        if (installedPackagesByUser != null && progressStartIndex != null) {
            appendResolvedAppCommands(
                policies,
                targetUsers,
                installedPackagesByUser,
                progressStartIndex,
            )
            return
        }
        val actionable = policies.filter { it.autostartManaged || it.dozeManaged }
        if (actionable.isEmpty()) {
            appendLine("printf 'No per-app Autostart or AOSP battery policies selected\\n'")
            return
        }
        if (targetUsers.isEmpty()) {
            appendLine("printf 'No Android users selected; no per-app state changed\\n'")
            return
        }

        if (installedPackagesByUser != null) {
            appendResolvedAppCommands(actionable, targetUsers, installedPackagesByUser, null)
        } else {
            appendInstalledPackageSnapshot(targetUsers)
            appendRuntimeAppCommands(actionable, targetUsers)
        }
    }

    private fun StringBuilder.appendInstalledPackageSnapshot(targetUsers: List<Int>) {
        targetUsers.forEach { userId ->
            val variable = "installed_packages_user_$userId"
            appendLine("attempts=\$((attempts + 1))")
            appendLine("$variable=\$(pm list packages --user '$userId' 2>&1)")
            appendLine("command_status=\$?")
            appendLine("if [ \"\$command_status\" -eq 0 ]; then")
            appendLine("  succeeded=\$((succeeded + 1))")
            appendLine("else")
            appendLine("  failed=\$((failed + 1))")
            appendLine("  printf 'FAILED [pm list packages --user %s]: %s\\n' '$userId' \"\$$variable\"")
            appendLine("  $variable=")
            appendLine("fi")
        }
        appendLine("is_installed() {")
        appendLine("  case \"\$1\" in")
        targetUsers.forEach { userId ->
            appendLine("    '$userId') installed_packages=\"\$installed_packages_user_$userId\" ;;")
        }
        appendLine("    *) return 1 ;;")
        appendLine("  esac")
        appendLine("  case \"")
        appendLine("\$installed_packages")
        appendLine("\" in")
        appendLine("    *\"")
        appendLine("package:\$2")
        appendLine("\"*) return 0 ;;")
        appendLine("    *) return 1 ;;")
        appendLine("  esac")
        appendLine("}")
    }

    private fun StringBuilder.appendRuntimeAppCommands(
        policies: Collection<AppPolicy>,
        targetUsers: List<Int>,
    ) {
        policies.forEachIndexed { index, policy ->
            val variable = "app_found_$index"
            appendLine("$variable=0")
            appendLine("for target_user in ${targetUsers.joinToString(" ")}; do")
            appendLine("  if is_installed \"\$target_user\" '${policy.packageName}'; then")
            appendLine("    $variable=1")
            appendPolicyMutations(policy, "\$target_user", "    ")
            appendLine("    printf '${policy.packageName} user %s: autostart=${if (policy.autostartManaged) policy.autostartEnabled else "unmanaged"} doze=${if (policy.dozeManaged) policy.dozePolicy.persistedValue else "unmanaged"}\\n' \"\$target_user\"")
            appendLine("  else")
            appendLine("    printf '${policy.packageName} user %s: skipped (not installed)\\n' \"\$target_user\"")
            appendLine("  fi")
            appendLine("done")
            if (policy.dozeManaged) {
                val operation = if (policy.dozePolicy == AppDozePolicy.UNRESTRICTED) "+" else "-"
                appendLine("if [ \"\$$variable\" -eq 1 ]; then")
                appendLine("  run_quiet cmd deviceidle whitelist '$operation${policy.packageName}'")
                appendLine("fi")
            }
        }
    }

    private fun StringBuilder.appendResolvedAppCommands(
        policies: Collection<AppPolicy>,
        targetUsers: List<Int>,
        installedPackagesByUser: Map<Int, Set<String>>,
        progressStartIndex: Int?,
    ) {
        policies.forEachIndexed { index, policy ->
            if (policy.autostartManaged || policy.dozeManaged) {
                var installed = false
                targetUsers.forEach { userId ->
                    if (policy.packageName in installedPackagesByUser[userId].orEmpty()) {
                        installed = true
                        appendPolicyMutations(policy, userId.toString(), "")
                        appendLine("printf '${policy.packageName} user $userId: autostart=${if (policy.autostartManaged) policy.autostartEnabled else "unmanaged"} doze=${if (policy.dozeManaged) policy.dozePolicy.persistedValue else "unmanaged"}\\n'")
                    } else {
                        appendLine("printf '${policy.packageName} user $userId: skipped (not installed)\\n'")
                    }
                }
                if (installed && policy.dozeManaged) {
                    val operation = if (policy.dozePolicy == AppDozePolicy.UNRESTRICTED) "+" else "-"
                    appendLine("run_quiet cmd deviceidle whitelist '$operation${policy.packageName}'")
                }
            } else {
                appendLine("printf '${policy.packageName}: no per-app policy selected\\n'")
            }
            if (progressStartIndex != null) {
                appendLine("printf '$PROGRESS_PREFIX${progressStartIndex + index + 1}\\n'")
            }
        }
    }

    private fun StringBuilder.appendPolicyMutations(
        policy: AppPolicy,
        userId: String,
        indent: String,
    ) {
        if (policy.autostartManaged) {
            val mode = if (policy.autostartEnabled) "allow" else "ignore"
            appendLine("${indent}run_quiet cmd appops set --user \"$userId\" '${policy.packageName}' '$MIUI_AUTOSTART_OP' '$mode'")
            appendLine("${indent}run_quiet cmd appops set --user \"$userId\" '${policy.packageName}' '$MIUI_AUTOSTART_SWITCH_OP' '$mode'")
        }
        if (policy.dozeManaged) {
            val mode = if (policy.dozePolicy == AppDozePolicy.RESTRICTED) "ignore" else "allow"
            appendLine("${indent}run_quiet cmd appops set --user \"$userId\" '${policy.packageName}' RUN_IN_BACKGROUND '$mode'")
            appendLine("${indent}run_quiet cmd appops set --user \"$userId\" '${policy.packageName}' RUN_ANY_IN_BACKGROUND '$mode'")
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
