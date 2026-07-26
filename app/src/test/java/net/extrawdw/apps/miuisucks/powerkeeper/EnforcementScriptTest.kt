package net.extrawdw.apps.miuisucks.powerkeeper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnforcementScriptTest {
    private val applicationId = "net.extrawdw.apps.miuisucks.powerkeeper"
    private val autoUnrestrictedPackage = AppPolicyDefaults.HYPEROS_AUTO_UNRESTRICTED_PACKAGES.first()
    private val autoUnrestrictedPolicy = AppPolicyDefaults.forPackage(autoUnrestrictedPackage)

    @Test
    fun scriptTargetsOnlyOwnerAndXSpace() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId)

        assertTrue(script.contains("for target_user in 0 999"))
        assertFalse(script.contains("--user 16"))
        assertFalse(script.contains("--user all"))
        assertFalse(script.contains("--uid"))
        assertFalse(script.contains("setUidMode"))
    }

    @Test
    fun customUserSelectionReplacesDefaultUsers() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId, targetUsers = listOf(10))

        assertTrue(script.contains("for target_user in 10"))
        assertFalse(script.contains("for target_user in 0 999"))
    }

    @Test
    fun emptyUserSelectionDoesNotMutateConfiguredApps() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId, targetUsers = emptyList())

        assertTrue(script.contains("No Android users selected"))
        assertFalse(script.contains("'$autoUnrestrictedPackage' RUN_ANY_IN_BACKGROUND"))
        assertFalse(script.contains("'$autoUnrestrictedPackage' '10008'"))
    }

    @Test
    fun optimizedBatteryPolicyAllowsBackgroundButRemovesUnrestrictedAllowlist() {
        val policy = AppPolicy("com.example.push", dozePolicy = AppDozePolicy.DEFAULT)
        val script = EnforcementScript.build(listOf(policy), applicationId)

        assertTrue(script.contains("'com.example.push' RUN_ANY_IN_BACKGROUND 'allow'"))
        assertTrue(script.contains("whitelist '-com.example.push'"))
    }

    @Test
    fun unrestrictedBatteryPolicyAddsUnrestrictedAllowlist() {
        val policy = AppPolicy("com.example.push", dozePolicy = AppDozePolicy.UNRESTRICTED)
        val script = EnforcementScript.build(listOf(policy), applicationId)

        assertTrue(script.contains("'com.example.push' RUN_ANY_IN_BACKGROUND 'allow'"))
        assertTrue(script.contains("whitelist '+com.example.push'"))
    }

    @Test
    fun configuredAutostartWritesBothXiaomiAppOps() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId)

        assertTrue(script.contains("'$autoUnrestrictedPackage' '10008' 'allow'"))
        assertTrue(script.contains("'$autoUnrestrictedPackage' '10053' 'allow'"))
    }

    @Test
    fun disablingManagedAutostartWritesIgnore() {
        val policy = AppPolicy("com.example.push", autostartManaged = true)
        val script = EnforcementScript.build(listOf(policy), applicationId)

        assertTrue(script.contains("'com.example.push' '10008' 'ignore'"))
        assertTrue(script.contains("'com.example.push' '10053' 'ignore'"))
    }

    @Test
    fun selfProtectionEnablesBothXiaomiAutostartAppOpsForOwner() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId)

        assertTrue(script.contains("cmd appops set --user 0 '$applicationId' '10008' allow"))
        assertTrue(script.contains("cmd appops set --user 0 '$applicationId' '10053' allow"))
        assertFalse(script.contains("cmd appops set --user 999 '$applicationId' '10008' allow"))
    }

    @Test
    fun restrictedPolicyIgnoresArbitraryAppBackgroundOps() {
        val policy = AppPolicy("com.example.push", dozePolicy = AppDozePolicy.RESTRICTED)
        val script = EnforcementScript.build(listOf(policy), applicationId)

        assertTrue(script.contains("'com.example.push' RUN_IN_BACKGROUND 'ignore'"))
        assertTrue(script.contains("'com.example.push' RUN_ANY_IN_BACKGROUND 'ignore'"))
    }

    @Test
    fun offUnmanagedPolicyDoesNotMutateApp() {
        val script = EnforcementScript.build(listOf(AppPolicy("com.example.push")), applicationId)

        assertTrue(script.contains("No per-app Autostart or AOSP battery policies selected"))
        assertFalse(script.contains("'com.example.push' RUN_ANY_IN_BACKGROUND"))
        assertFalse(script.contains("'com.example.push' '10008'"))
    }

    @Test
    fun scriptDoesNotAlterPowerKeeper() {
        val script = EnforcementScript.build(listOf(autoUnrestrictedPolicy), applicationId)

        assertFalse(script.contains("com.miui.powerkeeper"))
        assertFalse(script.contains("force-stop --user"))
    }

    @Test
    fun targetedPolicyDoesNotApplyManagerSelfProtection() {
        val policy = AppPolicy("com.example.push", autostartEnabled = true, autostartManaged = true)
        val script = EnforcementScript.build(
            listOf(policy),
            applicationId,
            includeSelfProtection = false,
        )

        assertTrue(script.contains("'com.example.push' '10008' 'allow'"))
        assertFalse(script.contains("'$applicationId' '10008' allow"))
        assertFalse(script.contains("whitelist '+$applicationId'"))
    }

    @Test
    fun generatedScriptHasValidShellSyntax() {
        val policies = listOf(
            autoUnrestrictedPolicy,
            AppPolicy("com.example.default", dozePolicy = AppDozePolicy.DEFAULT),
            AppPolicy(
                "com.example.restricted",
                autostartManaged = true,
                dozePolicy = AppDozePolicy.RESTRICTED,
            ),
        )
        val process = ProcessBuilder("/bin/sh", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(EnforcementScript.build(policies, applicationId)) }
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(error, 0, process.waitFor())
    }
}
