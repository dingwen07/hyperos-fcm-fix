package net.extrawdw.apps.miuisucks.powerkeeper

object EnforcementScript {
    const val WECHAT_PACKAGE = "com.tencent.mm"
    val DEFAULT_TARGET_USERS = AndroidUserSelections.DEFAULT_ENABLED_USER_IDS.sorted()

    private val selfProtectionMiuiOps = listOf(10007, 10008, 10021, 10023)

    fun build(
        policy: WechatPolicy,
        applicationId: String,
        targetUsers: List<Int> = DEFAULT_TARGET_USERS,
    ): String {
        require(applicationId.matches(Regex("[A-Za-z0-9_.]+"))) { "Invalid application ID" }
        require(targetUsers.all { it >= 0 }) { "Invalid Android user ID" }
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
            appendLine("printf 'Battery policy enforcement\\n'")
            appendLine("printf 'shell_uid=%s wechat_policy=${policy.persistedValue}\\n' \"\$(id -u)\"")
            appendWechatCommands(policy, normalizedTargetUsers)
            appendSelfProtectionCommands(applicationId)
            appendLine("run_quiet cmd appops write-settings")
            appendStatusCommands(policy, normalizedTargetUsers)
            appendLine("printf 'summary: %s/%s commands succeeded; %s failed\\n' \"\$succeeded\" \"\$attempts\" \"\$failed\"")
            appendLine("if [ \"\$failed\" -eq 0 ]; then exit 0; else exit 2; fi")
        }
    }

    private fun StringBuilder.appendWechatCommands(policy: WechatPolicy, targetUsers: List<Int>) {
        if (policy == WechatPolicy.DISABLED) {
            appendLine("printf 'WeChat policy: disabled; no WeChat state changed\\n'")
            return
        }
        if (targetUsers.isEmpty()) {
            appendLine("printf 'WeChat policy: no Android users selected; no WeChat state changed\\n'")
            return
        }

        val appOpMode = if (policy == WechatPolicy.OPTIMIZED) "allow" else "ignore"
        appendLine("wechat_found=0")
        appendLine("for target_user in ${targetUsers.joinToString(" ")}; do")
        appendLine("  if is_installed \"\$target_user\" '$WECHAT_PACKAGE'; then")
        appendLine("    wechat_found=1")
        appendLine("    run_quiet cmd appops set --user \"\$target_user\" '$WECHAT_PACKAGE' RUN_IN_BACKGROUND '$appOpMode'")
        appendLine("    run_quiet cmd appops set --user \"\$target_user\" '$WECHAT_PACKAGE' RUN_ANY_IN_BACKGROUND '$appOpMode'")
        appendLine("    printf 'WeChat user %s: ${policy.persistedValue}\\n' \"\$target_user\"")
        appendLine("  else")
        appendLine("    printf 'WeChat user %s: skipped (not installed)\\n' \"\$target_user\"")
        appendLine("  fi")
        appendLine("done")
        appendLine("if [ \"\$wechat_found\" -eq 1 ]; then")
        appendLine("  run_quiet cmd deviceidle whitelist '-$WECHAT_PACKAGE'")
        appendLine("fi")
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

    private fun StringBuilder.appendStatusCommands(policy: WechatPolicy, targetUsers: List<Int>) {
        if (policy == WechatPolicy.DISABLED || targetUsers.isEmpty()) return

        appendLine("for target_user in ${targetUsers.joinToString(" ")}; do")
        appendLine("  if is_installed \"\$target_user\" '$WECHAT_PACKAGE'; then")
        appendLine("    printf 'WeChat user %s background: ' \"\$target_user\"")
        appendLine("    cmd appops get --user \"\$target_user\" '$WECHAT_PACKAGE' RUN_ANY_IN_BACKGROUND 2>&1 | tr '\\n' ' '")
        appendLine("    printf '\\n'")
        appendLine("  fi")
        appendLine("done")
        appendLine("if cmd deviceidle whitelist | grep -Fq 'user,$WECHAT_PACKAGE,'; then")
        appendLine("  printf 'WeChat global Doze allowlist: still present\\n'")
        appendLine("else")
        appendLine("  printf 'WeChat global Doze allowlist: removed\\n'")
        appendLine("fi")
    }
}
