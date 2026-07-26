package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementScriptTest {
    private val applicationId = "net.extrawdw.apps.miuisucks.powerkeeper"

    @Test
    fun scriptTargetsOnlyOwnerAndXSpace() {
        val script = EnforcementScript.build(WechatPolicy.OPTIMIZED, applicationId)

        assertTrue(script.contains("for target_user in 0 999"))
        assertFalse(script.contains("--user 16"))
        assertFalse(script.contains("--user all"))
        assertFalse(script.contains("--uid"))
        assertFalse(script.contains("setUidMode"))
    }

    @Test
    fun customUserSelectionReplacesDefaultUsers() {
        val script = EnforcementScript.build(
            WechatPolicy.OPTIMIZED,
            applicationId,
            targetUsers = listOf(10),
        )

        assertTrue(script.contains("for target_user in 10"))
        assertFalse(script.contains("for target_user in 0 999"))
    }

    @Test
    fun emptyUserSelectionDoesNotMutateWechat() {
        val script = EnforcementScript.build(
            WechatPolicy.OPTIMIZED,
            applicationId,
            targetUsers = emptyList(),
        )

        assertTrue(script.contains("no Android users selected"))
        assertFalse(script.contains("cmd appops set --user \"\$target_user\" '${EnforcementScript.WECHAT_PACKAGE}'"))
    }

    @Test
    fun optimizedAllowsBackgroundButRemovesUnrestrictedAllowlist() {
        val script = EnforcementScript.build(WechatPolicy.OPTIMIZED, applicationId)

        assertTrue(script.contains("'$applicationId' RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(script.contains("'${EnforcementScript.WECHAT_PACKAGE}' RUN_ANY_IN_BACKGROUND 'allow'"))
        assertTrue(script.contains("whitelist '-${EnforcementScript.WECHAT_PACKAGE}'"))
    }

    @Test
    fun restrictedIgnoresWechatBackgroundOps() {
        val script = EnforcementScript.build(WechatPolicy.RESTRICTED, applicationId)

        assertTrue(script.contains("'${EnforcementScript.WECHAT_PACKAGE}' RUN_IN_BACKGROUND 'ignore'"))
        assertTrue(script.contains("'${EnforcementScript.WECHAT_PACKAGE}' RUN_ANY_IN_BACKGROUND 'ignore'"))
    }

    @Test
    fun disabledDoesNotMutateWechat() {
        val script = EnforcementScript.build(WechatPolicy.DISABLED, applicationId)

        assertTrue(script.contains("WeChat policy: disabled"))
        assertFalse(script.contains("'$applicationId' RUN_ANY_IN_BACKGROUND 'ignore'"))
        assertFalse(script.contains("whitelist '-${EnforcementScript.WECHAT_PACKAGE}'"))
    }

    @Test
    fun scriptDoesNotAlterPowerKeeper() {
        val script = EnforcementScript.build(WechatPolicy.OPTIMIZED, applicationId)

        assertFalse(script.contains("com.miui.powerkeeper"))
        assertFalse(script.contains("force-stop --user"))
    }

    @Test
    fun generatedScriptHasValidShellSyntax() {
        WechatPolicy.entries.forEach { policy ->
            val process = ProcessBuilder("/bin/sh", "-n").start()
            process.outputStream.bufferedWriter().use {
                it.write(EnforcementScript.build(policy, applicationId))
            }
            val error = process.errorStream.bufferedReader().readText()
            assertEquals("$policy: $error", 0, process.waitFor())
        }
    }
}
