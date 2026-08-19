package net.extrawdw.apps.miuisucks.powerkeeper

/** Builds the shell pass that clears FLAG_STOPPED without launching target apps. */
object UnstopScript {
    fun build(packageNames: Collection<String>, targetUsers: Collection<Int>): String {
        val packages = packageNames.distinct().sorted()
        val users = targetUsers.distinct().sorted()
        require(packages.all(PACKAGE_NAME::matches)) { "Invalid package name" }
        require(users.all { it >= 0 }) { "Invalid Android user ID" }

        if (packages.isEmpty() || users.isEmpty()) {
            return buildString {
                appendLine("printf 'Auto unstop: no packages or Android users selected\\n'")
                appendLine("exit 0")
            }
        }

        return buildString {
            appendLine("unstopped=0")
            appendLine("failed=0")
            appendLine("checked=0")
            users.forEach { userId ->
                val quotedUser = shellQuote(userId.toString())
                appendLine("state=\$(/system/bin/pm list packages --user $quotedUser --show-stopped 2>&1)")
                appendLine("state_exit=\$?")
                appendLine("if [ \"\$state_exit\" -ne 0 ]; then")
                appendLine("  failed=\$((failed + 1))")
                appendLine("  printf 'Auto unstop: failed to list user $userId\\n'")
                appendLine("else")
                packages.forEach { packageName ->
                    val quotedPackage = shellQuote(packageName)
                    appendLine("  case \"\$state\" in")
                    appendLine("    *\"package:$packageName stopped=true\"*)")
                    appendLine("      checked=\$((checked + 1))")
                    appendLine("      if /system/bin/pm unstop --user $quotedUser $quotedPackage >/dev/null 2>&1; then")
                    appendLine("        unstopped=\$((unstopped + 1))")
                    appendLine("        printf 'Auto unstop: user $userId package $packageName\\n'")
                    appendLine("      else")
                    appendLine("        failed=\$((failed + 1))")
                    appendLine("        printf 'Auto unstop: failed user $userId package $packageName\\n'")
                    appendLine("      fi")
                    appendLine("      ;;")
                    appendLine("  esac")
                }
                appendLine("fi")
            }
            appendLine("printf 'Auto unstop summary: checked=%s unstopped=%s failed=%s\\n' \"\$checked\" \"\$unstopped\" \"\$failed\"")
            appendLine("exit \$failed")
        }
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
}
